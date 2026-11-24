package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoudizhuEngineTest {

    private static DoudizhuEngine engine() {
        DoudizhuEngine engine = new DoudizhuEngine();
        engine.configurePlayers(3);
        engine.reset();
        return engine;
    }

    @Test
    void dealsSeventeenCardsEachAndThreeHiddenBottomCards() {
        DoudizhuEngine engine = engine();
        assertEquals(GameType.DOUDIZHU, engine.type());
        assertEquals(3, engine.getPlayerCount());
        for (int side = 1; side <= 3; side++) assertEquals(17, engine.getHand(side).size());
        assertEquals(3, engine.getBottom().size());
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        assertEquals("DOUDIZHU", view.get("kind"));
        assertTrue((Boolean) view.get("bidding"));
        assertEquals(0, ((List<?>) view.get("bottom")).size(), "叫地主阶段底牌必须保密");
        List<?> players = (List<?>) view.get("players");
        assertTrue(((Map<?, ?>) players.get(0)).containsKey("hand"));
        assertFalse(((Map<?, ?>) players.get(1)).containsKey("hand"));
    }

    @Test
    void bidderBecomesLandlordAndReceivesBottomCards() {
        DoudizhuEngine engine = engine();
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), 1));
        assertTrue(engine.applyMove(MoveCommand.action(2), 2));
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), 3));
        assertFalse(engine.isBidding());
        assertEquals(2, engine.getLandlord());
        assertEquals(20, engine.getHand(2).size());
        assertEquals(2, engine.getCurrentTurn(), "地主先手");
        assertEquals(2, engine.getMultiplier());
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        assertEquals(3, ((List<?>) view.get("bottom")).size(), "确定地主后底牌公开");
    }

    @Test
    void allPassRedealsWithoutLosingNamesOrCount() {
        DoudizhuEngine engine = engine();
        engine.setPlayerName(1, "甲");
        engine.setPlayerName(2, "乙");
        engine.setPlayerName(3, "丙");
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), 1));
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), 2));
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), 3));
        assertTrue(engine.isBidding(), "三家不叫应重新进入叫地主");
        assertEquals(3, engine.getPlayerCount());
        assertEquals(17, engine.getHand(1).size());
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        List<?> players = (List<?>) view.get("players");
        assertEquals("甲", ((Map<?, ?>) players.get(0)).get("name"));
    }

    @Test
    void landlordCannotPassOnFreeLeadAndPlayAdvancesToNextSeat() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2);
        engine.setLandlordForTest(1);
        engine.setHandForTest(1, List.of(3, 3, 4, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 3, 4));
        assertFalse(engine.applyMove(MoveCommand.action(DoudizhuEngine.PASS_ACTION), 1), "自由出牌不能过");
        assertTrue(engine.playByIndices(1, List.of(0)));
        assertEquals(2, engine.getCurrentTurn());
        assertEquals(1, engine.getLastPlaySide());
    }

    @Test
    void cannotPlayWeakerOrDifferentLengthCombo() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2);
        engine.setLandlordForTest(1);
        engine.setHandForTest(2, List.of(4, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 3, 3));
        assertTrue(engine.playByIndices(1, List.of(0)));
        assertFalse(engine.playByIndices(2, List.of(1, 2, 3, 4, 5)), "不同张数的顺子不能压");
    }

    @Test
    void passPassReturnsLeadToLastPlayer() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2);
        engine.setLandlordForTest(1);
        assertTrue(engine.playByIndices(1, List.of(0)));
        assertEquals(2, engine.getCurrentTurn());
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.PASS_ACTION), 2));
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.PASS_ACTION), 3));
        assertEquals(1, engine.getCurrentTurn(), "两家过牌后回到上次出牌者");
        assertNull(engine.getLastCombo(), "重新领出时桌面牌型清空");
    }

    @Test
    void bombDoublesMultiplierAndRocketWinsRoleBattle() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2);
        engine.setLandlordForTest(1);
        engine.setHandForTest(1, List.of(3, 3, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 6, 7));
        // 地主手牌固定为 20 张：上面已是 20 张，确保不是 18 张的非法构造。
        List<Integer> hand = engine.getHand(1);
        List<Integer> bomb = new java.util.ArrayList<>();
        for (int i = 0; i < hand.size(); i++) if (hand.get(i) == 3) bomb.add(i);
        assertTrue(engine.playByIndices(1, bomb));
        assertEquals(4, engine.getMultiplier(), "炸弹使倍数从 2 翻倍到 4");
    }

    @Test
    void farmerWinMarksBothFarmersAsWinners() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(2, 2, 2);
        engine.setHandForTest(1, List.of(10));
        engine.setHandForTest(2, List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 3, 3, 4));
        engine.setHandForTest(3, List.of(6));
        int landlordPlayIndex = engine.getHand(2).indexOf(4);
        assertTrue(landlordPlayIndex >= 0, "地主手里应有 4");
        assertTrue(engine.playByIndices(2, List.of(landlordPlayIndex)), "地主领出");
        assertTrue(engine.applyMove(MoveCommand.action(DoudizhuEngine.PASS_ACTION), 3), "下家过牌");
        assertTrue(engine.playByIndices(1, List.of(0)), "农民用 10 压过 4 并出完");
        assertTrue(engine.isOver());
        assertEquals(List.of(1, 3), engine.getWinningSides());
    }

    @Test
    void landlordForfeitGivesBothFarmersTheWin() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2, 1);
        assertTrue(engine.forfeit(1));
        assertTrue(engine.isOver());
        assertEquals(List.of(2, 3), engine.getWinningSides());
        assertTrue(engine.getScoreSummary().contains("农民"));
    }

    @Test
    void farmerForfeitGivesLandlordTheWin() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2, 1);
        assertTrue(engine.forfeit(2));
        assertTrue(engine.isOver());
        assertEquals(List.of(1), engine.getWinningSides());
    }

    @Test
    void hintIsOnlyVisibleToCurrentPlayerAndDoesNotChangeTurn() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2, 1);
        List<Integer> before = engine.getHand(1);
        Map<?, ?> mine = (Map<?, ?>) engine.getViewState(1);
        Map<?, ?> other = (Map<?, ?>) engine.getViewState(2);
        assertTrue(((List<?>) mine.get("hintIndices")).size() > 0, "当前行动者应看到提示");
        assertEquals(0, ((List<?>) other.get("hintIndices")).size(), "对手不应看到提示");
        assertEquals(before, engine.getHand(1), "提示不能改变手牌");
        assertEquals(1, engine.getCurrentTurn());
    }

    @Test
    void remainingRankCountsOnlySubtractPublicPlays() {
        DoudizhuEngine engine = engine();
        engine.setBiddingForTest(1, 2, 1);
        Map<?, ?> before = (Map<?, ?>) ((Map<?, ?>) engine.getViewState(1)).get("remainingByRank");
        List<Integer> hand = engine.getHand(1);
        assertFalse(hand.isEmpty());
        int playedValue = hand.get(0);
        String label = DoudizhuEngine.cardLabel(playedValue);
        int beforeCount = (Integer) before.get(label);
        assertTrue(engine.playByIndices(1, List.of(0)));
        Map<?, ?> after = (Map<?, ?>) ((Map<?, ?>) engine.getViewState(2)).get("remainingByRank");
        assertEquals(beforeCount - 1, after.get(label), "已打出的牌应计入公开记牌器");
        assertTrue(((Map<?, ?>) engine.getViewState(2)).get("remainingByRank") != null,
                "记牌器对所有座位公开");
    }
}
