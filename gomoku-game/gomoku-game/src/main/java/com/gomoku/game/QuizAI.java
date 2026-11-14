package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 答题 AI
 *
 * 这个 AI 有一个设计上的诚实点必须说明：**它知道正确答案**（直接问引擎）。
 * 之所以这样做，是因为「让 AI 真的会做题」需要外部知识库/模型，超出本项目的范围；
 * 而如果让它纯随机，玩家每局都能碾压，玩法就没意义了。
 *
 * 所以难度直接映射成正确率，而不是假装它在「思考」：
 * - 简单(2)：40% 答对
 * - 中等(4)：70% 答对
 * - 困难(6)：90% 答对
 *
 * 这个取舍在 UI 和文档里都如实标注，避免让玩家误以为 AI 真在理解题目。
 */
public class QuizAI implements GameAI {

    static final QuizAI INSTANCE = new QuizAI();

    private QuizAI() {}

    @Override
    public int chooseAction(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof QuizBattleEngine)) return -1;
        QuizBattleEngine quiz = (QuizBattleEngine) engine;
        if (!quiz.isAwaitingAction(side)) return -1;

        int correct = quiz.correctIndexForAi();
        if (correct < 0) return -1;

        boolean answerCorrectly = ThreadLocalRandom.current().nextDouble() < accuracy(difficulty);
        if (answerCorrectly) return correct;

        // 答错时随机挑一个不等于正确答案的选项
        int wrong;
        do {
            wrong = ThreadLocalRandom.current().nextInt(4);
        } while (wrong == correct);
        return wrong;
    }

    private double accuracy(int difficulty) {
        switch (difficulty) {
            case 2: return 0.4;
            case 6: return 0.9;
            default: return 0.7;
        }
    }
}
