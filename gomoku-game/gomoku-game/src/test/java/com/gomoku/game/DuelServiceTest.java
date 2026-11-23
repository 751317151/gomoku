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

    private static DuelService.Started<String> accept(DuelService service, String target, String inviteId) {
        return service.accept(target, inviteId, duel -> duel.fromId + "-" + duel.toId);
    }

    @Test
    void invitationStoresOnlyIdentitiesAndValidatedGameType() {
        FixedClock service = new FixedClock();
        DuelService.Duel invite = service.invite("alice", "bob", "GOMOKU");
        assertTrue(invite.id.matches("DUEL-[A-HJ-NP-Z2-9]{6}"));
        assertEquals("alice", invite.fromId);
        assertEquals("bob", invite.toId);
        assertEquals("GOMOKU", invite.gameType);
        assertEquals(service.clock, invite.createdAt);
        assertEquals(service.clock + 120_000L, invite.expiresAt);
        assertSame(invite, service.findForTarget("bob", invite.id));
        assertEquals(List.of(invite), service.invitesFor("bob"));
        assertEquals(List.of(invite), service.outgoingFor("alice"));
    }

    @Test
    void incompleteOrBlankParametersAndSelfInviteLeaveNoState() {
        DuelService service = new DuelService();
        for (String invalid : new String[] {null, "", " \t\n"}) {
            assertCode("INVALID", () -> service.invite(invalid, "bob", "GOMOKU"));
            assertCode("INVALID", () -> service.invite("alice", invalid, "GOMOKU"));
            assertCode("INVALID", () -> service.invite("alice", "bob", invalid));
        }
        assertCode("INVALID", () -> service.invite("alice", "alice", "GOMOKU"));
        assertCode("INVALID", () -> service.invite("alice", "bob", "NO_SUCH_GAME"));
        assertEquals(0, service.pendingCount());
    }

    @Test
    void duplicateAndCrossedInvitesCannotReplaceOriginalGame() {
        DuelService service = new DuelService();
        DuelService.Duel original = service.invite("alice", "bob", "GOMOKU");

        assertCode("CONFLICT", () -> service.invite("alice", "bob", "UNO"));
        assertCode("CONFLICT", () -> service.invite("bob", "alice", "GOMOKU"));
        assertSame(original, service.findForTarget("bob", original.id));
        assertEquals(1, service.pendingCount());
    }

    @Test
    void outgoingLimitAndAcceptanceClearRelatedInvites() {
        DuelService service = new DuelService();
        for (int i = 0; i < 3; i++) service.invite("alice", "bob-" + i, "GOMOKU");

        assertCode("LIMIT", () -> service.invite("alice", "overflow", "GOMOKU"));
        DuelService.Duel first = service.invitesFor("bob-0").get(0);
        accept(service, "bob-0", first.id);
        service.invite("alice", "new-target", "GOMOKU");
        assertEquals(1, service.outgoingFor("alice").size(), "成功接受会清掉双方相关待处理邀请");
    }

    @Test
    void incomingLimitDoesNotAffectOtherRecipientsAndAcceptReleasesSlot() {
        DuelService service = new DuelService();
        for (int i = 0; i < 5; i++) service.invite("alice-" + i, "bob", "GOMOKU");

        assertCode("LIMIT", () -> service.invite("overflow", "bob", "GOMOKU"));
        accept(service, "bob", service.invitesFor("bob").get(0).id);
        service.invite("new-sender", "bob", "GOMOKU");
        assertEquals(1, service.invitesFor("bob").size(), "成功接受会清掉双方相关待处理邀请");
    }

    @Test
    void onlyRecipientCanReadAcceptOrDeclineWithoutConsumingInvite() {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "GOMOKU");
        for (String other : new String[] {"alice", "mallory", null}) {
            assertCode("UNAUTHORIZED", () -> service.findForTarget(other, invite.id));
            assertCode("UNAUTHORIZED", () -> service.accept(other, invite.id, ignored -> "bad"));
            assertCode("UNAUTHORIZED", () -> service.decline(other, invite.id));
        }
        assertSame(invite, service.findForTarget("bob", invite.id));
        assertEquals(1, service.pendingCount());
    }

    @Test
    void acceptConsumesOnlySelectedInviteAndCannotBeReplayed() {
        DuelService service = new DuelService();
        DuelService.Duel selected = service.invite("alice", "bob", "GOMOKU");
        DuelService.Duel other = service.invite("charlie", "dave", "UNO");

        assertEquals("alice-bob", accept(service, "bob", selected.id).result);
        assertCode("NOT_FOUND", () -> service.accept("bob", selected.id, ignored -> "bad"));
        assertCode("NOT_FOUND", () -> service.decline("bob", selected.id));
        assertSame(other, service.findForTarget("dave", other.id));
        assertTrue(service.outgoingFor("alice").isEmpty());
        assertTrue(service.invitesFor("bob").isEmpty());
    }

    @Test
    void declineAllowsPairToInviteAgainAndPreservesUnrelatedInvites() {
        DuelService service = new DuelService();
        DuelService.Duel selected = service.invite("alice", "bob", "GOMOKU");
        DuelService.Duel other = service.invite("charlie", "dave", "UNO");

        assertSame(selected, service.decline("bob", selected.id));
        assertCode("NOT_FOUND", () -> service.findForTarget("bob", selected.id));
        assertSame(other, service.findForTarget("dave", other.id));
        service.invite("bob", "alice", "GOMOKU");
        assertEquals(2, service.pendingCount());
    }

    @Test
    void expirationBoundaryRejectsAcceptAndDeclineAndReleasesBothQuotas() {
        FixedClock service = new FixedClock();
        DuelService.Duel invite = service.invite("alice", "bob", "GOMOKU");
        service.clock = invite.expiresAt - 1;
        assertSame(invite, service.findForTarget("bob", invite.id));

        service.clock = invite.expiresAt;
        assertCode("NOT_FOUND", () -> service.accept("bob", invite.id, ignored -> "bad"));
        assertCode("NOT_FOUND", () -> service.decline("bob", invite.id));
        assertTrue(service.invitesFor("bob").isEmpty());
        assertTrue(service.outgoingFor("alice").isEmpty());
        assertEquals(0, service.pendingCount());
        service.invite("bob", "alice", "GOMOKU");
        assertEquals(1, service.pendingCount());
    }

    @Test
    void expiredInvitesCannotBlockNewInvitesAtQuota() {
        FixedClock service = new FixedClock();
        for (int i = 0; i < 3; i++) service.invite("alice", "bob-" + i, "GOMOKU");
        for (int i = 0; i < 5; i++) service.invite("other-" + i, "bob", "GOMOKU");
        service.clock += 120_000L;

        service.invite("alice", "bob", "GOMOKU");
        assertEquals(1, service.pendingCount());
    }

    @Test
    void missingInvitationReportsNotFound() {
        DuelService service = new DuelService();
        for (String missing : new String[] {null, "", "DUEL-UNKNOWN"}) {
            assertCode("NOT_FOUND", () -> service.findForTarget("bob", missing));
            assertCode("NOT_FOUND", () -> service.accept("bob", missing, ignored -> "bad"));
            assertCode("NOT_FOUND", () -> service.decline("bob", missing));
        }
        assertEquals(0, service.pendingCount());
    }

    @Test
    void listSnapshotsSurviveLaterConsumptionWithoutExposingMutableState() {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "GOMOKU");
        List<DuelService.Duel> received = service.invitesFor("bob");
        List<DuelService.Duel> sent = service.outgoingFor("alice");
        assertThrows(UnsupportedOperationException.class, received::clear);
        assertThrows(UnsupportedOperationException.class, sent::clear);

        accept(service, "bob", invite.id);
        assertEquals(List.of(invite), received);
        assertEquals(List.of(invite), sent);
        assertEquals(0, service.pendingCount());
    }

    @Test
    void simultaneousAcceptsConsumeInvitationOnce() throws Exception {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "GOMOKU");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> accept = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("accept start timed out");
                try {
                    service.accept("bob", invite.id, ignored -> { Thread.yield(); return "ok"; });
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
            assertTrue(outcomes.contains("CONFLICT") || outcomes.contains("NOT_FOUND"), outcomes.toString());
            assertEquals(0, service.pendingCount());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedLaunchPreservesInviteAndReleasesBothPlayersForRetry() {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "UNO");
        assertThrows(IllegalStateException.class, () -> service.accept("bob", invite.id, ignored -> {
            throw new IllegalStateException("room unavailable");
        }));
        assertSame(invite, service.findForTarget("bob", invite.id));
        assertEquals("alice-bob", accept(service, "bob", invite.id).result);
        assertEquals(0, service.pendingCount());
    }

    @Test
    void successfulLaunchClearsAllInvitesInvolvingEitherParticipant() {
        DuelService service = new DuelService();
        DuelService.Duel selected = service.invite("alice", "bob", "UNO");
        DuelService.Duel outgoing = service.invite("alice", "charlie", "GOMOKU");
        DuelService.Duel incoming = service.invite("dave", "bob", "GOMOKU");
        DuelService.Duel reversed = service.invite("bob", "eve", "GOMOKU");
        DuelService.Duel unrelated = service.invite("frank", "grace", "GOMOKU");
        DuelService.Started<String> started = accept(service, "bob", selected.id);
        assertEquals(List.of(selected, outgoing, incoming, reversed), started.clearedInvites);
        assertThrows(UnsupportedOperationException.class, started.clearedInvites::clear);
        assertSame(unrelated, service.findForTarget("grace", unrelated.id));
        assertEquals(1, service.pendingCount());
    }

    @Test
    void onlySenderCanCancelAndCancellationReleasesQuota() {
        DuelService service = new DuelService();
        DuelService.Duel invite = service.invite("alice", "bob", "GOMOKU");
        assertCode("UNAUTHORIZED", () -> service.cancel("bob", invite.id));
        assertCode("UNAUTHORIZED", () -> service.cancel(null, invite.id));
        assertSame(invite, service.cancel("alice", invite.id));
        assertCode("NOT_FOUND", () -> service.cancel("alice", invite.id));
        service.invite("bob", "alice", "UNO");
        assertEquals(1, service.pendingCount());
    }

    @Test
    void launchReleasesServiceLockButFreezesOverlappingActionsUntilCompletion() throws Exception {
        FixedClock service = new FixedClock();
        DuelService.Duel selected = service.invite("alice", "bob", "UNO");
        DuelService.Duel overlapping = service.invite("bob", "charlie", "GOMOKU");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            Future<DuelService.Started<String>> launching = pool.submit(() -> service.accept("bob", selected.id, ignored -> {
                entered.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("launch finish timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return "started";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // 列表读取不应等待房间开局锁；到期回收也不能拿走正在开局的邀请。
            service.clock = selected.expiresAt;
            assertEquals(2, service.pendingCount());
            assertCode("CONFLICT", () -> service.accept("charlie", overlapping.id, ignored -> "bad"));
            assertCode("CONFLICT", () -> service.decline("bob", selected.id));
            assertCode("CONFLICT", () -> service.cancel("alice", selected.id));
            assertCode("CONFLICT", () -> service.invite("alice", "dave", "GOMOKU"));
            service.invite("eve", "frank", "GOMOKU");
            finish.countDown();
            assertEquals("started", launching.get(5, TimeUnit.SECONDS).result);
            assertEquals(1, service.pendingCount());
        } finally {
            finish.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
