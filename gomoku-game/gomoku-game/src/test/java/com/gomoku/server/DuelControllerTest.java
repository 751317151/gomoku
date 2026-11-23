package com.gomoku.server;

import com.gomoku.game.*;
import com.gomoku.model.GameMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REST 权限、错误码和开局失败可重试；邀请/房间实体的竞争条件另由服务测试覆盖。 */
class DuelControllerTest {
    private DuelService duels;
    private FriendService friends;
    private RoomManager rooms;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        duels = new DuelService();
        friends = mock(FriendService.class);
        rooms = mock(RoomManager.class);
        GuestIdentityService guests = mock(GuestIdentityService.class);
        when(guests.resolve("token-a")).thenReturn("a");
        when(guests.resolve("token-b")).thenReturn("b");
        when(guests.resolve("token-c")).thenReturn("c");
        when(friends.areFriends(anyString(), anyString())).thenReturn(true);
        when(rooms.getIdentityPresence(anyString())).thenAnswer(call -> Map.of("name", "玩家" + call.getArgument(0)));
        mvc = MockMvcBuilders.standaloneSetup(new DuelController(duels, friends, rooms,
                mock(AccountService.class), mock(GameRecordService.class), guests,
                mock(ModerationService.class))).build();
    }

    private DuelService.Duel invitation() {
        return duels.invite("a", "b", "GOMOKU");
    }

    @Test
    void allEndpointsRequireValidBearerIdentity() throws Exception {
        mvc.perform(get("/api/duels")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/duels").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/duels").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/duels/id/accept")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/duels/id/decline")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/duels/id")).andExpect(status().isUnauthorized());
        assertEquals(0, duels.pendingCount());
        verifyNoInteractions(rooms);
    }

    @Test
    void inviteAndListsReturnNamesServerClockAndNoStore() throws Exception {
        mvc.perform(post("/api/duels").header("Authorization", "Bearer token-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"b\",\"gameType\":\" gomoku \"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.invite.fromName").value("玩家a"))
                .andExpect(jsonPath("$.invite.gameType").value("GOMOKU"));
        mvc.perform(get("/api/duels").header("Authorization", "Bearer token-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.incoming[0].gameLabel").value("五子棋"))
                .andExpect(jsonPath("$.outgoing").isEmpty()).andExpect(jsonPath("$.serverTime").isNumber());
        mvc.perform(get("/api/duels").header("Authorization", "Bearer token-c"))
                .andExpect(jsonPath("$.incoming").isEmpty()).andExpect(jsonPath("$.outgoing").isEmpty());
        verify(rooms).validateDuelPlayers(eq("a"), eq("b"), any());
        verify(rooms, never()).startDuelGame(any(), any(), any(), any());
        verify(rooms).sendToIdentity(eq("b"), any(GameMessage.class));
    }

    @Test
    void malformedMissingAndUnsupportedInputsDoNotCreateInvites() throws Exception {
        for (String body : new String[] {"{}", "null", "{", "{\"target\":\"b\",\"gameType\":\"UNKNOWN\"}",
                "{\"target\":\"a\",\"gameType\":\"GOMOKU\"}", "{\"target\":{},\"gameType\":\"GOMOKU\"}"}) {
            mvc.perform(post("/api/duels").header("Authorization", "Bearer token-a")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"));
        }
        assertEquals(0, duels.pendingCount());
    }

    @Test
    void nonFriendsAndOfflineOrBannedPlayersCannotInvite() throws Exception {
        when(friends.areFriends("a", "b")).thenReturn(false);
        mvc.perform(post("/api/duels").header("Authorization", "Bearer token-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"b\",\"gameType\":\"GOMOKU\"}"))
                .andExpect(status().isBadRequest());
        when(friends.areFriends("a", "b")).thenReturn(true);
        doThrow(new DuelService.DuelException("CONFLICT", "不在线"))
                .when(rooms).validateDuelPlayers(eq("a"), eq("b"), any());
        mvc.perform(post("/api/duels").header("Authorization", "Bearer token-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"b\",\"gameType\":\"GOMOKU\"}"))
                .andExpect(status().isConflict());
        doThrow(new DuelService.DuelException("UNAVAILABLE", "无法参加对局"))
                .when(rooms).validateDuelPlayers(eq("a"), eq("b"), any());
        mvc.perform(post("/api/duels").header("Authorization", "Bearer token-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"b\",\"gameType\":\"GOMOKU\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, duels.pendingCount());
    }

    @Test
    void onlyRecipientCanAcceptOrDeclineAndOnlySenderCanCancel() throws Exception {
        DuelService.Duel invite = invitation();
        for (String other : new String[] {"token-a", "token-c"}) {
            mvc.perform(post("/api/duels/" + invite.id + "/accept").header("Authorization", "Bearer " + other))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/duels/" + invite.id + "/decline").header("Authorization", "Bearer " + other))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(delete("/api/duels/" + invite.id).header("Authorization", "Bearer token-b"))
                .andExpect(status().isForbidden());
        assertEquals(1, duels.pendingCount());
        verify(rooms, never()).startDuelGame(any(), any(), any(), any());
    }

    @Test
    void launchFailurePreservesInviteAndAllowsRetry() throws Exception {
        DuelService.Duel invite = invitation();
        GameRoom room = mock(GameRoom.class);
        when(room.getRoomId()).thenReturn("ROOM-ABCDEF");
        when(room.getGameType()).thenReturn(GameType.GOMOKU);
        when(rooms.startDuelGame(eq("a"), eq("b"), eq(GameType.GOMOKU), any()))
                .thenThrow(new DuelService.DuelException("CONFLICT", "好友仍在房间中"))
                .thenReturn(room);
        mvc.perform(post("/api/duels/" + invite.id + "/accept").header("Authorization", "Bearer token-b"))
                .andExpect(status().isConflict());
        assertEquals(1, duels.pendingCount());
        mvc.perform(post("/api/duels/" + invite.id + "/accept").header("Authorization", "Bearer token-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value("ROOM-ABCDEF"));
        mvc.perform(post("/api/duels/" + invite.id + "/accept").header("Authorization", "Bearer token-b"))
                .andExpect(status().isNotFound());
        assertEquals(0, duels.pendingCount());
    }

    @Test
    void removedFriendshipIsRevalidatedAtAcceptance() throws Exception {
        DuelService.Duel invite = invitation();
        when(friends.areFriends("a", "b")).thenReturn(false);
        mvc.perform(post("/api/duels/" + invite.id + "/accept").header("Authorization", "Bearer token-b"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("好友关系已解除，不能接受这条约战"));
        verify(rooms, never()).startDuelGame(any(), any(), any(), any());
    }

    @Test
    void declineAndCancelNotifyBothSidesAndCannotReplay() throws Exception {
        DuelService.Duel first = invitation();
        mvc.perform(post("/api/duels/" + first.id + "/decline").header("Authorization", "Bearer token-b"))
                .andExpect(status().isOk());
        DuelService.Duel second = invitation();
        mvc.perform(delete("/api/duels/" + second.id).header("Authorization", "Bearer token-a"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/duels/" + second.id).header("Authorization", "Bearer token-a"))
                .andExpect(status().isNotFound());
        assertEquals(0, duels.pendingCount());
        verify(rooms, times(2)).sendToIdentity(eq("a"), any(GameMessage.class));
        verify(rooms, times(2)).sendToIdentity(eq("b"), any(GameMessage.class));
    }

    @Test
    void successfulLaunchRefreshesThirdPartiesWhoseInvitesWereCleared() throws Exception {
        DuelService.Duel first = invitation();
        duels.invite("a", "c", "UNO");
        GameRoom room = mock(GameRoom.class);
        when(room.getRoomId()).thenReturn("ROOM-ABCDEF");
        when(room.getGameType()).thenReturn(GameType.GOMOKU);
        when(rooms.startDuelGame(eq("a"), eq("b"), eq(GameType.GOMOKU), any())).thenReturn(room);
        mvc.perform(post("/api/duels/" + first.id + "/accept").header("Authorization", "Bearer token-b"))
                .andExpect(status().isOk());
        assertEquals(0, duels.pendingCount());
        verify(rooms).sendToIdentity(eq("c"), any(GameMessage.class));
    }
}
