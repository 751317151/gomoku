package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 斗地主牌型规则：纯函数、无随机、可独立单测。
 *
 * 牌值编码：3..10 / J=11 / Q=12 / K=13 / A=14 / 2=15 / 小王=16 / 大王=17。
 * 一手牌由牌值列表表达；比较只看牌型与主牌值，不看花色。
 */
public final class DoudizhuRules {

    public enum Kind {
        SINGLE, PAIR, TRIPLE,
        TRIPLE_WITH_SINGLE, TRIPLE_WITH_PAIR,
        STRAIGHT, STRAIGHT_PAIR, PLANE, PLANE_WITH_SINGLE, PLANE_WITH_PAIR,
        FOUR_WITH_TWO_SINGLES, FOUR_WITH_TWO_PAIRS,
        BOMB, ROCKET
    }

    /** 解析后的牌型；值相同时再比长度（顺子/连对/飞机）。 */
    public static final class Combo {
        public final Kind kind;
        public final int value;
        public final int size;

        Combo(Kind kind, int value, int size) {
            this.kind = kind;
            this.value = value;
            this.size = size;
        }

        @Override
        public String toString() {
            return kind + "(" + value + "," + size + ")";
        }
    }

    private DoudizhuRules() {}

    /**
     * 识别一手牌；不是合法牌型返回 null。
     */
    public static Combo parse(List<Integer> cards) {
        if (cards == null || cards.isEmpty()) return null;
        List<Integer> sorted = new ArrayList<>(cards);
        Collections.sort(sorted);
        int n = sorted.size();
        int[] counts = new int[18];
        for (int v : sorted) {
            if (v < 3 || v > 17) return null;
            counts[v]++;
        }
        int distinct = 0;
        for (int c : counts) if (c > 0) distinct++;

        if (n == 2 && counts[16] == 1 && counts[17] == 1) return new Combo(Kind.ROCKET, 17, 2);
        if (n == 4 && distinct == 1) return new Combo(Kind.BOMB, firstValue(counts), 4);
        if (n == 1) return new Combo(Kind.SINGLE, sorted.get(0), 1);
        if (n == 2 && distinct == 1) return new Combo(Kind.PAIR, sorted.get(0), 2);
        if (n == 3 && distinct == 1) return new Combo(Kind.TRIPLE, sorted.get(0), 3);
        if (n == 4 && distinct == 2) {
            int triple = valueWithCount(counts, 3);
            if (triple > 0) return new Combo(Kind.TRIPLE_WITH_SINGLE, triple, 4);
        }
        if (n == 5 && distinct == 2) {
            int triple = valueWithCount(counts, 3);
            if (triple > 0 && valueWithCount(counts, 2) > 0) {
                return new Combo(Kind.TRIPLE_WITH_PAIR, triple, 5);
            }
        }
        Combo straight = parseStraight(counts, n);
        if (straight != null) return straight;
        Combo straightPair = parseStraightPair(counts, n);
        if (straightPair != null) return straightPair;
        Combo plane = parsePlane(counts, n);
        if (plane != null) return plane;
        Combo fourWithTwo = parseFourWithTwo(counts, n);
        if (fourWithTwo != null) return fourWithTwo;
        return null;
    }

    private static Combo parseStraight(int[] counts, int n) {
        if (n < 5) return null;
        int start = -1, end = -1;
        for (int v = 3; v <= 14; v++) {
            if (counts[v] == 1) {
                if (start < 0) start = v;
                end = v;
            }
        }
        if (start > 0 && end - start + 1 == n && allSinglesInRange(counts, start, end)) {
            return new Combo(Kind.STRAIGHT, end, n);
        }
        return null;
    }

    private static Combo parseStraightPair(int[] counts, int n) {
        if (n < 6 || n % 2 != 0) return null;
        int pairs = 0, singles = 0, start = -1, end = -1;
        for (int v = 3; v <= 14; v++) {
            if (counts[v] == 2) {
                if (start < 0) start = v;
                end = v;
                pairs++;
            } else if (counts[v] == 1) singles++;
            else if (counts[v] > 2) return null;
        }
        for (int v = 15; v <= 17; v++) if (counts[v] != 0) return null;
        if (singles > 0) {
            if (n != pairs * 2 + singles) return null;
        }
        if (pairs >= 3 && end - start + 1 == pairs) return new Combo(Kind.STRAIGHT_PAIR, end, n);
        return null;
    }

    /**
     * 飞机：连续三张，可裸飞机，也可各带一张单牌或一对。
     * 三张段必须是连续的 3..A（2 和大小王不能组成飞机）。
     */
    private static Combo parsePlane(int[] counts, int n) {
        List<Integer> triples = new ArrayList<>();
        for (int v = 3; v <= 14; v++) if (counts[v] == 3) triples.add(v);
        if (triples.isEmpty()) return null;
        // 取最长连续三张段
        int bestStart = -1, bestLen = 0;
        int i = 0;
        while (i < triples.size()) {
            int j = i;
            while (j + 1 < triples.size() && triples.get(j + 1) == triples.get(j) + 1) j++;
            int len = j - i + 1;
            if (len > bestLen) { bestLen = len; bestStart = triples.get(i); }
            i = j + 1;
        }
        int baseCards = bestLen * 3;
        if (n == baseCards) return new Combo(Kind.PLANE, bestStart + bestLen - 1, n);
        if (n == baseCards + bestLen) {
            // 每个三张各带一张单牌；剩余牌不能是三张，且不能出现四张（炸弹不能拆作带牌）
            if (validPlaneWings(counts, bestStart, bestLen, false)) {
                return new Combo(Kind.PLANE_WITH_SINGLE, bestStart + bestLen - 1, n);
            }
        }
        if (n == baseCards + bestLen * 2) {
            if (validPlaneWings(counts, bestStart, bestLen, true)) {
                return new Combo(Kind.PLANE_WITH_PAIR, bestStart + bestLen - 1, n);
            }
        }
        return null;
    }

    private static boolean validPlaneWings(int[] counts, int start, int len, boolean pairs) {
        int wingCards = 0, wingGroups = 0;
        for (int v = 3; v <= 17; v++) {
            int c = counts[v];
            if (v >= start && v < start + len) c -= 3;
            if (c < 0) return false;
            if (c == 0) continue;
            if (pairs) {
                if (c != 2) return false;
                wingGroups++;
            } else {
                if (c > 2) return false;
                wingCards += c;
                wingGroups += c;
            }
        }
        return pairs ? wingGroups == len : wingCards == len;
    }

    private static Combo parseFourWithTwo(int[] counts, int n) {
        int four = valueWithCount(counts, 4);
        if (four <= 0) return null;
        if (n == 6) {
            // 四带两张单牌（可为一对，但不能是另一个四张）
            int remaining = 0;
            for (int v = 3; v <= 17; v++) {
                if (v == four) continue;
                remaining += counts[v];
                if (counts[v] > 2) return null;
            }
            if (remaining == 2) return new Combo(Kind.FOUR_WITH_TWO_SINGLES, four, 6);
        }
        if (n == 8) {
            int pairs = 0;
            for (int v = 3; v <= 17; v++) {
                if (v == four) continue;
                if (counts[v] == 2) pairs++;
                else if (counts[v] != 0) return null;
            }
            if (pairs == 2) return new Combo(Kind.FOUR_WITH_TWO_PAIRS, four, 8);
        }
        return null;
    }

    private static boolean allSinglesInRange(int[] counts, int start, int end) {
        for (int v = 3; v <= 17; v++) {
            if (v >= start && v <= end) {
                if (counts[v] != 1) return false;
            } else if (counts[v] != 0) return false;
        }
        return true;
    }

    private static int firstValue(int[] counts) {
        for (int v = 3; v <= 17; v++) if (counts[v] > 0) return v;
        return -1;
    }

    private static int valueWithCount(int[] counts, int wanted) {
        for (int v = 3; v <= 17; v++) if (counts[v] == wanted) return v;
        return -1;
    }

    /** 能否压过场上牌；场上是 null 表示自由出牌。 */
    public static boolean beats(Combo candidate, Combo table) {
        if (candidate == null) return false;
        if (table == null) return true;
        if (candidate.kind == Kind.ROCKET) return table.kind != Kind.ROCKET;
        if (table.kind == Kind.ROCKET) return false;
        if (candidate.kind == Kind.BOMB) {
            return table.kind != Kind.BOMB || candidate.value > table.value;
        }
        if (table.kind == Kind.BOMB) return false;
        return candidate.kind == table.kind
                && candidate.size == table.size
                && candidate.value > table.value;
    }

    /** 人类可读牌名，用于日志与复盘。 */
    public static String label(Combo combo) {
        if (combo == null) return "无牌型";
        switch (combo.kind) {
            case ROCKET: return "火箭";
            case BOMB: return "炸弹";
            case SINGLE: return "单张";
            case PAIR: return "对子";
            case TRIPLE: return "三张";
            case TRIPLE_WITH_SINGLE: return "三带一";
            case TRIPLE_WITH_PAIR: return "三带二";
            case STRAIGHT: return "顺子";
            case STRAIGHT_PAIR: return "连对";
            case PLANE: return "飞机";
            case PLANE_WITH_SINGLE: return "飞机带单";
            case PLANE_WITH_PAIR: return "飞机带对";
            case FOUR_WITH_TWO_SINGLES: return "四带二";
            case FOUR_WITH_TWO_PAIRS: return "四带两对";
            default: return "牌型";
        }
    }
}
