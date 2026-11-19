package com.gomoku.game;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gomoku.dto.GameSyncDto;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 组队进房：全队要么一起进同一间私房，要么谁都不动。
 *
 * 这批用例钉死三件事，都是「先各自 JOIN 再补通知」这种实现会挂的地方：
 * 1. 失败路径不留半个房间、不移动任何人的连接；
 * 2. 人数必须匹配所选玩法（否则房间永远开不了局）；
 * 3. 同一身份只占一个座位——多标签页会被拒绝而不是复制席位。
 */
class PartyLaunchTest {

    private RoomManager rooms;
    private PartyService parties;
    private FriendService friends;
    private AccountService accounts;
    private GuestIdentityService guests;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        friends = new FriendService(dir.resolve("friends.json"), accounts);
        friends.init();
        parties = new PartyService();
        GameRecordService records = new GameRecordService(dir.resolve("games"));
        records.init();
        rooms = new RoomManager(new SimpleMeterRegistry(),
                new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json")),
                records, new SeasonService(records, dir.resolve("seasons.json")),
                friends, parties, new EventService());
    }

    /** 组一支「leader + mates」的队伍，并把每个人的连接绑定到各自身份。 */
    private PartyService.Party formParty(String prefix, String leader, String... mates) {
        PartyService.Invite invite;
        for (String mate : mates) {
            invite = parties.invite(leader, mate);
            parties.accept(mate, invite.id);
        }
        return parties.partyOf(leader);
    }

    private EmbeddedChannel online(String identityId, String name) {
        EmbeddedChannel channel = new EmbeddedChannel();
        assertTrue(rooms.bindIdentity(channel, name, identityId));
        return channel;
    }

    @Test
    void 全队一起进入同一间私房并开局() {
        String leader = "guest-l1";
        String mate = "guest-l2";
        EmbeddedChannel c1 = online(leader, "队长");
        EmbeddedChannel c2 = online(mate, "队员");
        PartyService.Party party = formParty("PTY-L", leader, mate);

        GameRoom room = rooms.startPartyGame(party, GameType.GOMOKU, id -> false);

        assertEquals(GameRoom.State.PLAYING, room.getState(), "人数够了应直接开局");
        assertEquals(2, room.getPlayers().size());
        assertEquals(room.getRoomId(), rooms.getRoomByPlayer(rooms.getPlayer(c1)).getRoomId());
        assertEquals(room.getRoomId(), rooms.getRoomByPlayer(rooms.getPlayer(c2)).getRoomId());
        assertTrue(room.isPrivateRoom(), "组队开局用私房：不出现在大厅列表、不参与匹配");
        assertTrue(rooms.getRoomsList().stream().noneMatch(r -> r.getRoomId().equals(room.getRoomId())),
                "私房不进公共列表");
    }

    @Test
    void 容量按队伍人数而不是玩法默认人数() {
        // 多人玩法默认 4 人：容量若按默认值开，2 人队伍会永远停在等待中。
        String leader = "guest-n1";
        String mate = "guest-n2";
        online(leader, "队长");
        online(mate, "队员");
        PartyService.Party party = formParty("PTY-N", leader, mate);

        GameRoom room = rooms.startPartyGame(party, GameType.UNO, id -> false);

        assertEquals(2, room.getTargetPlayers(), "容量应等于队伍人数");
        assertEquals(GameRoom.State.PLAYING, room.getState(), "2 人队伍打 UNO 应立刻开局");
    }

    @Test
    void 有人离线时拒绝且谁都进不去() {
        String leader = "guest-o1";
        String mate = "guest-o2";
        online(leader, "队长");   // 队员不连
        PartyService.Party party = formParty("PTY-O", leader, mate);

        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> rooms.startPartyGame(party, GameType.GOMOKU, id -> false));

        assertTrue(ex.getMessage().contains("不在线"), ex.getMessage());
        assertNull(rooms.getRoomByPlayer(rooms.getPlayer(online(leader, "队长"))), "队长也不该被单独放进房间");
        assertEquals(0, ((Number) rooms.getStats().get("totalRooms")).intValue());
    }

    @Test
    void 队员仍在别的房间时拒绝而不是把人拽出来() {
        String leader = "guest-r1";
        String mate = "guest-r2";
        online(leader, "队长");
        EmbeddedChannel mateChannel = online(mate, "队员");
        // 队员先自己占了一间等待房
        GameRoom other = rooms.joinGame(mateChannel, "队员", null, GameType.GOMOKU, null, null, true);
        assertNotNull(other);
        PartyService.Party party = formParty("PTY-R", leader, mate);

        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> rooms.startPartyGame(party, GameType.GOMOKU, id -> false));

        assertTrue(ex.getMessage().contains("房间"), ex.getMessage());
        assertSame(other, rooms.getRoomByPlayer(rooms.getPlayer(mateChannel)), "队员应留在原房间，不应被跨局拽走");
    }

    @Test
    void bannedMemberBlocksLaunch() {
        String leader = "guest-b1";
        String mate = "guest-b2";
        online(leader, "队长");
        online(mate, "队员");
        PartyService.Party party = formParty("PTY-B", leader, mate);

        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> rooms.startPartyGame(party, GameType.GOMOKU, id -> mate.equals(id)));
        assertTrue(ex.getMessage().contains("无法参加对局"), ex.getMessage());
        assertEquals(0, ((Number) rooms.getStats().get("totalRooms")).intValue(),
                "被拒时不建房间");
    }

    @Test
    void 同一身份多标签页时不复制席位而是拒绝() {
        String leader = "guest-t1";
        String mate = "guest-t2";
        online(leader, "队长");
        online(mate, "队员标签一");
        online(mate, "队员标签二");   // 同一身份第二条连接
        PartyService.Party party = formParty("PTY-T", leader, mate);

        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> rooms.startPartyGame(party, GameType.GOMOKU, id -> false));

        assertTrue(ex.getMessage().contains("多个"), ex.getMessage());
        assertEquals(0, ((Number) rooms.getStats().get("totalRooms")).intValue());
    }

    @Test
    void 队长不在队里时不能带队() {
        String leader = "guest-x1";
        String mate = "guest-x2";
        online(leader, "队长");
        online(mate, "队员");
        PartyService.Party party = formParty("PTY-X", leader, mate);

        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> parties.launch(mate, p -> rooms.startPartyGame(p, GameType.GOMOKU, id -> false)));

        assertTrue(ex.getMessage().contains("队长"), ex.getMessage());
    }

    @Test
    void seatDataCarriesPartyBadge() {
        String leader = "guest-k1";
        String mate = "guest-k2";
        EmbeddedChannel c1 = online(leader, "队长");
        online(mate, "队员");
        PartyService.Party party = formParty("PTY-K", leader, mate);

        GameRoom room = rooms.startPartyGame(party, GameType.GOMOKU, id -> false);

        // 房间同步会读队伍标识；这也顺带证明开局没有长期持有队伍锁（否则这里会挂住）
        GameSyncDto sync = room.buildSyncDataForReconnect(rooms.getPlayer(c1));
        assertEquals(2, sync.getRoomData().getPlayers().size());
        assertTrue(sync.getRoomData().getPlayers().stream()
                        .allMatch(p -> party.id.equals(p.getPartyId())),
                "两个座位都应带同一队伍标识");
    }

    @Test
    void 队伍人数超过玩法上限时提示人数范围() {
        String leader = "guest-c1";
        online(leader, "队长");
        String[] mates = {"guest-c2", "guest-c3"};
        for (String mate : mates) online(mate, "队员" + mate.charAt(mate.length() - 1));
        PartyService.Party party = formParty("PTY-C", leader, mates);

        PartyService.PartyException ex = assertThrows(PartyService.PartyException.class,
                () -> rooms.startPartyGame(party, GameType.GOMOKU, id -> false));

        assertTrue(ex.getMessage().contains("3 人"), ex.getMessage());
        assertEquals(0, ((Number) rooms.getStats().get("totalRooms")).intValue());
    }
}
