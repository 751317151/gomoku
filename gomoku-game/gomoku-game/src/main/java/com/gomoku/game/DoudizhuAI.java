package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 斗地主 AI：只读自己的手牌、底牌与公开桌面牌。
 *
 * 策略保持可解释：
 * - 叫地主按大牌数量与炸弹估分；
 * - 出牌优先一手走完，其次从最小牌开始拆，尽量不为压单张拆炸弹；
 * - 农民不压队友（除非队友只剩 1 张且自己必须接）。
 */
public final class DoudizhuAI implements GameAI {
    static final DoudizhuAI INSTANCE = new DoudizhuAI();
    private DoudizhuAI() {}

    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof DoudizhuEngine)) return null;
        DoudizhuEngine game = (DoudizhuEngine) engine;
        if (!game.isAwaitingAction(side)) return null;
        if (game.isBidding()) return MoveCommand.action(bestBid(game, side));

        List<Integer> hand = game.getHand(side);
        if (hand.isEmpty()) return MoveCommand.action(DoudizhuEngine.PASS_ACTION);
        DoudizhuRules.Combo table = game.getLastCombo();
        // 自己重新领出时，桌面牌型已清空（引擎保证）。
        if (table != null && game.getLastPlaySide() == side) table = null;

        // 1. 能一手走完就直接走完。
        List<Integer> all = allIndices(hand.size());
        if (game.canPlay(side, all)) return playCommand(all);

        // 2. 找能压过桌面的最小合法组合；同一轮里农民尽量不压农民队友。
        boolean teammate = table != null && !isLandlord(game, side)
                && !isLandlord(game, game.getLastPlaySide());
        List<List<Integer>> candidates = new ArrayList<>();
        candidates.addAll(singles(hand));
        if (table == null || table.kind == DoudizhuRules.Kind.PAIR
                || table.kind == DoudizhuRules.Kind.STRAIGHT_PAIR
                || table.kind == DoudizhuRules.Kind.TRIPLE_WITH_PAIR
                || table.kind == DoudizhuRules.Kind.PLANE_WITH_PAIR
                || table.kind == DoudizhuRules.Kind.FOUR_WITH_TWO_PAIRS) {
            candidates.addAll(pairs(hand));
        }
        candidates.addAll(triples(hand));
        candidates.addAll(straights(hand, 5));
        candidates.addAll(straightPairs(hand));
        candidates.addAll(bombs(hand));
        List<List<Integer>> rockets = rocket(hand);
        candidates.addAll(rockets);

        // 三带一/三带二：从三张里挑最小的一套，带最小单/对，避免只压三张时漏选。
        for (List<Integer> triple : triples(hand)) {
            List<Integer> withSingle = withSingleKicker(hand, triple);
            if (withSingle != null) candidates.add(withSingle);
            List<Integer> withPair = withPairKicker(hand, triple);
            if (withPair != null) candidates.add(withPair);
        }

        List<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (List<Integer> candidate : candidates) {
            if (!game.canPlay(side, candidate)) continue;
            DoudizhuRules.Combo combo = comboOf(hand, candidate);
            if (combo == null) continue;
            if (table != null && isBomb(combo) && table.kind != DoudizhuRules.Kind.BOMB
                    && table.kind != DoudizhuRules.Kind.ROCKET && difficulty < 4) {
                continue; // 低难度不轻易炸
            }
            int score = combo.value;
            if (isBomb(combo)) score += 100;
            if (teammate && difficulty >= 4) score += 60;
            // 优先少拆牌：留下的散牌越少越好，这里用出牌张数做近似。
            score -= candidate.size() * 2;
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        if (best == null) return MoveCommand.action(DoudizhuEngine.PASS_ACTION);
        return playCommand(best);
    }

    /**
     * 房间在决定命令后如何执行：统一用 PLAY_MARKER；
     * 下标通过线程内的 {@link #pendingIndices()} 交给房间，不污染通用 MoveCommand。
     */
    public static MoveCommand playCommand(List<Integer> indices) {
        LAST_CHOSEN.set(new ArrayList<>(indices));
        return MoveCommand.action(DoudizhuEngine.PLAY_MARKER);
    }

    private static final ThreadLocal<List<Integer>> LAST_CHOSEN = new ThreadLocal<>();
    /** 取走 AI 最近一次决定的下标；房间执行后必须调用，避免线程复用时串局。 */
    public static List<Integer> pendingIndices() {
        List<Integer> value = LAST_CHOSEN.get();
        LAST_CHOSEN.remove();
        return value == null ? Collections.emptyList() : value;
    }

    private static int bestBid(DoudizhuEngine game, int side) {
        List<Integer> hand = game.getHand(side);
        int score = 0;
        for (int card : hand) {
            if (card == 17) score += 8;
            else if (card == 16) score += 6;
            else if (card == 15) score += 4;
            else if (card == 14) score += 2;
            else if (card == 13) score += 1;
        }
        for (int value = 3; value <= 15; value++) {
            int count = 0;
            for (int card : hand) if (card == value) count++;
            if (count == 4) score += 10;
        }
        List<Integer> allowed = game.availableBids();
        int want = score >= 18 ? 3 : score >= 10 ? 2 : score >= 5 ? 1 : 0;
        for (int i = allowed.size() - 1; i >= 0; i--) if (allowed.get(i) <= want) return allowed.get(i);
        return DoudizhuEngine.NO_BID_ACTION;
    }

    private static boolean isLandlord(DoudizhuEngine game, int side) {
        return game.getLandlord() == side;
    }

    private static boolean isBomb(DoudizhuRules.Combo combo) {
        return combo.kind == DoudizhuRules.Kind.BOMB || combo.kind == DoudizhuRules.Kind.ROCKET;
    }

    private static DoudizhuRules.Combo comboOf(List<Integer> hand, List<Integer> indices) {
        List<Integer> cards = new ArrayList<>();
        for (int i : indices) cards.add(hand.get(i));
        return DoudizhuRules.parse(cards);
    }

    private static List<Integer> allIndices(int size) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < size; i++) list.add(i);
        return list;
    }

    private static List<List<Integer>> singles(List<Integer> hand) {
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) result.add(List.of(i));
        return result;
    }

    private static List<List<Integer>> pairs(List<Integer> hand) {
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) {
            for (int j = i + 1; j < hand.size(); j++) {
                if (hand.get(i).equals(hand.get(j))) result.add(List.of(i, j));
            }
        }
        return result;
    }

    private static List<List<Integer>> triples(List<Integer> hand) {
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) {
            for (int j = i + 1; j < hand.size(); j++) {
                for (int k = j + 1; k < hand.size(); k++) {
                    if (hand.get(i).equals(hand.get(j)) && hand.get(j).equals(hand.get(k))) {
                        result.add(List.of(i, j, k));
                    }
                }
            }
        }
        return result;
    }

    private static List<List<Integer>> bombs(List<Integer> hand) {
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) {
            for (int j = i + 1; j < hand.size(); j++) {
                for (int k = j + 1; k < hand.size(); k++) {
                    for (int l = k + 1; l < hand.size(); l++) {
                        int a = hand.get(i);
                        if (a == hand.get(j) && a == hand.get(k) && a == hand.get(l)) {
                            result.add(List.of(i, j, k, l));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<List<Integer>> rocket(List<Integer> hand) {
        int small = -1, big = -1;
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i) == 16) small = i;
            if (hand.get(i) == 17) big = i;
        }
        return small >= 0 && big >= 0 ? List.of(List.of(small, big)) : Collections.emptyList();
    }

    private static List<Integer> withSingleKicker(List<Integer> hand, List<Integer> triple) {
        for (int i = 0; i < hand.size(); i++) {
            if (triple.contains(i) || hand.get(i) >= 15) continue;
            List<Integer> result = new ArrayList<>(triple);
            result.add(i);
            return result;
        }
        return null;
    }

    private static List<Integer> withPairKicker(List<Integer> hand, List<Integer> triple) {
        for (int i = 0; i < hand.size(); i++) {
            if (triple.contains(i)) continue;
            for (int j = i + 1; j < hand.size(); j++) {
                if (triple.contains(j)) continue;
                if (hand.get(i).equals(hand.get(j)) && hand.get(i) < 15) {
                    List<Integer> result = new ArrayList<>(triple);
                    result.add(i);
                    result.add(j);
                    return result;
                }
            }
        }
        return null;
    }

    private static List<List<Integer>> straights(List<Integer> hand, int min) {
        List<List<Integer>> result = new ArrayList<>();
        for (int start = 3; start <= 14 - min + 1; start++) {
            List<Integer> picked = new ArrayList<>();
            boolean ok = true;
            for (int value = start; value < start + min; value++) {
                int index = firstIndex(hand, value, picked);
                if (index < 0) { ok = false; break; }
                picked.add(index);
            }
            if (ok) result.add(picked);
        }
        return result;
    }

    private static List<List<Integer>> straightPairs(List<Integer> hand) {
        List<List<Integer>> result = new ArrayList<>();
        for (int start = 3; start <= 12; start++) {
            List<Integer> picked = new ArrayList<>();
            boolean ok = true;
            for (int value = start; value < start + 3; value++) {
                int a = firstIndex(hand, value, picked);
                int b = a < 0 ? -1 : firstIndex(hand, value, append(picked, a));
                if (a < 0 || b < 0) { ok = false; break; }
                picked.add(a);
                picked.add(b);
            }
            if (ok) result.add(picked);
        }
        return result;
    }

    private static List<Integer> append(List<Integer> source, int value) {
        List<Integer> copy = new ArrayList<>(source);
        copy.add(value);
        return copy;
    }

    private static int firstIndex(List<Integer> hand, int value, List<Integer> used) {
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i) == value && !used.contains(i)) return i;
        }
        return -1;
    }
}
