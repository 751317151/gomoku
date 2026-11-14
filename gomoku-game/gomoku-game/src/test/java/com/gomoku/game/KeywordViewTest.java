package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 验证前端能拿到的关键词字段完整且正确（前端徽标完全依赖这些字段） */
class KeywordViewTest {

    @Test
    @SuppressWarnings("unchecked")
    void boardExposesAllKeywords() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.ARCHER);       // 冲锋
        e.putMinionForTest(1, CardBattleEngine.Card.GUARDIAN);     // 嘲讽
        e.putMinionForTest(1, CardBattleEngine.Card.BERSERKER);    // 战吼
        e.putMinionForTest(1, CardBattleEngine.Card.BONE_DRAGON);  // 亡语

        Map<String, Object> view = (Map<String, Object>) e.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        List<Map<String, Object>> board = (List<Map<String, Object>>) players.get(0).get("board");

        assertEquals(4, board.size());

        Map<String, Map<String, Object>> byLabel = new HashMap<>();
        for (Map<String, Object> m : board) {
            byLabel.put((String) m.get("label"), m);
        }

        assertEquals(Boolean.TRUE, byLabel.get("弓箭手").get("charge"), "弓箭手应标 charge");
        assertEquals(Boolean.TRUE, byLabel.get("守卫").get("taunt"), "守卫应标 taunt");
        assertEquals(Boolean.TRUE, byLabel.get("狂战士").get("battlecry"), "狂战士应标 battlecry");
        assertEquals(Boolean.TRUE, byLabel.get("骨龙").get("deathrattle"), "骨龙应标 deathrattle");

        // 未带关键词的不应误标
        assertEquals(Boolean.FALSE, byLabel.get("守卫").get("charge"));
        assertEquals(Boolean.FALSE, byLabel.get("弓箭手").get("taunt"));
        assertEquals(Boolean.FALSE, byLabel.get("狂战士").get("deathrattle"));

        // canAttack：冲锋可立即攻击，其余有召唤失调
        assertEquals(Boolean.TRUE, byLabel.get("弓箭手").get("canAttack"), "冲锋当回合可攻击");
        assertEquals(Boolean.FALSE, byLabel.get("守卫").get("canAttack"), "普通随从召唤失调");
    }
}
