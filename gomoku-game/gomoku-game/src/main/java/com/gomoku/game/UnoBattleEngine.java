package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** UNO 风格多人卡牌：按颜色/数字出牌，并包含跳过、反转、+2、万能牌。 */
public class UnoBattleEngine implements GameEngine {
    public static final int DRAW_ACTION = 100;
    public static final int CALL_UNO_ACTION = 101;
    public static final int END_DRAW_ACTION = 102;
    public static final int CATCH_UNO_ACTION = 103;
    private static final int HAND_SIZE = 7, MAX_MOVES = 300, COLORS = 4;
    private int count = 2, turn = 1, seq, direction = 1, activeColor, pendingDraw, unoPendingSide, unoPendingSteps, drawnPendingSide, drawnCardIndex;
    private boolean over;
    private Integer winner;
    private boolean[] active = new boolean[9];
    private Card top;
    private final List<Card> deck = new ArrayList<>();
    private final List<Card> discard = new ArrayList<>();
    private List<List<Card>> hands = new ArrayList<>();
    private int[] roundScores = new int[3];
    private String[] names = new String[3];
    private final List<int[]> history = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final Random random = new Random();

    public static final class Card {
        public final int color; // 0红 1黄 2绿 3蓝，-1万能
        public final int value; // 0-9 数字，10跳过 11反转 12+2 13万能，14万能+4
        Card(int color, int value) { this.color = color; this.value = value; }
        String label() {
            if (value == 10) return "跳过";
            if (value == 11) return "反转";
            if (value == 12) return "+2";
            if (value == 13) return "万能";
            if (value == 14) return "万能 +4";
            return new String[]{"红","黄","绿","蓝"}[color] + value;
        }
        boolean special() { return value >= 10; }
        boolean wildDrawFour() { return value == 14; }
    }

    public UnoBattleEngine() { reset(); }
    public GameType type() { return GameType.UNO; }
    public boolean autoEndsTurn() { return true; }
    public void configurePlayers(int count) { this.count = type().resolvePlayerCount(count); }

    public void reset() {
        turn = 1; seq = 0; direction = 1; activeColor = 0; pendingDraw = 0; unoPendingSide = 0; unoPendingSteps = 0; drawnPendingSide = 0; drawnCardIndex = -1; over = false; winner = null;
        active = new boolean[9];
        history.clear(); log.clear(); deck.clear(); discard.clear(); hands = new ArrayList<>(); hands.add(Collections.emptyList());
        roundScores = new int[count + 1];
        names = new String[count + 1];
        for (int s = 1; s <= count; s++) { names[s] = "玩家 " + s; hands.add(new ArrayList<>()); }
        for (int s = 1; s <= count; s++) active[s] = true;
        for (int color = 0; color < COLORS; color++) {
            for (int value = 0; value <= 12; value++) {
                deck.add(new Card(color, value));
                if (value > 0) deck.add(new Card(color, value));
            }
        }
        for (int i = 0; i < 4; i++) { deck.add(new Card(-1, 13)); deck.add(new Card(-1, 14)); }
        Collections.shuffle(deck, random);
        for (int s = 1; s <= count; s++) for (int i = 0; i < HAND_SIZE; i++) hands.get(s).add(draw());
        do { top = draw(); } while (top != null && top.special());
        if (top == null) top = new Card(0, 0);
        activeColor = top.color;
        log.add("UNO 开始：数字牌按颜色或数字出牌，特殊牌改变回合节奏");
        log.add("首张牌：" + top.label());
    }

    private Card draw() {
        recycleDiscardIfNeeded();
        return deck.isEmpty() ? null : deck.remove(deck.size() - 1);
    }
    private void recycleDiscardIfNeeded() {
        if (!deck.isEmpty() || discard.isEmpty()) return;
        deck.addAll(discard);
        discard.clear();
        Collections.shuffle(deck, random);
        log.add("牌库耗尽，洗回弃牌堆继续游戏");
    }
    public void setPlayerName(int side, String name) { if (side > 0 && side <= count && name != null && !name.isEmpty()) names[side] = name; }

    public boolean applyMove(MoveCommand command, int side) {
        if (over || command == null || !isActive(side) || !command.hasAction()) return false;
        List<Card> hand = hands.get(side);
        int action = command.getAction();
        boolean catchingUno = action == CATCH_UNO_ACTION;
        if (!catchingUno && side != turn) return false;
        if (catchingUno) {
            if (unoPendingSide == 0 || side == unoPendingSide) return false;
            int offender = unoPendingSide;
            List<Card> offenderHand = hands.get(offender);
            for (int i = 0; i < 2; i++) { Card c = draw(); if (c != null) offenderHand.add(c); }
            history.add(new int[]{side, CATCH_UNO_ACTION, offender, 2}); seq++;
            log.add(names[side] + " 抓到 " + names[offender] + " 未喊 UNO，罚摸 2 张");
            int steps = unoPendingSteps <= 0 ? 1 : unoPendingSteps;
            unoPendingSide = 0; unoPendingSteps = 0;
            advance(steps);
            return true;
        }
        boolean playingDrawnCard = false;
        if (drawnPendingSide != 0) {
            if (side != drawnPendingSide) return false;
            if (action == END_DRAW_ACTION) {
                drawnPendingSide = 0; drawnCardIndex = -1;
                history.add(new int[]{side, END_DRAW_ACTION, 0}); seq++;
                log.add(names[side] + " 保留摸到的牌，结束摸牌");
                advance(1); return true;
            }
            if (action != drawnCardIndex) return false;
            playingDrawnCard = true;
        }
        if (unoPendingSide != 0) {
            if (side != unoPendingSide || action != CALL_UNO_ACTION) return false;
            log.add(names[side] + " 喊了 UNO");
            history.add(new int[]{side, CALL_UNO_ACTION, 0}); seq++;
            int steps = unoPendingSteps <= 0 ? 1 : unoPendingSteps;
            unoPendingSide = 0; unoPendingSteps = 0;
            advance(steps); return true;
        }
        if (action == DRAW_ACTION) {
            if (!canDraw(side)) return false;
            int requested = pendingDraw > 0 ? pendingDraw : 1, drawn = 0;
            for (int i = 0; i < requested; i++) { Card c = draw(); if (c != null) { hand.add(c); drawn++; } }
            history.add(new int[]{side, DRAW_ACTION, drawn}); seq++;
            log.add(names[side] + " 摸了 " + drawn + " 张牌");
            pendingDraw = 0;
            if (requested == 1 && drawn == 1 && playable(side, hand.get(hand.size() - 1))) {
                drawnPendingSide = side; drawnCardIndex = hand.size() - 1;
                log.add(names[side] + " 摸到可出的牌，可立即打出或结束摸牌");
                return true;
            }
            advance(1); return true;
        }
        if (action < 0 || action >= hand.size()) return false;
        Card c = hand.get(action);
        if (!canPlay(side, action)) return false;
        int chosenColor = activeColor;
        if (c.color < 0) {
            if (!command.hasTarget() || command.getTargetSide() != 1 || command.getTargetIndex() < 0 || command.getTargetIndex() >= COLORS) return false;
            chosenColor = command.getTargetIndex();
        }
        int exchangeTarget = -1;
        if (c.value == 7 && hand.size() > 1) {
            if (!command.hasTarget() || command.getTargetSide() != 2) return false;
            exchangeTarget = command.getTargetIndex();
            if (!isActive(exchangeTarget) || exchangeTarget == side) return false;
        }
        if (playingDrawnCard) { drawnPendingSide = 0; drawnCardIndex = -1; }
        hand.remove(action);
        if (top != null) discard.add(top);
        top = c; activeColor = c.color >= 0 ? c.color : chosenColor;
        // 最后一张先结算胜利；否则 0/7 会把空手牌送给另一席，导致无人正常清空获胜。
        boolean finalCard = hand.isEmpty();
        if (!finalCard && c.value == 7) {
            List<Card> other = hands.get(exchangeTarget);
            hands.set(side, other); hands.set(exchangeTarget, hand);
            hand = hands.get(side);
            log.add(names[side] + " 与 " + names[exchangeTarget] + " 交换了手牌");
        } else if (!finalCard && c.value == 0) {
            rotateHands();
            hand = hands.get(side);
            log.add("所有玩家按方向轮转手牌");
        }
        // 前四列兼容旧记录；新增实际换手目标与最后一张标志，不记录隐藏手牌。
        history.add(new int[]{side, c.color, c.value, activeColor, !finalCard && c.value == 7 ? exchangeTarget : 0, finalCard ? 1 : 0}); seq++;
        log.add(names[side] + " 打出 " + c.label());
        if (hand.isEmpty()) {
            over = true; winner = side;
            roundScores[side] = 0;
            for (int other = 1; other <= count; other++) if (other != side) roundScores[side] += handScore(hands.get(other));
            log.add(names[side] + " 清空手牌，获胜，获得 " + roundScores[side] + " 分");
            return true;
        }
        int steps = 1;
        if (c.value == 10) { log.add("跳过下一位"); steps = 2; }
        else if (c.value == 11) { direction = -direction; log.add("出牌方向反转"); if (activeCount() == 2) steps = 2; }
        else if (c.value == 12) { pendingDraw += 2; steps = 1; log.add("下一位需摸 2 张牌"); }
        else if (c.value == 14) { pendingDraw += 4; steps = 1; log.add("下一位需摸 4 张牌"); }
        if (hand.size() == 1 && !over) {
            unoPendingSide = side; unoPendingSteps = steps;
            log.add(names[side] + " 只剩 1 张牌，请喊 UNO");
            return true;
        }
        advance(steps); return true;
    }

    private boolean playable(int side, Card c) {
        if (c.wildDrawFour() && hasColorMatch(side)) return false;
        if (pendingDraw > 0) return c.value == 12 || c.value == 14;
        return c.color < 0 || c.color == activeColor || c.value == top.value;
    }
    private int handScore(List<Card> hand) {
        int score = 0;
        for (Card c : hand) score += c.value <= 9 ? c.value : c.value >= 13 ? 50 : 20;
        return score;
    }
    private void rotateHands() {
        List<Integer> activeSides = new ArrayList<>();
        for (int side = 1; side <= count; side++) if (active[side]) activeSides.add(side);
        if (activeSides.size() < 2) return;
        List<List<Card>> snapshot = new ArrayList<>();
        for (int side : activeSides) snapshot.add(hands.get(side));
        for (int i = 0; i < activeSides.size(); i++) {
            int sourceIndex = (i - direction + activeSides.size()) % activeSides.size();
            hands.set(activeSides.get(i), snapshot.get(sourceIndex));
        }
    }
    private void advance(int steps) {
        if (seq >= MAX_MOVES) { over = true; winner = null; log.add("达到手数上限，判为平局"); return; }
        for (int i = 0; i < steps; i++) turn = nextActive(turn);
    }
    private int nextActive(int from) {
        int candidate = from;
        for (int step = 0; step < count; step++) {
            candidate = ((candidate - 1 + direction + count) % count) + 1;
            if (active[candidate]) return candidate;
        }
        return from;
    }
    private int activeCount() {
        int result = 0;
        for (int side = 1; side <= count; side++) if (active[side]) result++;
        return result;
    }

    /** 退出只淘汰该座位；剩最后一人时直接判胜，不能让 4 人局因一人离开整局作废。 */
    @Override
    public boolean forfeit(int side) {
        if (over || side < 1 || side > count || !active[side]) return false;
        active[side] = false;
        hands.get(side).clear();
        if (unoPendingSide == side) { unoPendingSide = 0; unoPendingSteps = 0; }
        if (drawnPendingSide == side) { drawnPendingSide = 0; drawnCardIndex = -1; }
        if (pendingDraw > 0 && turn == side) pendingDraw = 0;
        log.add(names[side] + " 退出本局");
        if (activeCount() <= 1) {
            over = true;
            winner = 0;
            for (int s = 1; s <= count; s++) if (active[s]) winner = s;
            if (winner != null && winner > 0) log.add(names[winner] + " 成为最后留在场上的玩家，获胜");
            else { winner = null; log.add("所有玩家都已退出，本局平局"); }
            return true;
        }
        if (turn == side) advance(1);
        return true;
    }
    public boolean isActive(int side) { return side > 0 && side <= count && active[side]; }
    public int getHandCount(int side) { return side > 0 && side <= count ? hands.get(side).size() : 0; }
    public boolean canPlay(int side, int index) {
        return !over && isActive(side) && side == turn && unoPendingSide == 0
                && index >= 0 && index < hands.get(side).size()
                && (drawnPendingSide == 0 || drawnPendingSide == side && index == drawnCardIndex)
                && playable(side, hands.get(side).get(index));
    }
    public boolean canDraw(int side) {
        return !over && isActive(side) && side == turn && unoPendingSide == 0 && drawnPendingSide == 0
                && (pendingDraw > 0 || !hasPlayable(side));
    }
    public List<Card> getHand(int side) { return side > 0 && side <= count ? new ArrayList<>(hands.get(side)) : Collections.emptyList(); }
    public Card getTop() { return top; }
    public boolean hasPlayable(int side) { for (int i = 0; i < getHandCount(side); i++) if (canPlay(side, i)) return true; return false; }
    private boolean hasColorMatch(int side) { for (Card c : hands.get(side)) if (c.color == activeColor) return true; return false; }
    public int getCount() { return count; }
    public int getActiveColor() { return activeColor; }
    public int getDirection() { return direction; }
    public int getPendingDraw() { return pendingDraw; }
    public int getUnoPendingSide() { return unoPendingSide; }
    public int getDrawnPendingSide() { return drawnPendingSide; }
    public int getDrawnCardIndex() { return drawnCardIndex; }
    public Integer getWinner() { return winner; }
    public int getCurrentTurn() { return turn; }
    public boolean isOver() { return over; }
    public String getScoreSummary() {
        if (!over) return null;
        if (winner == null) return "平局（达到手数上限）";
        if (activeCount() == 1) return names[winner] + " 成为最后留在场上的玩家，获胜";
        return names[winner] + " 清空手牌获胜，结算 " + roundScores[winner] + " 分";
    }
    public List<int[]> getMoveHistory() { return new ArrayList<>(history); }
    public List<int[]> getLastMoveEffects() { return Collections.emptyList(); }
    public int getMoveCount() { return history.size(); }
    public int getMoveSeq() { return seq; }
    public int getStepTimeSeconds() { return 30; }
    public int getTotalTimeSeconds() { return 300; }

    void setTopForTest(int color, int value) { top = new Card(color, value); activeColor = color >= 0 ? color : activeColor; }
    void setHandForTest(int side, List<Card> cards) { hands.set(side, new ArrayList<>(cards)); }
    void setDeckForTest(List<Card> cards, List<Card> discarded) { deck.clear(); deck.addAll(cards); discard.clear(); discard.addAll(discarded); }
    public Object getViewState(int viewerSide) {
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("kind", "UNO"); view.put("turn", turn); view.put("over", over); view.put("winner", winner);
        view.put("canDraw", canDraw(viewerSide));
        view.put("canEndDraw", !over && isActive(viewerSide) && drawnPendingSide == viewerSide);
        view.put("canCallUno", !over && isActive(viewerSide) && unoPendingSide == viewerSide);
        view.put("canCatchUno", !over && isActive(viewerSide) && unoPendingSide != 0 && unoPendingSide != viewerSide);
        view.put("deckLeft", deck.size()); view.put("discardLeft", discard.size()); view.put("activeColor", activeColor); view.put("drawnPendingSide", drawnPendingSide); view.put("drawnCardIndex", drawnCardIndex); view.put("unoPendingSide", unoPendingSide); view.put("unoCatchable", unoPendingSide != 0); view.put("activeColorLabel", new String[]{"红","黄","绿","蓝"}[activeColor]); view.put("direction", direction); view.put("pendingDraw", pendingDraw);
        Map<String,Object> t = new LinkedHashMap<>(); t.put("color", top.color); t.put("value", top.value); t.put("label", top.label()); view.put("top", t);
        List<Map<String,Object>> ps = new ArrayList<>();
        for (int s = 1; s <= count; s++) {
            Map<String,Object> p = new LinkedHashMap<>(); p.put("side", s); p.put("name", names[s]); p.put("handCount", hands.get(s).size()); p.put("score", roundScores[s]); p.put("active", active[s]);
            if (s == viewerSide) {
                List<Map<String,Object>> h = new ArrayList<>();
                for (int i = 0; i < hands.get(s).size(); i++) { Card c = hands.get(s).get(i); Map<String,Object> x = new LinkedHashMap<>(); x.put("index", i); x.put("color", c.color); x.put("value", c.value); x.put("label", c.label()); x.put("special", c.special()); x.put("playable", canPlay(s, i)); h.add(x); }
                p.put("hand", h);
            }
            ps.add(p);
        }
        view.put("players", ps); view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 12), log.size()))); return view;
    }
}















