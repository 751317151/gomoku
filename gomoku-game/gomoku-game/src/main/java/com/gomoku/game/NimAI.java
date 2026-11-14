package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 取石子博弈 AI — 基于 Bouton 定理的完美策略
 *
 * Nim 的胜负完全由「异或和」决定：
 * - 异或和 ≠ 0 → 存在一手能把异或和变成 0（必胜态）
 * - 异或和 = 0 → 无论怎么拿都会把必胜态让给对手（必败态）
 *
 * 难度差异体现在「是否总是走最优」：
 * - 简单(2)：40% 概率随机拿，让新手有机会赢
 * - 中等(4)：80% 走最优
 * - 困难(6)：100% 完美解，人类在数学上无法赢
 */
public class NimAI implements GameAI {

    static final NimAI INSTANCE = new NimAI();

    private NimAI() {}

    /**
     * 返回 {pileIndex, takeCount}。
     *
     * 注意这里返回的是「堆号 + 数量」，而 {@link GameAI#decide} 默认按坐标构造指令，
     * 正好对应 NimBattleEngine 的 row/col 约定，无需额外适配。
     */
    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        return null; // Nim 不是 BoardGameEngine
    }

    /**
     * Nim 不走「行动序号」通道，因此不实现 chooseAction；
     * 它覆写 decide 直接构造坐标指令。
     */
    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof NimBattleEngine)) return null;
        NimBattleEngine nim = (NimBattleEngine) engine;
        if (nim.getCurrentTurn() != side || !nim.hasAnyMove()) return null;

        int[] piles = nim.getPiles();
        int[] optimal = optimalMove(piles);

        // 难度决定「是否听话」：拿不准时随机拿一手，给人类留机会
        boolean trustOptimal = ThreadLocalRandom.current().nextDouble() < accuracy(difficulty);
        if (!trustOptimal || optimal == null) {
            int[] random = randomMove(piles);
            return random == null ? null : MoveCommand.at(random[0], random[1]);
        }
        return MoveCommand.at(optimal[0], optimal[1]);
    }

    private double accuracy(int difficulty) {
        switch (difficulty) {
            case 2: return 0.6;   // 简单：六成走最优
            case 6: return 1.0;   // 困难：完美
            default: return 0.85; // 中等
        }
    }

    /**
     * Bouton 定理的核心：把某堆改成 piles[i] ^ nimSum，即可让异或和归零。
     * 要求改后的值必须比原值小（即真的「拿走」）。
     *
     * @return {pileIndex, takeCount}；异或和已为 0（必败态）时返回 null
     */
    static int[] optimalMove(int[] piles) {
        int xor = 0;
        for (int p : piles) xor ^= p;
        if (xor == 0) return null; // 必败态，没有「把异或和归零」的走法

        for (int i = 0; i < piles.length; i++) {
            int target = piles[i] ^ xor;
            if (target < piles[i]) {
                int take = piles[i] - target;
                if (take > 0) return new int[]{i, take};
            }
        }
        return null;
    }

    /** 随机合法走法：随便挑一堆，拿走 1..该堆数量 */
    private int[] randomMove(int[] piles) {
        java.util.List<Integer> nonEmpty = new java.util.ArrayList<>();
        for (int i = 0; i < piles.length; i++) {
            if (piles[i] > 0) nonEmpty.add(i);
        }
        if (nonEmpty.isEmpty()) return null;
        int pile = nonEmpty.get(ThreadLocalRandom.current().nextInt(nonEmpty.size()));
        int take = ThreadLocalRandom.current().nextInt(piles[pile]) + 1;
        return new int[]{pile, take};
    }
}
