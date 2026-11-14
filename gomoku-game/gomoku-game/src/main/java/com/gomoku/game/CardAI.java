package com.gomoku.game;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 卡牌 AI — 规则式决策
 *
 * 行动空间是「打出手牌第几张」，看起来适合搜索，但每回合能打出的组合是
 * 手牌大小的排列（7!），且存在隐藏信息（对手手牌未知），期望值搜索的收益
 * 远不如几条明确的优先级规则。所以沿用 RpgAI 的路线：把「好牌先打」写清楚。
 *
 * 优先级：
 * 1. 能斩杀（火球直伤 ≥ 对手生命）→ 立刻打出
 * 2. 己方血量危险 → 治疗
 * 3. 对手场上随从多 → 横扫
 * 4. 高费强力随从优先站场
 * 5. 有随从在场 → 鼓舞强化
 * 6. 兜底：任意可打的牌
 */
public class CardAI implements GameAI {

    static final CardAI INSTANCE = new CardAI();

    private CardAI() {}

    @Override
    public int chooseAction(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof CardBattleEngine)) return -1;
        CardBattleEngine card = (CardBattleEngine) engine;

        List<CardBattleEngine.Card> hand = card.getHand(side);
        int mana = card.getMana(side);
        int myHp = card.getHp(side);
        int foeHp = card.getHp(3 - side);
        int foeMinions = card.getBoardCount(3 - side);
        int myMinions = card.getBoardCount(side);

        // 简单难度：一半概率随便打一张，避免新手被一直压制
        if (difficulty <= 2 && ThreadLocalRandom.current().nextBoolean()) {
            int any = pickCheapestPlayable(hand, mana);
            return any >= 0 ? any : pickAnyPlayable(hand, mana);
        }

        // 1. 斩杀线
        for (int i = 0; i < hand.size(); i++) {
            CardBattleEngine.Card c = hand.get(i);
            if (c.getCost() <= mana && c == CardBattleEngine.Card.FIREBALL && foeHp <= 4) {
                return i;
            }
        }

        // 2. 血量告急优先治疗
        if (myHp <= 12) {
            for (int i = 0; i < hand.size(); i++) {
                CardBattleEngine.Card c = hand.get(i);
                if (c.getCost() <= mana && c == CardBattleEngine.Card.HEAL) return i;
            }
        }

        // 3. 对手铺场：横扫
        if (foeMinions >= 2) {
            for (int i = 0; i < hand.size(); i++) {
                CardBattleEngine.Card c = hand.get(i);
                if (c.getCost() <= mana && c == CardBattleEngine.Card.CLEAVE) return i;
            }
        }

        // 4. 强力随从站场（攻+血最高）
        int best = -1;
        int bestPower = -1;
        for (int i = 0; i < hand.size(); i++) {
            CardBattleEngine.Card c = hand.get(i);
            if (c.getCost() > mana || !c.isMinion()) continue;
            if (myMinions >= CardBattleEngine.getMaxBoard()) continue;
            int power = c.getAtk() + c.getHp();
            if (power > bestPower) {
                bestPower = power;
                best = i;
            }
        }
        if (best >= 0) return best;

        // 5. 鼓舞强化
        if (myMinions > 0) {
            for (int i = 0; i < hand.size(); i++) {
                CardBattleEngine.Card c = hand.get(i);
                if (c.getCost() <= mana && c == CardBattleEngine.Card.BUFF) return i;
            }
        }

        // 6. 兜底
        return pickAnyPlayable(hand, mana);
    }

    /**
     * 是否结束回合。
     *
     * 卡牌是一回合多行动，AI 需要自己判断「还能不能继续」：
     * 1. 还有能攻击的随从 → 先攻击（收益高且不花法力）
     * 2. 还有买得起的牌 → 继续出牌
     * 3. 都不行 → 收尾
     *
     * 注意返回 false 时房间会再调一次 decide()，所以攻击与出牌都从 decide 走。
     */
    @Override
    public boolean shouldEndTurn(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof CardBattleEngine)) return true;
        CardBattleEngine card = (CardBattleEngine) engine;
        if (card.getCurrentTurn() != side) return false;

        // 还有随从能攻击 → 不结束，让 decide() 去打
        if (!card.attackableIndexes(side).isEmpty()) return false;

        int mana = card.getMana(side);
        for (CardBattleEngine.Card c : card.getHand(side)) {
            if (c.getCost() <= mana) {
                // 还有买得起的牌，且场上没满（随从牌需要位置）——继续出
                if (!c.isMinion() || card.getBoardCount(side) < CardBattleEngine.getMaxBoard()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 攻击指令：选一个能攻击的随从，攻击嘲讽随从（若有），否则打英雄。
     *
     * 返回 null 表示「本步不是攻击」。
     * 这个能力通过覆写 decide 暴露 —— 因为攻击不是「打出手牌第几张」，
     * 而 {@link GameAI#chooseAction} 只能表达行动序号。
     */
    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof CardBattleEngine)) return null;
        CardBattleEngine card = (CardBattleEngine) engine;
        if (card.getCurrentTurn() != side || card.isOver()) return null;

        // 优先级 1：还有随从能攻击，先打
        java.util.List<Integer> usable = card.attackableIndexes(side);
        if (!usable.isEmpty()) {
            int minionIdx = usable.get(0);
            java.util.List<Integer> taunts = card.tauntIndexes(3 - side);
            int target = taunts.isEmpty() ? -1 : taunts.get(0);
            return MoveCommand.attack(minionIdx, target);
        }

        // 优先级 2：出牌（需要选目标的法术要顺带算好目标）
        int action = chooseAction(engine, side, difficulty);
        if (action < 0) return null;

        CardBattleEngine.Card chosen = card.getHand(side).get(action);
        if (chosen == CardBattleEngine.Card.FIREBALL) {
            return MoveCommand.withTarget(action, 3 - side, bestFireballTarget(card, side));
        }
        if (chosen == CardBattleEngine.Card.BUFF) {
            return MoveCommand.withTarget(action, side, bestBuffTarget(card, side));
        }
        return MoveCommand.action(action);
    }

    /**
     * 火球目标：优先打「能被 4 点伤害正好打死」的敌方随从（赚取卡差），
     * 其次打最大的随从，最后才打脸。
     *
     * 这个优先级来自一个简单的价值判断：火球打脸只有 4 点伤害，
     * 而打死一个 3/2 或 2/4 的随从相当于同时清场 + 省下后续交换，
     * 通常比 4 点脸伤更有价值。
     */
    private int bestFireballTarget(CardBattleEngine card, int side) {
        java.util.List<int[]> foeBoard = card.getBoardForAi(3 - side);
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < foeBoard.size(); i++) {
            int[] m = foeBoard.get(i); // {atk, hp}
            int score;
            if (m[1] <= 4) {
                score = 100 + m[0] * 10 - m[1];   // 能打死：价值最高
            } else {
                score = m[0] * 10 - m[1];          // 打不死：只造成伤害
            }
            if (score > bestScore) { bestScore = score; best = i; }
        }
        // 没有随从（或都不值得）→ 打脸
        return best;
    }

    /** 鼓舞目标：强化攻击力最高的己方随从（更容易打出价值） */
    private int bestBuffTarget(CardBattleEngine card, int side) {
        java.util.List<int[]> myBoard = card.getBoardForAi(side);
        if (myBoard.isEmpty()) return -1;
        int best = 0;
        for (int i = 1; i < myBoard.size(); i++) {
            if (myBoard.get(i)[0] > myBoard.get(best)[0]) best = i;
        }
        return best;
    }

    private int pickCheapestPlayable(List<CardBattleEngine.Card> hand, int mana) {
        int best = -1;
        int bestCost = Integer.MAX_VALUE;
        for (int i = 0; i < hand.size(); i++) {
            CardBattleEngine.Card c = hand.get(i);
            if (c.getCost() <= mana && c.getCost() < bestCost) {
                bestCost = c.getCost();
                best = i;
            }
        }
        return best;
    }

    private int pickAnyPlayable(List<CardBattleEngine.Card> hand, int mana) {
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).getCost() <= mana) return i;
        }
        return -1;
    }
}
