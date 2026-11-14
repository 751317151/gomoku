package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 回合制 RPG 对战 AI — 规则式决策，不做搜索
 *
 * 为什么不用搜索：RPG 对战的行动空间很小（6 个行动）、状态连续（血量是整数），
 * 而且存在随机浮动，Minimax 的收益远不如几条清晰的战术规则。
 *
 * 决策优先级（越靠前越优先）：
 * 1. 快死了先回血（治疗术 → 药水）
 * 2. 能一击斩杀就斩杀（避免给对方回血机会）
 * 3. 高伤技能优先（火球 > 强力一击）
 * 4. 没蓝又掉血较多时防御回蓝
 * 5. 兜底普攻
 */
public class RpgAI implements GameAI {

    static final RpgAI INSTANCE = new RpgAI();

    /** 低于该血量考虑治疗 */
    private static final int HEAL_THRESHOLD = 45;
    /** 低于该血量优先喝药水（药水不耗蓝，留给没蓝的局面） */
    private static final int POTION_THRESHOLD = 35;
    /** 斩杀线：按各技能的下限伤害估算 */
    private static final int FINISH_HEAVY = 22;
    private static final int FINISH_FIREBALL = 20;

    private RpgAI() {}

    @Override
    public int chooseAction(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof RpgBattleEngine)) return -1;
        RpgBattleEngine rpg = (RpgBattleEngine) engine;

        int myHp = rpg.getHp(side);
        int myMp = rpg.getMp(side);
        int foeSide = 3 - side;
        int foeHp = rpg.getHp(foeSide);

        // 简单难度：有一半概率直接普攻，让新手局不至于被压着打
        if (difficulty <= 2 && ThreadLocalRandom.current().nextBoolean()) {
            return RpgBattleEngine.Action.ATTACK.getIndex();
        }

        // 1. 回血：蓝够就治疗，没蓝就喝药水
        if (myHp <= HEAL_THRESHOLD && rpg.canUse(side, RpgBattleEngine.Action.HEAL)) {
            return RpgBattleEngine.Action.HEAL.getIndex();
        }
        if (myHp <= POTION_THRESHOLD && rpg.canUse(side, RpgBattleEngine.Action.POTION)) {
            return RpgBattleEngine.Action.POTION.getIndex();
        }

        // 2. 斩杀：对方残血时优先用伤害最高的可用技能
        if (foeHp <= FINISH_HEAVY && rpg.canUse(side, RpgBattleEngine.Action.HEAVY)) {
            return RpgBattleEngine.Action.HEAVY.getIndex();
        }
        if (foeHp <= FINISH_FIREBALL && rpg.canUse(side, RpgBattleEngine.Action.FIREBALL)) {
            return RpgBattleEngine.Action.FIREBALL.getIndex();
        }

        // 3. 常规输出
        if (rpg.canUse(side, RpgBattleEngine.Action.FIREBALL)) {
            return RpgBattleEngine.Action.FIREBALL.getIndex();
        }
        if (rpg.canUse(side, RpgBattleEngine.Action.HEAVY)) {
            return RpgBattleEngine.Action.HEAVY.getIndex();
        }

        // 4. 没蓝了：防御回蓝（防御本身回 6 点，加上每回合自动回复，一两轮就能再出招）
        if (myMp < RpgBattleEngine.Action.HEAVY.getMpCost()) {
            return RpgBattleEngine.Action.DEFEND.getIndex();
        }

        return RpgBattleEngine.Action.ATTACK.getIndex();
    }
}
