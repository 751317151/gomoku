package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 炸弹猫：2–8 人轮流出牌/抽牌的隐藏信息卡牌玩法。
 *
 * 规则：
 * - 每人起手 4 张行动牌 + 1 张拆弹器；牌库中混有「人数 - 1」枚炸弹。
 * - 每回合可以打出任意数量行动牌，最后必须抽一张牌结束回合（或由跳过/攻击直接结束）。
 * - 抽到炸弹时：有拆弹器则消耗拆弹器，并秘密选择把炸弹放回牌库顶/中/底；
 *   没有拆弹器则出局。最后存活的玩家获胜。
 * - 行动牌：跳过（下家被跳过）、攻击（下家连走两回合）、预知（私看牌库顶 3 张）、
 *   洗牌、偷牌（从指定玩家手中随机拿走一张）。
 *
 * 隐藏信息：手牌、牌库顺序、炸弹放回位置只对本人可见；日志不会公开抽到的牌名，
 * 偷牌也不会公开被偷走的牌。AI 只读取自己的手牌与公开计数。
 */
public class BombCatEngine implements GameEngine {

    public enum CardType {
        DEFUSE("拆弹器"),
        SKIP("跳过"),
        ATTACK("攻击"),
        SEE_FUTURE("预知"),
        SHUFFLE("洗牌"),
        FAVOR("偷牌"),
        BOMB("炸弹");

        private final String label;
        CardType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public static final int DRAW_ACTION = 100;
    public static final int REINSERT_TOP = 110;
    public static final int REINSERT_MIDDLE = 111;
    public static final int REINSERT_BOTTOM = 112;
    public static final int START_HAND = 4;
    public static final int MAX_PLAYERS = 8;
    /** 每回合最多打出的行动牌数，避免无限拖延；抽牌之后回合结束。 */
    public static final int MAX_PLAYS_PER_TURN = 3;
    /** 总行动上限；牌库循环与偷牌都不会永远拖住一局。 */
    static final int MAX_MOVES = 600;
    private static final int STEP_TIME_SECONDS = 30;
    private static final int TOTAL_TIME_SECONDS = 600;
    private static final int PEEK_COUNT = 3;

    public static final class Card {
        public final CardType type;
        Card(CardType type) { this.type = type; }
        public String getLabel() { return type.getLabel(); }
    }

    private final Random random;
    private final List<int[]> history = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<Card> deck = new ArrayList<>();
    private final List<Card> discard = new ArrayList<>();
    private final String[] names = new String[MAX_PLAYERS + 1];
    private List<List<Card>> hands = new ArrayList<>();
    private boolean[] active = new boolean[MAX_PLAYERS + 1];
    private int[] extraTurns = new int[MAX_PLAYERS + 1];
    private int[] playsThisTurn = new int[MAX_PLAYERS + 1];
    private int count = 4, turn = 1, seq;
    private boolean over, draw;
    private Integer winner;
    private String drawReason;
    private int pendingReinsertSide;
    private Card pendingBomb;
    private int peekSide;
    private List<Card> peekCards = new ArrayList<>();

    public BombCatEngine() { this(new Random()); }
    BombCatEngine(Random random) {
        this.random = random;
        reset();
    }

    @Override public GameType type() { return GameType.BOMB_CAT; }

    @Override
    public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("炸弹猫支持 2–8 人");
        this.count = count;
    }

    @Override
    public void reset() {
        history.clear();
        log.clear();
        deck.clear();
        discard.clear();
        hands = new ArrayList<>();
        hands.add(Collections.emptyList());
        active = new boolean[MAX_PLAYERS + 1];
        extraTurns = new int[MAX_PLAYERS + 1];
        playsThisTurn = new int[MAX_PLAYERS + 1];
        for (int side = 1; side <= MAX_PLAYERS; side++) {
            names[side] = null;
            active[side] = side <= count;
            hands.add(new ArrayList<>());
        }
        turn = 1;
        seq = 0;
        over = false;
        draw = false;
        winner = null;
        drawReason = null;
        pendingReinsertSide = 0;
        pendingBomb = null;
        peekSide = 0;
        peekCards = new ArrayList<>();
        buildDeck();
        log.add("炸弹猫开始：每回合可出牌，最后抽一张；抽到炸弹又没有拆弹器就会出局");
    }

    private void buildDeck() {
        List<Card> safe = new ArrayList<>();
        addCards(safe, CardType.SKIP, count + 2);
        addCards(safe, CardType.ATTACK, Math.max(2, count - 1));
        addCards(safe, CardType.SEE_FUTURE, count + 2);
        addCards(safe, CardType.SHUFFLE, count);
        addCards(safe, CardType.FAVOR, count + 1);
        Collections.shuffle(safe, random);

        for (int side = 1; side <= count; side++) {
            List<Card> hand = hands.get(side);
            for (int i = 0; i < START_HAND && !safe.isEmpty(); i++) {
                hand.add(safe.remove(safe.size() - 1));
            }
            hand.add(new Card(CardType.DEFUSE));
        }
        deck.addAll(safe);
        addCards(deck, CardType.BOMB, Math.max(1, count - 1));
        addCards(deck, CardType.DEFUSE, 2);
        Collections.shuffle(deck, random);
    }

    private static void addCards(List<Card> target, CardType type, int amount) {
        for (int i = 0; i < amount; i++) target.add(new Card(type));
    }

    @Override
    public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= count && name != null && !name.isEmpty()) names[side] = name;
    }

    /** 每次行动都由引擎推进到下一个合法阶段；房间据此刷新计时与 AI。 */
    @Override public boolean autoEndsTurn() { return true; }
    @Override public int getActionRound() { return seq; }

    @Override
    public boolean isAwaitingAction(int side) {
        return !over && side >= 1 && side <= count && active[side] && turn == side
                && (pendingReinsertSide == 0 || pendingReinsertSide == side);
    }

    /** 本回合剩余可打出的行动牌数；抽牌不受此限制。 */
    public int playsLeft(int side) {
        if (side < 1 || side > count) return 0;
        return Math.max(0, MAX_PLAYS_PER_TURN - playsThisTurn[side]);
    }

    /**
     * 引擎自行管理回合结束，因此房间的「结束回合」按钮等价于抽牌。
     */
    @Override
    public boolean endTurn(int side) {
        if (over || !isAwaitingAction(side) || pendingReinsertSide != 0) return false;
        boolean applied = drawForTurn(side);
        if (applied) finishByMoveCapIfNeeded();
        return applied;
    }

    @Override
    public boolean applyMove(MoveCommand command, int side) {
        boolean applied = applyMoveInternal(command, side);
        if (applied) finishByMoveCapIfNeeded();
        return applied;
    }

    private boolean applyMoveInternal(MoveCommand command, int side) {
        if (over || command == null || !isAwaitingAction(side)) return false;
        if (pendingReinsertSide != 0) {
            if (pendingReinsertSide != side || !command.hasAction()) return false;
            int position = command.getAction();
            if (position != REINSERT_TOP && position != REINSERT_MIDDLE && position != REINSERT_BOTTOM) return false;
            reinsertBomb(position);
            return true;
        }
        if (!command.hasAction()) return false;
        int action = command.getAction();
        if (action == DRAW_ACTION) return drawForTurn(side);
        if (playsThisTurn[side] >= MAX_PLAYS_PER_TURN) return false;
        List<Card> hand = hands.get(side);
        if (action < 0 || action >= hand.size()) return false;
        Card card = hand.get(action);
        if (card.type == CardType.DEFUSE || card.type == CardType.BOMB) return false;

        if (card.type == CardType.FAVOR) {
            if (!command.hasTarget() || command.getTargetSide() != 2) return false;
            int target = command.getTargetIndex();
            if (target < 1 || target > count || target == side || !active[target]
                    || hands.get(target).isEmpty()) return false;
            hand.remove(action);
            discard.add(card);
            int stolenIndex = random.nextInt(hands.get(target).size());
            Card stolen = hands.get(target).remove(stolenIndex);
            hand.add(stolen);
            playsThisTurn[side]++;
            history.add(new int[]{side, CardType.FAVOR.ordinal(), target, 0});
            seq++;
            log.add(nameOf(side) + " 从 " + nameOf(target) + " 手中拿走一张牌");
            return true;
        }

        hand.remove(action);
        discard.add(card);
        playsThisTurn[side]++;
        switch (card.type) {
            case SKIP:
                history.add(new int[]{side, CardType.SKIP.ordinal(), 0, 0});
                seq++;
                log.add(nameOf(side) + " 打出跳过，下一位玩家被跳过");
                endTurn(true);
                return true;
            case ATTACK:
                history.add(new int[]{side, CardType.ATTACK.ordinal(), 0, 0});
                seq++;
                int victim = nextActive(side);
                if (victim != 0) {
                    extraTurns[victim]++;
                    turn = victim;
                    log.add(nameOf(side) + " 打出攻击，" + nameOf(victim) + " 将连走两回合");
                } else {
                    endTurn(false);
                }
                return true;
            case SEE_FUTURE:
                history.add(new int[]{side, CardType.SEE_FUTURE.ordinal(), 0, 0});
                seq++;
                peekSide = side;
                peekCards = topCards(PEEK_COUNT);
                log.add(nameOf(side) + " 打出预知，查看牌库顶");
                return true;
            case SHUFFLE:
                history.add(new int[]{side, CardType.SHUFFLE.ordinal(), 0, 0});
                seq++;
                Collections.shuffle(deck, random);
                clearPeek();
                log.add(nameOf(side) + " 打出洗牌，牌库重新洗匀");
                return true;
            default:
                return false;
        }
    }

    private boolean drawForTurn(int side) {
        if (pendingReinsertSide != 0) return false;
        Card card = draw();
        if (card == null) {
            over = true;
            draw = true;
            winner = null;
            drawReason = "牌库耗尽";
            log.add("牌库和弃牌堆都已耗尽，本局平局");
            return true;
        }
        if (card.type == CardType.BOMB) {
            int defuseIndex = findCard(side, CardType.DEFUSE);
            if (defuseIndex >= 0) {
                hands.get(side).remove(defuseIndex);
                discard.add(new Card(CardType.DEFUSE));
                pendingReinsertSide = side;
                pendingBomb = card;
                history.add(new int[]{side, DRAW_ACTION, CardType.BOMB.ordinal(), 1});
                seq++;
                log.add(nameOf(side) + " 抽到炸弹，拆弹器已生效：请秘密选择放回位置");
                return true;
            }
            discard.add(card);
            history.add(new int[]{side, DRAW_ACTION, CardType.BOMB.ordinal(), 0});
            seq++;
            eliminate(side);
            return true;
        }
        hands.get(side).add(card);
        history.add(new int[]{side, DRAW_ACTION, card.type.ordinal(), 0});
        seq++;
        clearPeek();
        log.add(nameOf(side) + " 抽了一张牌");
        endTurn(false);
        return true;
    }

    private void reinsertBomb(int position) {
        Card bomb = pendingBomb;
        pendingBomb = null;
        pendingReinsertSide = 0;
        int index;
        if (position == REINSERT_TOP) index = deck.size();
        else if (position == REINSERT_BOTTOM) index = 0;
        else index = Math.max(0, deck.size() / 2);
        deck.add(index, bomb);
        history.add(new int[]{turn, position, CardType.BOMB.ordinal(), 2});
        seq++;
        log.add(nameOf(turn) + " 把炸弹秘密放回牌库");
        endTurn(false);
    }

    private Card draw() {
        if (deck.isEmpty()) recycleDiscard();
        if (deck.isEmpty()) return null;
        return deck.remove(deck.size() - 1);
    }

    private void recycleDiscard() {
        if (discard.isEmpty()) return;
        deck.addAll(discard);
        discard.clear();
        Collections.shuffle(deck, random);
        log.add("牌库抽空，弃牌堆洗回牌库");
    }

    private List<Card> topCards(int amount) {
        List<Card> result = new ArrayList<>();
        for (int i = 0; i < amount && i < deck.size(); i++) {
            result.add(deck.get(deck.size() - 1 - i));
        }
        return result;
    }

    private int findCard(int side, CardType type) {
        List<Card> hand = hands.get(side);
        for (int i = 0; i < hand.size(); i++) if (hand.get(i).type == type) return i;
        return -1;
    }

    private void eliminate(int side) {
        if (!active[side]) return;
        active[side] = false;
        extraTurns[side] = 0;
        playsThisTurn[side] = 0;
        List<Card> hand = hands.get(side);
        for (int i = 0; i < hand.size(); i++) discard.add(hand.get(i));
        hand.clear();
        if (pendingReinsertSide == side) {
            pendingReinsertSide = 0;
            if (pendingBomb != null) discard.add(pendingBomb);
            pendingBomb = null;
        }
        log.add(nameOf(side) + " 没有拆弹器，出局");
        int remaining = activeCount();
        if (remaining <= 1) {
            finish();
            return;
        }
        if (turn == side) endTurn(false);
    }

    private void finish() {
        over = true;
        int remaining = firstActive();
        winner = remaining == 0 ? null : remaining;
        draw = winner == null;
        if (draw) drawReason = "所有玩家出局";
        if (winner != null) log.add(nameOf(winner) + " 存活到最后，赢得本局");
        else log.add("所有玩家都已出局，本局平局");
    }

    /**
     * 牌库可以循环，偷牌也能无限进行；总行动上限保证一局不会拖到总时耗尽。
     * 已经有人存活时达到上限按平局处理，避免把「最后行动者」误判成胜者。
     */
    private void finishByMoveCapIfNeeded() {
        if (over || history.size() < MAX_MOVES) return;
        over = true;
        draw = true;
        winner = null;
        drawReason = "行动上限";
        pendingReinsertSide = 0;
        pendingBomb = null;
        clearPeek();
        log.add("本局达到 " + MAX_MOVES + " 个行动上限，判为平局");
    }

    private void endTurn(boolean skipNext) {
        if (over) return;
        int current = turn;
        if (extraTurns[current] > 0) {
            extraTurns[current]--;
            playsThisTurn[current] = 0;
            log.add(nameOf(current) + " 开始额外回合");
            return;
        }
        int next = nextActive(current);
        if (next == 0) { finish(); return; }
        if (skipNext) {
            int after = nextActive(next);
            if (after != 0) next = after;
        }
        turn = next;
        playsThisTurn[next] = 0;
    }

    private int nextActive(int from) {
        for (int step = 1; step <= count; step++) {
            int candidate = ((from - 1 + step) % count) + 1;
            if (active[candidate]) return candidate;
        }
        return 0;
    }

    private int firstActive() {
        for (int side = 1; side <= count; side++) if (active[side]) return side;
        return 0;
    }

    private int activeCount() {
        int result = 0;
        for (int side = 1; side <= count; side++) if (active[side]) result++;
        return result;
    }

    private void clearPeek() {
        peekSide = 0;
        peekCards = new ArrayList<>();
    }

    @Override
    public boolean forfeit(int side) {
        if (over || side < 1 || side > count || !active[side]) return false;
        log.add(nameOf(side) + " 退出本局");
        eliminate(side);
        return true;
    }

    @Override public int getCurrentTurn() { return turn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }

    @Override
    public List<Integer> getWinningSides() {
        return over && winner != null ? Collections.singletonList(winner) : Collections.emptyList();
    }

    @Override
    public List<Integer> getDrawingSides() {
        if (!over || !draw) return Collections.emptyList();
        List<Integer> sides = new ArrayList<>();
        for (int side = 1; side <= count; side++) if (active[side]) sides.add(side);
        return sides;
    }

    @Override
    public String getScoreSummary() {
        if (!over) return null;
        if (draw) return (drawReason == null ? "平局" : drawReason + " · 平局");
        return (winner == null ? "无人存活" : nameOf(winner) + " 存活到最后")
                + " · 剩余牌库 " + deck.size() + " 张";
    }

    @Override public List<int[]> getMoveHistory() { return new ArrayList<>(history); }
    @Override public List<int[]> getLastMoveEffects() { return Collections.emptyList(); }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return seq; }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }

    public List<Card> getHand(int side) {
        return side >= 1 && side <= count ? new ArrayList<>(hands.get(side)) : Collections.emptyList();
    }
    public int getDeckCount() { return deck.size(); }
    public int getDiscardCount() { return discard.size(); }
    public int getPlayerCount() { return count; }
    public int getPendingReinsertSide() { return pendingReinsertSide; }
    public boolean isActive(int side) { return side >= 1 && side <= count && active[side]; }
    public boolean hasCard(int side, CardType type) { return findCard(side, type) >= 0; }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "BOMB_CAT");
        view.put("turn", turn);
        view.put("over", over);
        view.put("winner", winner);
        view.put("draw", draw);
        view.put("deckCount", deck.size());
        view.put("discardCount", discard.size());
        view.put("playsLeft", playsLeft(viewerSide));
        view.put("maxPlaysPerTurn", MAX_PLAYS_PER_TURN);
        view.put("pendingReinsertSide", pendingReinsertSide);
        view.put("peekForSide", peekSide);
        List<Map<String, Object>> peek = new ArrayList<>();
        if (viewerSide == peekSide && peekSide != 0) {
            for (Card card : peekCards) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", card.type.name());
                item.put("label", card.getLabel());
                peek.add(item);
            }
        }
        view.put("peekCards", peek);
        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= count; side++) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("side", side);
            player.put("name", nameOf(side));
            player.put("handCount", hands.get(side).size());
            player.put("active", active[side]);
            player.put("extraTurns", extraTurns[side]);
            if (viewerSide == side) {
                List<Map<String, Object>> hand = new ArrayList<>();
                for (int i = 0; i < hands.get(side).size(); i++) {
                    Card card = hands.get(side).get(i);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("index", i);
                    item.put("type", card.type.name());
                    item.put("label", card.getLabel());
                    item.put("playable", !over && pendingReinsertSide == 0 && turn == side
                            && playsLeft(side) > 0
                            && card.type != CardType.DEFUSE && card.type != CardType.BOMB);
                    hand.add(item);
                }
                player.put("hand", hand);
            }
            players.add(player);
        }
        view.put("players", players);
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 12), log.size())));
        return view;
    }

    private String nameOf(int side) {
        return names[side] == null ? "第 " + side + " 位玩家" : names[side];
    }

    // ============ 测试辅助 ============

    void setHandForTest(int side, List<Card> cards) {
        hands.set(side, new ArrayList<>(cards));
    }

    void setDeckForTest(List<Card> cards) {
        deck.clear();
        deck.addAll(cards);
    }

    void forceTurnForTest(int side) { turn = side; }

    void setActiveForTest(int side, boolean value) { active[side] = value; }

    void forceMoveCountForTest(int moves) {
        history.clear();
        for (int i = 0; i < moves; i++) history.add(new int[]{1, 0, 0, 0});
    }
}
