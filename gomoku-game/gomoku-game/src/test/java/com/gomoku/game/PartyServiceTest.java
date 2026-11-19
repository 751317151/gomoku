package com.gomoku.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 队伍单测：建队、邀请、接受、队长移交、解散、上限与邀请过期。 */
class PartyServiceTest {

    /** 可控时钟，便于验证邀请有效期 */
    private static class FixedClock extends PartyService {
        long clock = 1_700_000_000_000L;
        @Override long now() { return clock; }
        void advanceMinutes(long minutes) { clock += minutes * 60_000L; }
    }

    private FixedClock newParty() {
        return new FixedClock();
    }

    @Test
    void firstInviteCreatesPartyAndLeader() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        PartyService.Party party = service.partyOf("a");
        assertNotNull(party);
        assertEquals("a", party.leaderId, "邀请人自动成为队长");
        assertEquals(1, party.members.size(), "被邀请人接受前不入队");
        assertEquals(party.id, invite.partyId);
        assertEquals("b", invite.toId);
    }

    @Test
    void acceptAddsMemberAndClearsInvite() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        PartyService.Party party = service.accept("b", invite.id);
        assertEquals(2, party.members.size());
        assertEquals(party.id, service.partyOf("b").id);
        assertTrue(service.invitesFor("b").isEmpty(), "接受后邀请应消失");
    }

    @Test
    void nonLeaderCannotInvite() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        service.accept("b", invite.id);
        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> service.invite("b", "c"));
        assertEquals("UNAUTHORIZED", ex.getCode(), "只有队长能邀请");
    }

    @Test
    void cannotInviteSelfOrDuplicate() {
        FixedClock service = newParty();
        assertThrows(PartyService.PartyException.class, () -> service.invite("a", "a"));
        service.invite("a", "b");
        PartyService.PartyException dup = assertThrows(PartyService.PartyException.class,
                () -> service.invite("a", "b"));
        assertEquals("CONFLICT", dup.getCode());
    }

    @Test
    void cannotAcceptWhenAlreadyInAnotherParty() {
        FixedClock service = newParty();
        PartyService.Invite first = service.invite("a", "b");
        PartyService.Invite second = service.invite("c", "b");
        service.accept("b", first.id);
        // 接受后其余待处理邀请会被作废，所以这里拿到的是「邀请已失效」而不是「已在队伍中」。
        // 关键断言是：b 不会被拉进第二支队伍。
        assertThrows(PartyService.PartyException.class, () -> service.accept("b", second.id));
        assertEquals("a", service.partyOf("b").leaderId, "b 仍留在第一支队伍");
        assertTrue(service.partyOf("c").members.size() == 1, "c 的队伍没有多出成员");
    }

    @Test
    void onlyIntendedRecipientCanAccept() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> service.accept("c", invite.id));
        assertEquals("UNAUTHORIZED", ex.getCode());
    }

    @Test
    void invitesExpire() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        service.advanceMinutes(3);
        assertTrue(service.invitesFor("b").isEmpty(), "超过 2 分钟的邀请应过期");
        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> service.accept("b", invite.id));
        assertEquals("NOT_FOUND", ex.getCode());
    }

    @Test
    void leaderLeavingTransfersLeadership() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        service.accept("b", invite.id);
        PartyService.Party after = service.leave("a");
        assertNotNull(after, "还有成员时队伍不 disband");
        assertEquals("b", after.leaderId, "队长离开后移交给下一位成员");
        assertEquals(1, after.members.size());
    }

    @Test
    void lastMemberLeavingDisbandsParty() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        service.accept("b", invite.id);
        service.leave("a");
        PartyService.Party after = service.leave("b");
        assertNull(after, "最后一人离开即解散");
        assertNull(service.partyOf("b"));
    }

    @Test
    void memberCapEnforced() {
        FixedClock service = newParty();
        for (int i = 0; i < PartyService.MAX_MEMBERS - 1; i++) {
            PartyService.Invite invite = service.invite("leader", "m" + i);
            service.accept("m" + i, invite.id);
        }
        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> service.invite("leader", "overflow"));
        assertEquals("LIMIT", ex.getCode(), "满员后不能再邀请");
    }

    @Test
    void duplicateInvitesToSameTargetPendingAreRejected() {
        FixedClock service = newParty();
        service.invite("a", "b");
        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> service.invite("a", "b"));
        assertEquals("CONFLICT", ex.getCode(), "等待回应期间不重复发邀请");
        assertEquals(1, service.invitesFor("b").size());
    }

    @Test
    void declineRemovesInviteOnly() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        service.decline("b", invite.id);
        assertTrue(service.invitesFor("b").isEmpty());
        assertEquals(1, service.partyOf("a").members.size(), "拒绝不影响发起方队伍");
    }

    @Test
    void partyIdIsShortCodeFormat() {
        FixedClock service = newParty();
        PartyService.Invite invite = service.invite("a", "b");
        assertTrue(service.partyOf("a").id.matches("PTY-[A-Z2-9]{4}"), "队伍短码便于展示");
        assertTrue(invite.id.matches("PINV-[A-Z2-9]{6}"));
    }
}
