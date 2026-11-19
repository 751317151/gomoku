package com.gomoku.game;

import com.gomoku.model.GameMessage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 好友事件的 WS 实时推送（RoomManager identityByConn + FRIEND_EVENT）。
 *
 * 动机：好友面板此前只靠 15 秒 REST 轮询，好友上线/收到申请最多要等一个轮询周期；
 * 现在上下线边缘与申请/通过都即时推送。这里用 EmbeddedChannel 的出站帧钉死语义：
 * 只推给好友、上线边缘只触发一次、LEAVE 回大厅不掉线、双端标签页都收到。
 */
class RoomFriendNotifyTest {

    private static final Gson GSON = new Gson();

    private RoomManager manager;
    private FriendService friends;
    private Path dir;

    private static final String ID_A = "guest-notify-a";
    private static final String ID_B = "guest-notify-b";

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("friend-notify");
        EloService elo = new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json"));
        GameRecordService records = new GameRecordService(dir.resolve("games"));
        records.init();
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        friends = new FriendService(dir.resolve("friends.json"), accounts);
        friends.init();
        manager = new RoomManager(new SimpleMeterRegistry(), elo, records,
                new SeasonService(records, dir.resolve("seasons.json")), friends, new PartyService(),
                new EventService(dir.resolve("events.json")));
    }

    /** 读出某连接当前积压的全部出站帧文本（TextWebSocketFrame 内容），清空出站队列 */
    private static List<String> drain(EmbeddedChannel channel) {
        // Player.sendMessage 走 eventLoop().execute 排队；EmbeddedChannel 的任务不自动执行，先手动跑掉
        channel.runPendingTasks();
        List<String> texts = new ArrayList<>();
        Object frame;
        while ((frame = channel.readOutbound()) != null) {
            if (frame instanceof TextWebSocketFrame) {
                texts.add(((TextWebSocketFrame) frame).content().toString(StandardCharsets.UTF_8));
            }
        }
        return texts;
    }

    /** 读出出站帧里 FRIEND_EVENT 的 payload 列表（其他类型消息忽略） */
    private static List<Map<String, Object>> drainFriendEvents(EmbeddedChannel channel) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (String text : drain(channel)) {
            GameMessage m = GSON.fromJson(text, GameMessage.class);
            if (m.getType() == GameMessage.Type.FRIEND_EVENT && m.getData() != null) {
                payloads.add(GSON.fromJson(m.getData(), new TypeToken<Map<String, Object>>() {}.getType()));
            }
        }
        return payloads;
    }

    private void makeFriends() {
        friends.friendCodeOf(ID_A);
        friends.friendCodeOf(ID_B);
        friends.request(ID_B, ID_A);
        friends.accept(ID_A, ID_B);
    }

    @Test
    void onlineEdgePushedOnlyToFriends() {
        EmbeddedChannel a = new EmbeddedChannel();
        EmbeddedChannel b = new EmbeddedChannel();
        EmbeddedChannel stranger = new EmbeddedChannel();
        assertTrue(manager.bindIdentity(a, "甲", ID_A));
        makeFriends();

        // 陌生人（非好友）上线：甲不应收到任何事件
        assertTrue(manager.bindIdentity(stranger, "路人", "guest-notify-stranger"));
        assertEquals(List.of(), drainFriendEvents(a), "非好友上线不推送");

        // 好友乙上线：甲收到 online 事件，乙不收到（事件只发给对方好友）
        assertTrue(manager.bindIdentity(b, "好友乙", ID_B));
        List<Map<String, Object>> eventsToA = drainFriendEvents(a);
        assertEquals(1, eventsToA.size(), "好友上线应即时推送一条 online 事件");
        assertEquals("online", eventsToA.get(0).get("kind"));
        assertEquals(ID_B, eventsToA.get(0).get("id"), "事件应携带好友身份");
        assertEquals("好友乙", eventsToA.get(0).get("name"), "事件应携带好友昵称");
        assertEquals(List.of(), drainFriendEvents(b), "事件不应回推给当事人自己");
    }

    @Test
    void offlineEdgePushedOnceAfterLastConnectionCloses() {
        EmbeddedChannel a = new EmbeddedChannel();
        EmbeddedChannel b1 = new EmbeddedChannel();
        EmbeddedChannel b2 = new EmbeddedChannel();
        assertTrue(manager.bindIdentity(a, "甲", ID_A));
        makeFriends();
        assertTrue(manager.bindIdentity(b1, "好友乙", ID_B));
        assertTrue(manager.bindIdentity(b2, "好友乙", ID_B), "乙先双开，才有「掉一个连接不算下线」的前提");
        drain(a); // 清掉上线事件
        // 乙双开：第一个连接断开不算下线
        b1.close();
        manager.handleDisconnect(b1);
        assertEquals(List.of(), drainFriendEvents(a), "双开掉一个连接不应触发下线");

        // 最后一个连接断开才触发 offline
        b2.close();
        manager.handleDisconnect(b2);
        List<Map<String, Object>> events = drainFriendEvents(a);
        assertEquals(1, events.size(), "最后一条连接断开应推送一条 offline");
        assertEquals("offline", events.get(0).get("kind"));
    }

    @Test
    void secondConnectionDoesNotDuplicateOnlineEvent() {
        EmbeddedChannel a = new EmbeddedChannel();
        EmbeddedChannel b1 = new EmbeddedChannel();
        EmbeddedChannel b2 = new EmbeddedChannel();
        assertTrue(manager.bindIdentity(a, "甲", ID_A));
        makeFriends();
        assertTrue(manager.bindIdentity(b1, "好友乙", ID_B));
        assertEquals(1, drainFriendEvents(a).size(), "首次上线推送一次");
        assertTrue(manager.bindIdentity(b2, "好友乙", ID_B));
        assertEquals(List.of(), drainFriendEvents(a), "同身份第二个连接不应重复推送");
    }

    @Test
    void leavingRoomDoesNotNotifyOffline() {
        EmbeddedChannel a = new EmbeddedChannel();
        EmbeddedChannel b = new EmbeddedChannel();
        assertTrue(manager.bindIdentity(a, "甲", ID_A));
        makeFriends();
        assertTrue(manager.bindIdentity(b, "好友乙", ID_B));
        drain(a);
        // 乙进房再 LEAVE 回大厅（连接保持）
        manager.joinGame(b, "好友乙", null, GameType.GOMOKU, null, null, true);
        manager.leaveRoom(b);
        assertEquals(List.of(), drainFriendEvents(a), "离开房间回大厅不该被好友看到离线");
        assertEquals(Boolean.TRUE, manager.getIdentityPresence(ID_B).get("online"),
                "LEAVE 后身份仍应显示在线");
    }

    @Test
    void sendToIdentityReachesEveryTabOfTheIdentity() {
        EmbeddedChannel b1 = new EmbeddedChannel();
        EmbeddedChannel b2 = new EmbeddedChannel();
        assertTrue(manager.bindIdentity(b1, "好友乙", ID_B));
        assertTrue(manager.bindIdentity(b2, "好友乙", ID_B));
        drain(b1);
        drain(b2);

        manager.sendToIdentity(ID_B, RoomManager.friendEvent("request", ID_A, "甲"));
        assertEquals(1, drainFriendEvents(b1).size(), "第一个标签页应收到");
        assertEquals(1, drainFriendEvents(b2).size(), "第二个标签页也应收到");
    }

    @Test
    void friendEventPayloadIsWellFormed() {
        GameMessage msg = RoomManager.friendEvent("online", ID_B, "好友乙");
        assertEquals(GameMessage.Type.FRIEND_EVENT, msg.getType());
        Map<String, Object> payload = GSON.fromJson(msg.getData(), new TypeToken<Map<String, Object>>() {}.getType());
        assertEquals("online", payload.get("kind"));
        assertEquals(ID_B, payload.get("id"));
        assertEquals("好友乙", payload.get("name"));
    }
}
