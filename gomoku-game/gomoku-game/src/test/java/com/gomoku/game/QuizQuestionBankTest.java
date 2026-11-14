package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 题库与抽题策略
 *
 * 之前题库只有 10 题、每局随机取 8 题，容易出现「一局全是技术题」；
 * 现在按主题轮转抽题，这里把「主题分散」「题目合法」「不重复」钉住。
 */
class QuizQuestionBankTest {

    @Test
    void bankHasEnoughQuestionsAndCategories() {
        List<QuizBattleEngine.Question> pool = QuizBattleEngine.allQuestions();
        assertTrue(pool.size() >= QuizBattleEngine.ROUNDS * 2,
                "题库应至少是每局题数的两倍，实际 " + pool.size());

        Set<String> categories = new TreeSet<>();
        for (QuizBattleEngine.Question q : pool) categories.add(q.category);
        assertTrue(categories.size() >= 5, "主题数应 >= 5，实际 " + categories);
    }

    @Test
    void everyQuestionIsWellFormed() {
        for (QuizBattleEngine.Question q : QuizBattleEngine.allQuestions()) {
            assertNotNull(q.category, "题目缺少主题：" + q.text);
            assertFalse(q.category.isEmpty());
            assertNotNull(q.text);
            assertFalse(q.text.isEmpty());
            assertEquals(4, q.options.length, "每题应有 4 个选项：" + q.text);
            assertTrue(q.correctIndex >= 0 && q.correctIndex < 4,
                    "正确答案下标越界：" + q.text);
            // 选项不能有空、也不能重复（重复会让「只有一个正确答案」不成立）
            Set<String> seen = new HashSet<>();
            for (String o : q.options) {
                assertNotNull(o);
                assertFalse(o.trim().isEmpty(), "存在空选项：" + q.text);
                assertTrue(seen.add(o), "选项重复：" + q.text + " -> " + o);
            }
        }
    }

    @Test
    void selectionReturnsExactlyOneRound() {
        for (int seed = 0; seed < 50; seed++) {
            List<QuizBattleEngine.Question> picked =
                    QuizBattleEngine.selectQuestions(QuizBattleEngine.allQuestions(), new Random(seed));
            assertEquals(QuizBattleEngine.ROUNDS, picked.size(), "种子 " + seed + " 抽题数不对");
            assertEquals(picked.size(), new HashSet<>(picked).size(), "种子 " + seed + " 出现重复题");
        }
    }

    @Test
    void selectionSpreadsAcrossCategories() {
        // 主题轮转的意义：一局里应覆盖多个主题，而不是清一色
        for (int seed = 0; seed < 50; seed++) {
            List<QuizBattleEngine.Question> picked =
                    QuizBattleEngine.selectQuestions(QuizBattleEngine.allQuestions(), new Random(seed));
            Set<String> cats = new HashSet<>();
            for (QuizBattleEngine.Question q : picked) cats.add(q.category);
            assertTrue(cats.size() >= 5,
                    "种子 " + seed + " 的主题过于集中：" + cats);
        }
    }

    @Test
    void selectionIsRandomAcrossSeeds() {
        // 不能每次都抽到同一套题（否则「随机题库」名不副实）
        Set<String> firstQuestions = new HashSet<>();
        for (int seed = 0; seed < 20; seed++) {
            List<QuizBattleEngine.Question> picked =
                    QuizBattleEngine.selectQuestions(QuizBattleEngine.allQuestions(), new Random(seed));
            firstQuestions.add(picked.get(0).text);
        }
        assertTrue(firstQuestions.size() >= 3,
                "不同种子应抽到不同首题，实际只有 " + firstQuestions);
    }

    @Test
    void viewExposesCategory() {
        QuizBattleEngine engine = new QuizBattleEngine(new Random(3));
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> q = (Map<String, Object>) view.get("question");
        assertNotNull(q, "视图应带题目");
        assertNotNull(q.get("category"), "视图应带主题，供前端展示");
    }
}
