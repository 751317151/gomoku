package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 斗地主：3 人、54 张牌、叫地主 + 底牌 + 出牌/过牌。
 *
 * 设计取舍：
 * - 座位 1/2/3 为固定顺序，地主先手；农民共享胜负，结算时只给地主或两名农民记胜。
 * - 叫地主按固定座位轮询，每人只能叫一次；三家都不叫则重新发牌（极低概率），
 *   避免为「流局」增加第二套房间状态。
 * - 倍数由底分 × 叫分 × 炸弹/火箭 × 春天计算；炸弹每出现一次翻倍，春天再翻倍。
 * - 手牌只对本人可见；底牌在确定地主后公开，叫地主阶段只公开张数。
 */
public class DoudizhuEngine implements GameEngine {

    public static final int PASS_ACTION = 100;
    public static final int CALL_LANDLORD_ACTION = 101;
    public static final int NO_BID_ACTION = 102;
    public static final int PLAY_MARKER = 200;
    private static final int HAND_SIZE = 17;
    private static final int MAX_MOVES = 500;

    private final Random random;
    private final List<Integer> deck = new ArrayList<>();
    private final List<List<Integer>> hands = new ArrayList<>();
    private final List<Integer> bottom = new ArrayList<>();
    private final List<int[]> history = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private String[] names = new String[4];
    private int[] bids = new int[4];
    private int count = 3, turn = 1, seq;
    private boolean over, bidding = true;
    private Integer winner;
    private int landlord, highestBid;
    private int lastPlaySide;
    private List<Integer> lastPlay = new ArrayList<>();
    private DoudizhuRules.Combo lastCombo;
    private int multiplier = 1;
    private boolean landlordPlayed, farmerPlayed;
    private int landlordPlayCount;
    private int bidTurns;

    public DoudizhuEngine() { this(new Random()); }
    DoudizhuEngine(Random random) {
        this.random = random;
        reset();
    }

    @Override public GameType type() { return GameType.DOUDIZHU; }
    @Override public boolean autoEndsTurn() { return true; }
    @Override public void configurePlayers(int count) { this.count = type().resolvePlayerCount(count); }

    @Override
    public void reset() {
        history.clear();
        log.clear();
        deck.clear();
        bottom.clear();
        hands.clear();
        hands.add(Collections.emptyList());
        names = new String[4];
        bids = new int[4];
        turn = 1;
        seq = 0;
        over = false;
        bidding = true;
        winner = null;
        landlord = 0;
        highestBid = 0;
        lastPlaySide = 0;
        lastPlay = new ArrayList<>();
        lastCombo = null;
        multiplier = 1;
        landlordPlayed = false;
        landlordPlayCount = 0;
        farmerPlayed = false;
        bidTurns = 0;
        for (int side = 1; side <= count; side++) {
            names[side] = "玩家 " + side;
            hands.add(new ArrayList<>());
        }
        deal();
        log.add("斗地主开始：先叫地主，地主获得三张底牌");
    }

    private void deal() {
        deck.clear();
        for (int v = 3; v <= 15; v++) {
            for (int suit = 0; suit < 4; suit++) deck.add(v);
        }
        deck.add(16);
        deck.add(17);
        Collections.shuffle(deck, random);
        for (int side = 1; side <= count; side++) {
            List<Integer> hand = hands.get(side);
            hand.clear();
            for (int i = 0; i < HAND_SIZE; i++) hand.add(deck.remove(deck.size() - 1));
            Collections.sort(hand);
        }
        for (int i = 0; i < 3; i++) bottom.add(deck.remove(deck.size() - 1));
        Collections.sort(bottom);
    }

    @Override
    public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= count && name != null && !name.isEmpty()) names[side] = name;
    }

    @Override public boolean isAwaitingAction(int side) {
        return !over && side >= 1 && side <= count && turn == side;
    }

    /** 叫地主阶段；出牌阶段返回 0。 */
    public boolean isBidding() { return bidding; }

    /** 当前是否轮到该座位叫地主；前端据此只显示叫分按钮。 */
    public boolean isBidTurn(int side) { return bidding && !over && turn == side; }

    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (over || command == null || !isAwaitingAction(side) || !command.hasAction()) return false;
        if (bidding) return bid(side, command.getAction());
        int action = command.getAction();
        if (action == PASS_ACTION) return passTurn(side);
        return false;
    }

    /**
     * 房间层专用出牌：indices 是「我当前手牌」的下标。
     *
     * 通用 MoveCommand 只有一个 action/target 字段，装不下任意长度的手牌下标；
     * 斗地主也不适合把牌值当作网络协议（客户端可伪造）。因此房间在调用前
     * 做所属玩家校验，引擎在这里做牌型与压牌校验。过牌仍走 applyMove(PASS_ACTION)。
     */
    public boolean playByIndices(int side, List<Integer> indices) {
        return play(side, indices);
    }

    /**
     * 出牌入口：indices 是手牌下标（从 0 起，可任意顺序）。
     * 房间层通过 {@link #play(int, List)} 调用，不把牌面塞进通用 MoveCommand。
     */
    public boolean play(int side, List<Integer> indices) {
        if (over || bidding || !isAwaitingAction(side) || indices == null || indices.isEmpty()) return false;
        List<Integer> hand = hands.get(side);
        List<Integer> cards = new ArrayList<>();
        List<Integer> sortedIndices = new ArrayList<>(indices);
        Collections.sort(sortedIndices, Collections.reverseOrder());
        int previous = -1;
        for (int index : sortedIndices) {
            if (index < 0 || index >= hand.size() || index == previous) return false;
            previous = index;
            cards.add(hand.get(index));
        }
        DoudizhuRules.Combo combo = DoudizhuRules.parse(cards);
        if (combo == null) return false;
        // 过牌后重新领出时 lastCombo 已被清空；只有同一轮才需要压过。
        if (lastCombo != null && lastPlaySide != side && !DoudizhuRules.beats(combo, lastCombo)) return false;
        if (combo.kind == DoudizhuRules.Kind.BOMB || combo.kind == DoudizhuRules.Kind.ROCKET) {
            multiplier *= 2;
            log.add("炸弹/火箭出现，倍数翻倍到 ×" + multiplier);
        }
        for (int index : sortedIndices) hand.remove(index);
        Collections.sort(hand);
        lastPlay = new ArrayList<>(cards);
        lastCombo = combo;
        lastPlaySide = side;
        history.add(encodeHistory(side, PlayType.PLAY, cards));
        seq++;
        if (history.size() > MAX_MOVES) {
            over = true;
            winner = null;
            log.add("达到行动上限，本局判为平局");
            return true;
        }
        if (side == landlord) {
            landlordPlayed = true;
            landlordPlayCount++;
        }
        else farmerPlayed = true;
        log.add(names[side] + " 打出" + DoudizhuRules.label(combo) + "（" + cardText(cards) + "）");
        if (hand.isEmpty()) {
            finish(side);
            return true;
        }
        if (history.size() >= MAX_MOVES) {
            over = true;
            winner = null;
            log.add("达到行动上限，本局判为平局");
            return true;
        }
        turn = nextSide(side);
        return true;
    }

    private boolean bid(int side, int action) {
        if (action == NO_BID_ACTION) {
            if (highestBid == 3) return false;
            history.add(new int[]{side, NO_BID_ACTION, 0});
        } else if (action >= 1 && action <= 3 && action > highestBid) {
            highestBid = action;
            bids[side] = action;
            history.add(new int[]{side, CALL_LANDLORD_ACTION, action});
        } else {
            return false;
        }
        seq++;
        if (history.size() > MAX_MOVES) {
            over = true;
            winner = null;
            log.add("达到行动上限，本局判为平局");
            return true;
        }
        bidTurns++;
        log.add(names[side] + (action == NO_BID_ACTION ? " 不叫" : " 叫 " + action + " 分"));
        if (bidTurns >= count || highestBid == 3) {
            if (highestBid == 0) {
                // 三家都不叫：重新发牌，叫地主从头再来（不消耗手数）。
                List<String> keptLogs = new ArrayList<>(log);
                redealKeepingNames();
                log.addAll(keptLogs);
                log.add("三家都不叫，重新发牌");
                log.add("重新发牌完成，请再次叫地主");
                return true;
            }
            landlord = highestBidSide();
            multiplier = highestBid;
            hands.get(landlord).addAll(bottom);
            Collections.sort(hands.get(landlord));
            bidding = false;
            turn = landlord;
            log.add(names[landlord] + " 成为地主，底牌：" + cardText(bottom));
            return true;
        }
        turn = nextSide(side);
        return true;
    }

    private int highestBidSide() {
        for (int[] move : history) {
            if (move[1] == CALL_LANDLORD_ACTION && move[2] == highestBid) return move[0];
        }
        return 1;
    }

    /** 只重发牌，不清空本局玩家昵称与人数；三家不叫使用。 */
    private void redealKeepingNames() {
        String[] kept = names.clone();
        int keptCount = count;
        int keptSeq = seq;
        List<int[]> keptHistory = new ArrayList<>(history);
        reset();
        count = keptCount;
        names = kept;
        seq = keptSeq;
        history.clear();
        history.addAll(keptHistory);
        hands.clear();
        hands.add(Collections.emptyList());
        for (int side = 1; side <= keptCount; side++) hands.add(new ArrayList<>());
        bottom.clear();
        deal();
    }

    private boolean passTurn(int side) {
        if (lastPlaySide == 0 || lastPlaySide == side) return false;
        history.add(new int[]{side, PASS_ACTION, 0});
        seq++;
        log.add(names[side] + " 过牌");
        turn = nextSide(side);
        if (turn == lastPlaySide) {
            // 两家都不要：由上次出牌者重新领出，场面牌型清空。
            lastCombo = null;
            lastPlay = new ArrayList<>();
            lastPlaySide = 0;
            log.add(names[turn] + " 重新领出");
        }
        return true;
    }

    private int nextSide(int side) {
        return side % count + 1;
    }

    private void finish(int side) {
        over = true;
        winner = side;
        boolean landlordWon = side == landlord;
        if (landlordWon && !farmerPlayed) {
            multiplier *= 2;
            log.add("春天：农民一张牌都没出，倍数翻倍");
        } else if (!landlordWon && landlordPlayCount <= 1) {
            multiplier *= 2;
            log.add("反春天：地主只出过一手牌，倍数翻倍");
        }
        log.add((landlordWon ? "地主" : "农民") + "获胜，本局倍数 ×" + multiplier);
    }

    @Override
    public List<Integer> getWinningSides() {
        if (!over || winner == null) return Collections.emptyList();
        if (winner == landlord) return Collections.singletonList(landlord);
        List<Integer> farmers = new ArrayList<>();
        for (int side = 1; side <= count; side++) if (side != landlord) farmers.add(side);
        return farmers;
    }

    @Override
    public String getScoreSummary() {
        if (!over) return null;
        String role = winner == landlord ? "地主" : "农民";
        return role + "获胜 · 倍数 ×" + multiplier
                + " · 底牌 " + cardText(bottom);
    }

    public List<Integer> getHand(int side) {
        return side >= 1 && side <= count ? new ArrayList<>(hands.get(side)) : Collections.emptyList();
    }
    public List<Integer> getBottom() { return new ArrayList<>(bottom); }
    public int getLandlord() { return landlord; }
    public int getMultiplier() { return multiplier; }
    public int getHighestBid() { return highestBid; }
    public int getPlayerCount() { return count; }
    public List<Integer> getLastPlay() { return new ArrayList<>(lastPlay); }
    public int getLastPlaySide() { return lastPlaySide; }
    public DoudizhuRules.Combo getLastCombo() { return lastCombo; }
    private String lastPlayLabel() { return lastCombo == null ? "自由出牌" : DoudizhuRules.label(lastCombo); }

    @Override public int getCurrentTurn() { return turn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }
    @Override public List<int[]> getMoveHistory() { return new ArrayList<>(history); }
    @Override public List<int[]> getLastMoveEffects() { return Collections.emptyList(); }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return seq; }
    @Override public int getStepTimeSeconds() { return 30; }
    @Override public int getTotalTimeSeconds() { return 600; }

    /** 手牌下标组合能否作为当前局面下的合法出牌。 */
    public boolean canPlay(int side, List<Integer> indices) {
        if (over || bidding || side != turn || indices == null || indices.isEmpty()) return false;
        List<Integer> hand = hands.get(side);
        List<Integer> cards = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (int index : indices) {
            // 允许同一张牌被重复点击一次：前端用多选，聚合时应去重。
            if (index < 0 || index >= hand.size()) return false;
            if (seen.contains(index)) continue;
            seen.add(index);
            cards.add(hand.get(index));
        }
        DoudizhuRules.Combo combo = DoudizhuRules.parse(cards);
        return combo != null && (lastCombo == null || lastPlaySide == side || DoudizhuRules.beats(combo, lastCombo));
    }

    /** 叫地主阶段可选择的分值；出牌阶段返回空。 */
    public List<Integer> availableBids() {
        List<Integer> result = new ArrayList<>();
        if (!bidding) return result;
        for (int bid = 1; bid <= 3; bid++) if (bid > highestBid) result.add(bid);
        return result;
    }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "DOUDIZHU");
        view.put("turn", turn);
        view.put("over", over);
        view.put("winner", winner);
        view.put("bidding", bidding);
        view.put("landlord", landlord);
        view.put("highestBid", highestBid);
        view.put("multiplier", multiplier);
        view.put("lastPlay", new ArrayList<>(lastPlay));
        view.put("lastPlaySide", lastPlaySide);
        view.put("lastCombo", lastCombo == null ? null : lastPlayLabel());
        view.put("availableBids", availableBids());
        // 叫地主阶段底牌必须保密；确定地主后才公开。
        view.put("bottom", bidding ? Collections.emptyList() : new ArrayList<>(bottom));
        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= count; side++) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("side", side);
            player.put("name", names[side]);
            player.put("handCount", hands.get(side).size());
            player.put("landlord", side == landlord);
            player.put("bid", bids[side]);
            if (viewerSide == side) {
                List<Map<String, Object>> hand = new ArrayList<>();
                List<Integer> cards = hands.get(side);
                for (int i = 0; i < cards.size(); i++) {
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("index", i);
                    card.put("value", cards.get(i));
                    card.put("label", cardLabel(cards.get(i)));
                    hand.add(card);
                }
                player.put("hand", hand);
            }
            players.add(player);
        }
        view.put("players", players);
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 14), log.size())));
        return view;
    }

    public static String cardLabel(int value) {
        if (value == 16) return "小王";
        if (value == 17) return "大王";
        if (value == 15) return "2";
        if (value == 14) return "A";
        if (value == 13) return "K";
        if (value == 12) return "Q";
        if (value == 11) return "J";
        return String.valueOf(value);
    }

    public static String cardText(List<Integer> cards) {
        StringBuilder sb = new StringBuilder();
        for (int card : cards) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(cardLabel(card));
        }
        return sb.toString();
    }

    private int[] encodeHistory(int side, PlayType type, List<Integer> cards) {
        int[] move = new int[cards.size() + 3];
        move[0] = side;
        move[1] = type == PlayType.PLAY ? PLAY_MARKER : PASS_ACTION;
        move[2] = cards.size();
        for (int i = 0; i < cards.size(); i++) move[3 + i] = cards.get(i);
        return move;
    }

    private enum PlayType { PLAY }

    // ============ 测试辅助 ============
    void setHandForTest(int side, List<Integer> cards) {
        List<Integer> sorted = new ArrayList<>(cards);
        Collections.sort(sorted);
        hands.set(side, sorted);
    }
    void setBiddingForTest(int side, int highest) {
        bidding = false;
        landlord = 1;
        turn = side;
        highestBid = highest;
        multiplier = Math.max(1, highest);
    }
    void setLandlordForTest(int side) { landlord = side; }
    void setBiddingForTest(int side, int highest, int landlordSide) {
        bidding = false;
        landlord = landlordSide;
        turn = side;
        highestBid = highest;
        multiplier = Math.max(1, highest);
    }
}
