package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DuelServiceTest {

    private static class FixedClock extends DuelService {
        long clock = 1_700_000_000_000L;
        @Override long now() { return clock; }
    }

    private void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(DuelService.DuelException.class, action::run).getCode());
    }

    @Test
    void invitationPreservesRoomAndRecipientWithTwoMinuteLifetime() {
        FixedClock service = new FixedClock();
        DuelService.Duel invite = service.invite("alice", "bob", "private-room", "GOMOKU");

        assertTrue(invite.id.matches("DUEL-[A-HJ-NP-Z2-9]{6}"));
        assertEquals("alice", invite.fromId);
        assertEquals("bob", invite.toId);
        assertEquals("private-room", invite.roomId);
        assertEquals("GOMOKU", invite.gameType);
        assertEquals(service.clock, invite.createdAt);
        assertEquals(service.clock + 120_000L, invite.expiresAt);
        assertSame(invite, service.findForTarget("bob", invite.id));
        assertEquals(List.of(invite), service.invitesFor("bob"));
        assertEquals(List.of(invite), service.outgoingFor("alice"));
        assertTrue(service.invitesFor("alice").isEmpty());
        assertTrue(service.outgoingFor("bob").isEmpty());
    }

    @Test
    void incompleteOrBlankParametersAndSelfInviteLeaveNoState() {
        DuelService service = new DuelService();
        for (String invalid : new String[] {null, "", " \t\n"}) {
            assertCode("INVALID", () -> service.invite(invalid, "bob", "room", "GOMOKU"));
            assertCode("INVALID", () -> service.invite("alice", invalid, "room", "GOMOKU"));
            assertCode("INVALID", () -> service.invite("alice", "bob", invalid, "GOMOKU"));
            assertCode("INVALID", () -> service.invite("alice", "bob", "room", invalid));
        }
        assertCode("INVALID", () -> service.invite("alice", "alice", "room", "GOMOKU"));
        assertEquals(0, service.pendingCount());
    }

    @Test
    void duplicateAndCrossedInvitesCannotReplaceOriginalRoom() {
        DuelService service = new DuelService();
        DuelService.Duel original = service.invite("alice", "bob", "original-room", "GOMOKU");

        assertCode("CONFLICT", () -> service.invite("alice", "bob", "other-room", "UNO"));
        assertCode("CONFLICT", () -> service.invite("bob", "alice", "other-room", "UNO"));
        assertSame(original, service.findForTarget("bob", original.id));
        assertEquals(1, service.pendingCount());
    }

    @Test
    void outgoingLimitDoesNotAffectOtherSendersAndDeclineReleasesSlot() {
        DuelService service = new DuelService();
        for (int i = 0; i < 3; i++) service.invite("alice", "bob-" + i, "room", "GOMOKU");

        assertCode("LIMIT", () -> service.invite("alice", "overflow", "room", "GOMOKU"));
        assertEquals(3, service.pendingCount());
        assertTrue(service.invitesFor("overflow").isEmpty());
        service.invite("other", "someone", "room", "GOMOKU");
        DuelService.Duel first = service.invitesFor("bob-0").get(0);
        service.decline("bob-0", first.id);
        service.invite("alice", "new-target", "room", "GOMOKU");
        assertEquals(3, service.outgoingFor("alice").size());
    }

    @Test
    void incomingLimitDoesNotAffectOtherRecipientsAndAcceptReleasesSlot() {
        DuelService service = new DuelService();
        for (int i = 0; i < 5; i++) service.invite("alice-" + i, "bob", "room", "GOMOKU");

        assertCode("LIMIT", () -> service.invite("overflow", "bob", "room", "GOMOKU"));
        assertEquals(5, service.pendingCount());
        assertTrue(service.outgoingFor("overflow").isEmpty());
        service.invite("other", "someone", "room", "GOMOKU");
        service.accept("bob", service.invitesFor("bob").get(0).id);
        service.invite("new-sender", "bob", "room", "GOMOKU");
        assertEquals(5, service.invitesFor("bob").size());
    }

    @Test
    void onlyRecipientCanReadAcceptOrDeclineWithoutConsumingInvite() {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "room", "GOMOKU");
        for (String other : new String[] {"alice", "mallory", null}) {
            assertCode("UNAUTHORIZED", () -> service.findForTarget(other, invite.id));
            assertCode("UNAUTHORIZED", () -> service.accept(other, invite.id));
            assertCode("UNAUTHORIZED", () -> service.decline(other, invite.id));
        }
        assertSame(invite, service.findForTarget("bob", invite.id));
        assertEquals(1, service.pendingCount());
    }

    @Test
    void acceptConsumesOnlySelectedInviteAndCannotBeReplayed() {
        DuelService service = new DuelService();
        DuelService.Duel selected = service.invite("alice", "bob", "room-1", "GOMOKU");
        DuelService.Duel other = service.invite("charlie", "dave", "room-2", "UNO");

        assertSame(selected, service.accept("bob", selected.id));
        assertCode("NOT_FOUND", () -> service.accept("bob", selected.id));
        assertCode("NOT_FOUND", () -> service.decline("bob", selected.id));
        assertSame(other, service.findForTarget("dave", other.id));
        assertTrue(service.outgoingFor("alice").isEmpty());
        assertTrue(service.invitesFor("bob").isEmpty());
    }

    @Test
    void declineAllowsPairToInviteAgainAndPreservesUnrelatedInvites() {
        DuelService service = new DuelService();
        DuelService.Duel selected = service.invite("alice", "bob", "room", "GOMOKU");
        DuelService.Duel other = service.invite("charlie", "dave", "room", "UNO");

        assertSame(selected, service.decline("bob", selected.id));
        assertCode("NOT_FOUND", () -> service.findForTarget("bob", selected.id));
        assertSame(other, service.findForTarget("dave", other.id));
        service.invite("bob", "alice", "new-room", "GOMOKU");
        assertEquals(2, service.pendingCount());
    }

    @Test
    void expirationBoundaryRejectsAcceptAndDeclineAndReleasesBothQuotas() {
        FixedClock service = new FixedClock();
        DuelService.Duel invite = service.invite("alice", "bob", "room", "GOMOKU");
        service.clock = invite.expiresAt - 1;
        assertSame(invite, service.findForTarget("bob", invite.id));

        service.clock = invite.expiresAt;
        assertCode("NOT_FOUND", () -> service.accept("bob", invite.id));
        assertCode("NOT_FOUND", () -> service.decline("bob", invite.id));
        assertTrue(service.invitesFor("bob").isEmpty());
        assertTrue(service.outgoingFor("alice").isEmpty());
        assertEquals(0, service.pendingCount());
        service.invite("bob", "alice", "next-room", "GOMOKU");
        assertEquals(1, service.pendingCount());
    }

    @Test
    void expiredInvitesCannotBlockNewInvitesAtQuota() {
        FixedClock service = new FixedClock();
        for (int i = 0; i < 3; i++) service.invite("alice", "bob-" + i, "room", "GOMOKU");
        for (int i = 0; i < 5; i++) service.invite("other-" + i, "bob", "room", "GOMOKU");
        service.clock += 120_000L;

        service.invite("alice", "bob", "new-room", "GOMOKU");
        assertEquals(1, service.pendingCount());
    }

    @Test
    void droppingAnInvalidRoomInviteIsIdempotentAndDoesNotRemoveOthers() {
        DuelService service = new DuelService();
        DuelService.Duel invalid = service.invite("alice", "bob", "closed-room", "GOMOKU");
        DuelService.Duel other = service.invite("charlie", "dave", "room", "UNO");
        service.drop(invalid.id);
        service.drop(invalid.id);
        service.drop("unknown");
        service.drop(null);

        assertCode("NOT_FOUND", () -> service.findForTarget("bob", invalid.id));
        assertSame(other, service.findForTarget("dave", other.id));
        assertEquals(1, service.pendingCount());
    }

    @Test
    void missingInvitationReportsNotFound() {
        DuelService service = new DuelService();
        for (String missing : new String[] {null, "", "DUEL-UNKNOWN"}) {
            assertCode("NOT_FOUND", () -> service.findForTarget("bob", missing));
            assertCode("NOT_FOUND", () -> service.accept("bob", missing));
            assertCode("NOT_FOUND", () -> service.decline("bob", missing));
        }
        assertEquals(0, service.pendingCount());
    }

    @Test
    void listSnapshotsSurviveLaterConsumptionWithoutExposingMutableState() {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "room", "GOMOKU");
        List<DuelService.Duel> received = service.invitesFor("bob");
        List<DuelService.Duel> sent = service.outgoingFor("alice");
        assertThrows(UnsupportedOperationException.class, received::clear);
        assertThrows(UnsupportedOperationException.class, sent::clear);

        service.accept("bob", invite.id);
        assertEquals(List.of(invite), received);
        assertEquals(List.of(invite), sent);
        assertEquals(0, service.pendingCount());
    }

    @Test
    void simultaneousAcceptsConsumeInvitationOnce() throws Exception {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "room", "GOMOKU");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> accept = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("accept start timed out");
                try {
                    service.accept("bob", invite.id);
                    return "ACCEPTED";
                } catch (DuelService.DuelException e) {
                    return e.getCode();
                }
            };
            Future<String> first = pool.submit(accept);
            Future<String> second = pool.submit(accept);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertTrue(outcomes.contains("ACCEPTED"), outcomes.toString());
            assertTrue(outcomes.contains("NOT_FOUND"), outcomes.toString());
            assertEquals(0, service.pendingCount());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
