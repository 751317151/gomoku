package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoudizhuRulesTest {

    @Test
    void recognizesBasicCombos() {
        assertKind(DoudizhuRules.Kind.SINGLE, 3, 1, 3);
        assertKind(DoudizhuRules.Kind.PAIR, 7, 2, 7, 7);
        assertKind(DoudizhuRules.Kind.TRIPLE, 9, 3, 9, 9, 9);
        assertKind(DoudizhuRules.Kind.TRIPLE_WITH_SINGLE, 9, 4, 9, 9, 9, 3);
        assertKind(DoudizhuRules.Kind.TRIPLE_WITH_PAIR, 9, 5, 9, 9, 9, 4, 4);
    }

    @Test
    void recognizesStraightsWithoutTwoOrJokers() {
        assertKind(DoudizhuRules.Kind.STRAIGHT, 7, 5, 3, 4, 5, 6, 7);
        assertKind(DoudizhuRules.Kind.STRAIGHT, 14, 12,
                3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        assertNull(DoudizhuRules.parse(List.of(11, 12, 13, 14, 15)), "2 不能进顺子");
        assertNull(DoudizhuRules.parse(List.of(3, 4, 5, 6)), "顺子至少 5 张");
    }

    @Test
    void recognizesConsecutivePairs() {
        assertKind(DoudizhuRules.Kind.STRAIGHT_PAIR, 5, 6, 3, 3, 4, 4, 5, 5);
        assertKind(DoudizhuRules.Kind.STRAIGHT_PAIR, 6, 8,
                3, 3, 4, 4, 5, 5, 6, 6);
        assertNull(DoudizhuRules.parse(List.of(3, 3, 4, 4)), "连对至少 3 对");
        assertNull(DoudizhuRules.parse(List.of(3, 3, 4, 4, 15, 15)), "2 不能进连对");
    }

    @Test
    void recognizesPlanesAndWings() {
        assertKind(DoudizhuRules.Kind.PLANE, 4, 6, 3, 3, 3, 4, 4, 4);
        assertKind(DoudizhuRules.Kind.PLANE_WITH_SINGLE, 4, 8,
                3, 3, 3, 4, 4, 4, 5, 6);
        assertKind(DoudizhuRules.Kind.PLANE_WITH_PAIR, 4, 10,
                3, 3, 3, 4, 4, 4, 5, 5, 6, 6);
        assertNull(DoudizhuRules.parse(List.of(3, 3, 3, 5, 5, 5)), "三张必须连续");
    }

    @Test
    void recognizesFourWithTwoAndBomb() {
        assertKind(DoudizhuRules.Kind.FOUR_WITH_TWO_SINGLES, 8, 6,
                8, 8, 8, 8, 3, 4);
        assertKind(DoudizhuRules.Kind.FOUR_WITH_TWO_PAIRS, 8, 8,
                8, 8, 8, 8, 3, 3, 4, 4);
        assertKind(DoudizhuRules.Kind.BOMB, 10, 4, 10, 10, 10, 10);
        assertKind(DoudizhuRules.Kind.ROCKET, 17, 2, 16, 17);
    }

    @Test
    void comparesOnlySameKindAndLength() {
        DoudizhuRules.Combo five = DoudizhuRules.parse(List.of(3, 4, 5, 6, 7));
        DoudizhuRules.Combo six = DoudizhuRules.parse(List.of(4, 5, 6, 7, 8));
        DoudizhuRules.Combo longStraight = DoudizhuRules.parse(List.of(3, 4, 5, 6, 7, 8));
        assertTrue(DoudizhuRules.beats(six, five));
        assertFalse(DoudizhuRules.beats(five, six));
        assertFalse(DoudizhuRules.beats(longStraight, five), "长度不同不能压");
    }

    @Test
    void bombBeatsAnyNormalComboButNotHigherBomb() {
        DoudizhuRules.Combo straight = DoudizhuRules.parse(List.of(3, 4, 5, 6, 7));
        DoudizhuRules.Combo bomb3 = DoudizhuRules.parse(List.of(3, 3, 3, 3));
        DoudizhuRules.Combo bomb4 = DoudizhuRules.parse(List.of(4, 4, 4, 4));
        DoudizhuRules.Combo rocket = DoudizhuRules.parse(List.of(16, 17));
        assertTrue(DoudizhuRules.beats(bomb3, straight));
        assertFalse(DoudizhuRules.beats(straight, bomb3));
        assertTrue(DoudizhuRules.beats(bomb4, bomb3));
        assertTrue(DoudizhuRules.beats(rocket, bomb4));
        assertFalse(DoudizhuRules.beats(bomb4, rocket));
    }

    private static void assertKind(DoudizhuRules.Kind kind, int value, int size, Integer... cards) {
        DoudizhuRules.Combo combo = DoudizhuRules.parse(List.of(cards));
        assertEquals(kind, combo.kind, "牌型");
        assertEquals(value, combo.value, "主牌值");
        assertEquals(size, combo.size, "张数");
    }
}
