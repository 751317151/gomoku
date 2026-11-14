package com.gomoku.game;

import com.gomoku.game.CardBattleEngine.Card;
import com.gomoku.game.CardBattleEngine.Deck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对局记录里的套牌信息
 *
 * 动机：记录页原来只有「谁赢谁输 + 手数」，看不出双方打的是什么流派，
 * 复盘价值被打折。这里钉住三件事：
 * 1) 预组能报出中文标签；
 * 2) 自定义套牌能报出配比摘要；
 * 3) 非法自定义回落均衡后，记录必须写「均衡」而不是玩家提交的「自定义」。
 */
class RecordDeckInfoTest {

    @Test
    void presetSummaryIsNullButLabelAvailable() {
        CardBattleEngine e = new CardBattleEngine(new Random(3));
        e.setDeck(1, Deck.AGGRO);
        e.reset();

        assertEquals(Deck.AGGRO, e.getDeck(1));
        assertEquals("快攻", e.getDeck(1).getLabel());
        assertNull(e.getDeckSummary(1), "预组不需要配比摘要（名字已说明流派）");
    }

    @Test
    void customDeckSummaryListsEveryCard() {
        CardBattleEngine e = new CardBattleEngine(new Random(3));
        Map<Card, Integer> custom = new LinkedHashMap<>();
        custom.put(Card.FOOTMAN, 6);
        custom.put(Card.ARCHER, 6);
        custom.put(Card.KNIGHT, 6);
        custom.put(Card.WAR_TITAN, 6);
        custom.put(Card.BLOOD_QUEEN, 6);
        assertNull(e.setCustomDeck(1, custom), "配比应合法");
        e.reset();

        assertEquals(Deck.CUSTOM, e.getDeck(1));
        assertEquals("自定义", e.getDeck(1).getLabel());

        String summary = e.getDeckSummary(1);
        assertNotNull(summary, "自定义套牌应有配比摘要");
        assertTrue(summary.contains("步兵×6"), summary);
        assertTrue(summary.contains("战争泰坦×6"), summary);
        assertTrue(summary.contains("血皇后×6"), summary);
        // 按费用升序：步兵(1) 在 泰坦(8) 之前
        assertTrue(summary.indexOf("步兵") < summary.indexOf("战争泰坦"), summary);
        // 五张卡应有四个分隔符
        assertEquals(4, summary.split(" · ").length - 1, summary);
    }

    @Test
    void invalidCustomFallsBackAndDoesNotLieInRecord() {
        CardBattleEngine e = new CardBattleEngine(new Random(3));
        // 只有 12 张 → 非法
        Map<Card, Integer> broken = new LinkedHashMap<>();
        broken.put(Card.FOOTMAN, 6);
        broken.put(Card.ARCHER, 6);
        assertNotNull(e.setCustomDeck(1, broken), "非法配比应被拒绝");

        // 模拟房间层的回落：非法时显式设回均衡
        e.setDeck(1, Deck.BALANCED);
        e.reset();

        assertEquals(Deck.BALANCED, e.getDeck(1), "回落后的实际套牌应是均衡");
        assertNull(e.getDeckSummary(1), "均衡套牌不应有自定义摘要");
    }

    @Test
    void deckInfoSurvivesRecordPersistence() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("record-deck");
        GameRecordService svc = new GameRecordService(dir);
        svc.init();

        GameRecord rec = new GameRecord();
        rec.setGameType("CARD");
        rec.setEndedAt(5000L);
        rec.setWinnerName("甲");
        rec.setMoveCount(18);
        rec.setRanked(false);

        GameRecord.Participant a = new GameRecord.Participant(
                "p1", "甲", 1, "WIN", false, 1200, 1200, 0);
        a.setDeck("CUSTOM");
        a.setDeckLabel("自定义");
        a.setDeckSummary("步兵×6 · 弓箭手×6 · 战争泰坦×6");
        GameRecord.Participant b = new GameRecord.Participant(
                "p2", "电脑", 2, "LOSE", true, 1200, 1200, 0);
        b.setDeck("CONTROL");
        b.setDeckLabel("控制");
        rec.getParticipants().add(a);
        rec.getParticipants().add(b);

        svc.saveAsync(rec);
        svc.flushForTest();

        GameRecord loaded = svc.getRecord(rec.getId());
        assertNotNull(loaded);
        GameRecord.Participant la = loaded.getParticipants().get(0);
        assertEquals("CUSTOM", la.getDeck());
        assertEquals("自定义", la.getDeckLabel());
        assertEquals("步兵×6 · 弓箭手×6 · 战争泰坦×6", la.getDeckSummary());

        GameRecord.Participant lb = loaded.getParticipants().get(1);
        assertEquals("CONTROL", lb.getDeck());
        assertEquals("控制", lb.getDeckLabel());
        assertNull(lb.getDeckSummary());

        // 列表摘要也必须带上（否则列表页看不到套牌）
        List<GameRecord> recent = svc.getRecentGames(5);
        assertEquals(1, recent.size());
        assertEquals("自定义", recent.get(0).getParticipants().get(0).getDeckLabel());
    }
}
