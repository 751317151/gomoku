package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 答题对战测试
 *
 * 重点是「同一步两人各自作答」的缓冲语义，以及揭晓前的信息隔离：
 * 如果对手的选择或正确答案提前泄漏，这个玩法就失去意义了。
 */
class QuizBattleEngineTest {

    private QuizBattleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new QuizBattleEngine(new Random(7));
    }

    @Test
    void initialState() {
        assertEquals(GameType.QUIZ, engine.type());
        assertEquals(1, engine.getCurrentTurn());
        assertEquals(0, engine.getScore(1));
        assertEquals(0, engine.getScore(2));
        assertFalse(engine.isOver());
        assertEquals(8, QuizBattleEngine.ROUNDS);
        assertEquals(25, engine.getStepTimeSeconds());
    }

    @Test
    void bothSidesAnswerSameQuestion() {
        // 先手作答后换手，题号不变
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(2, engine.getCurrentTurn(), "第一人答完换手");
        assertEquals(0, engine.getRoundIndex(), "题号尚未推进（等第二人）");
        assertFalse(engine.isOver());

        // 第二人作答 → 本题结算、题号 +1
        assertTrue(engine.applyMove(MoveCommand.action(1), 2));
        assertEquals(1, engine.getRoundIndex(), "两人都答完才推进题号");
    }

    @Test
    void rejectDoubleAnswerInSameRound() {
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        // 同一方在同一题不能再答
        assertFalse(engine.applyMove(MoveCommand.action(1), 1), "同一题不能重复作答");
    }

    @Test
    void eitherSideCanAnswerFirstAndBadChoicesAreRejected() {
        assertFalse(engine.applyMove(MoveCommand.action(4), 1), "选项越界");
        assertFalse(engine.applyMove(MoveCommand.action(-1), 1));
        // 坐标指令对答题无效
        assertFalse(engine.applyMove(MoveCommand.at(0, 1), 1), "不接受坐标指令");
        assertTrue(engine.applyMove(MoveCommand.action(0), 2), "双方均可先提交");
        assertFalse(engine.applyMove(MoveCommand.action(1), 0));
        assertFalse(engine.applyMove(MoveCommand.action(1), 3));
    }

    @Test
    void correctAnswerScores() {
        int correct = engine.correctIndexForAi();
        assertTrue(correct >= 0 && correct < 4);

        assertTrue(engine.applyMove(MoveCommand.action(correct), 1));
        assertTrue(engine.applyMove(MoveCommand.action((correct + 1) % 4), 2));

        assertEquals(QuizBattleEngine.SCORE_CORRECT, engine.getScore(1), "答对加分");
        assertEquals(0, engine.getScore(2), "答错扣分但不低于 0");
    }

    @Test
    void consecutiveCorrectAnswersGainComboBonus() {
        for (int round = 0; round < 2; round++) {
            int correct = engine.correctIndexForAi();
            assertTrue(engine.applyMove(MoveCommand.action(correct), 1));
            assertTrue(engine.applyMove(MoveCommand.action((correct + 1) % 4), 2));
        }
        assertEquals(25, engine.getScore(1), "第二次连续答对应获得 10 + 5 连击奖励");
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals(2, players.get(0).get("streak"));
    }

    @Test
    void wrongAnswerNeverGoesBelowZero() {
        int correct = engine.correctIndexForAi();
        int wrong = (correct + 1) % 4;
        assertTrue(engine.applyMove(MoveCommand.action(wrong), 1));
        assertTrue(engine.applyMove(MoveCommand.action(wrong), 2));
        assertEquals(0, engine.getScore(1), "首次答错扣 5 分但下限为 0");
        assertEquals(0, engine.getScore(2));
    }

    @Test
    void opponentChoiceHiddenBeforeReveal() {
        // 红方先答，蓝方的视角里不该出现红方的选择
        assertTrue(engine.applyMove(MoveCommand.action(2), 1));

        Map<String, Object> viewFromBlue = (Map<String, Object>) engine.getViewState(2);
        List<Map<String, Object>> players = (List<Map<String, Object>>) viewFromBlue.get("players");

        Map<String, Object> red = players.get(0);
        assertTrue((Boolean) red.get("answered"), "对手是否已答是公开的");
        assertFalse(red.containsKey("choice") || red.get("choice") != null,
                "揭晓前对手的选择必须不可见");

        // 红方自己看得到自己的选择
        Map<String, Object> viewFromRed = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> playersRed = (List<Map<String, Object>>) viewFromRed.get("players");
        assertEquals(2, ((Number) playersRed.get(0).get("choice")).intValue(), "自己的选择可见");
    }

    @Test
    void correctAnswerHiddenBeforeReveal() {
        Map<String, Object> before = (Map<String, Object>) engine.getViewState(1);
        assertNull(before.get("correctIndex"), "揭晓前不下发正确答案");
        assertEquals(Boolean.FALSE, before.get("revealed"));

        int correct = engine.correctIndexForAi();
        assertTrue(engine.applyMove(MoveCommand.action(correct), 1));
        assertTrue(engine.applyMove(MoveCommand.action(0), 2));

        Map<String, Object> after = (Map<String, Object>) engine.getViewState(1);
        assertEquals(Boolean.FALSE, after.get("revealed"));
        assertNull(after.get("correctIndex"), "新题不能携带上一题的正确下标");
        assertEquals(correct, ((Number) after.get("lastCorrect")).intValue(), "上一题答案单独公开");
    }

    @Test
    void bothChoicesVisibleAfterReveal() {
        assertTrue(engine.applyMove(MoveCommand.action(1), 1));
        assertTrue(engine.applyMove(MoveCommand.action(2), 2));

        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        assertEquals(List.of(1, 2), view.get("lastChoices"), "上一题选择独立公开");
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertNull(players.get(0).get("choice"));
        assertFalse(players.get(1).containsKey("choice"));
    }

    @Test
    void gameEndsAfterAllRounds() {
        // 双方都用 AI 答完所有题
        for (int i = 0; i < QuizBattleEngine.ROUNDS * 2 + 5 && !engine.isOver(); i++) {
            int side = engine.getCurrentTurn();
            int choice = QuizAI.INSTANCE.chooseAction(engine, side, 6);
            if (choice < 0) break;
            assertTrue(engine.applyMove(MoveCommand.action(choice), side));
        }
        assertTrue(engine.isOver(), "答满 8 题应结束");
        assertEquals(8, engine.getRoundIndex());
        assertNotNull(engine.getScoreSummary());
    }

    @Test
    void aiAnswersAreInRange() {
        for (int i = 0; i < 20; i++) {
            int side = engine.getCurrentTurn();
            int choice = QuizAI.INSTANCE.chooseAction(engine, side, 4);
            assertTrue(choice >= 0 && choice < 4, "AI 选项必须在范围内");
            assertTrue(engine.applyMove(MoveCommand.action(choice), side));
            if (engine.isOver()) break;
        }
    }

    @Test
    void resetClearsScoresAndProgress() {
        assertTrue(engine.applyMove(MoveCommand.action(1), 1));
        assertTrue(engine.applyMove(MoveCommand.action(1), 2));
        engine.reset();
        assertEquals(0, engine.getScore(1));
        assertEquals(0, engine.getScore(2));
        assertEquals(0, engine.getRoundIndex());
        assertEquals(1, engine.getCurrentTurn());
        assertFalse(engine.isOver());
    }
}
