package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 21 点：2–8 人同桌对庄家，每人依次要牌或停牌，庄家 17 点停牌。
 *
 * 为什么适合这个平台：
 * - 一局 1–2 分钟，适合碎片时间与多人房间；
 * - 规则人人熟悉，不需要教程；
 * - 座位轮流行动，直接复用现有房间的计时/AI/回放/观战链路。
 */
public class BlackjackEngine implements GameEngine {

    public static final int ACTION_HIT = 0;
    public static final int ACTION_STAND = 1;
    public static final int DEALER_STAND = 17;
    private static final int MAX_PLAYERS = 8;
    private static final int DECK_COPIES = 6;
    static final int STEP_TIME_SECONDS = 30;
    static final int TOTAL_TIME_SECONDS = 300;

    private final Random random;
    private final List<Integer> deck = new ArrayList<>();
    /** 非 null 时只供测试重放固定牌序；生产房间保持 null。 */
    private List<Integer> injectedDeck;
    @SuppressWarnings("unchecked")
    private final List<Integer>[] hands = new List[MAX_PLAYERS + 1];
    private final List<Integer> dealer = new ArrayList<>();
    private final boolean[] stood = new boolean[MAX_PLAYERS + 1];
    private final boolean[] busted = new boolean[MAX_PLAYERS + 1];
    private final boolean[] natural = new boolean[MAX_PLAYERS + 1];
    private final String[] names = new String[MAX_PLAYERS + 1];
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private int playerCount = 2;
    private int currentTurn = 1;
    private int moveSeq;
    private boolean over;

    public BlackjackEngine() { this(new Random()); }

    BlackjackEngine(Random random) {
        this.random = random;
        reset();
    }

    /** 仅供回归测试构造确定性牌序；正常房间始终使用随机洗牌。 */
    void setDeckForTest(List<Integer> cards) {
        injectedDeck = new ArrayList<>();
        // drawCard() 从末尾抽取；测试按实际发牌顺序传入，转成栈序。
        for (int i = cards.size() - 1; i >= 0; i--) injectedDeck.add(cards.get(i));
        deck.clear();
        deck.addAll(injectedDeck);
        resetFromCurrentDeck();
    }

    @Override public GameType type() { return GameType.BLACKJACK; }

    @Override
    public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("21 点支持 2–8 人");
        playerCount = count;
    }

    @Override
    public void reset() {
        deck.clear();
        if (injectedDeck == null) buildDeck();
        else deck.addAll(injectedDeck);
        resetFromCurrentDeck();
    }

    private void resetFromCurrentDeck() {
        dealer.clear();
        history.clear();
        lastEffects.clear();
        log.clear();
        playerCount = Math.max(2, Math.min(MAX_PLAYERS, playerCount));
        for (int i = 1; i <= MAX_PLAYERS; i++) {
            hands[i] = new ArrayList<>();
            stood[i] = false;
            busted[i] = false;
            natural[i] = false;
        }
        for (int side = 1; side <= playerCount; side++) {
            hands[side].add(drawCard());
            hands[side].add(drawCard());
            if (total(hands[side]) == 21) { natural[side] = true; stood[side] = true; }
        }
        dealer.add(drawCard());
        dealer.add(drawCard());
        currentTurn = firstPlaying();
        over = false;
        moveSeq = 0;
        log.add("21 点开局：每人两张牌，庄家亮一张，17 点停牌");
        if (currentTurn == 0) dealerPlay();
    }

    private void buildDeck() {
        deck.clear();
        for (int copy = 0; copy < DECK_COPIES; copy++) {
            for (int card = 0; card < 52; card++) deck.add(card);
        }
        Collections.shuffle(deck, random);
    }

    private int drawCard() {
        // 固定牌序可能只有十几张；测试期间不能用「少于 20 张」触发的
        // 自动换靴把注入牌序冲掉。正常牌堆仍按原有阈值换新靴。
        // 固定牌序抽完后（例如庄家继续补牌）回落到正常新靴，
        // 避免测试辅助反而制造越界异常。
        if (deck.isEmpty() || (deck.size() < 20 && injectedDeck == null)) buildDeck();
        return deck.remove(deck.size() - 1);
    }

    @Override public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= MAX_PLAYERS && name != null && !name.isEmpty()) names[side] = name;
    }

    @Override public boolean autoEndsTurn() { return false; }
    @Override public boolean simultaneousTurn() { return false; }

    @Override
    public boolean isAwaitingAction(int side) {
        return !over && side == currentTurn && side >= 1 && side <= playerCount && !stood[side];
    }

    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction() || !isAwaitingAction(side)) return false;
        if (command.getAction() == ACTION_HIT) return hit(side);
        if (command.getAction() == ACTION_STAND) return stand(side);
        return false;
    }

    private boolean hit(int side) {
        int card = drawCard();
        hands[side].add(card);
        int total = total(hands[side]);
        lastEffects.clear();
        lastEffects.add(new int[]{side, ACTION_HIT, rankOf(card), suitOf(card), total});
        history.add(new int[]{side, ACTION_HIT, rankOf(card), suitOf(card), total});
        moveSeq++;
        if (total > 21) {
            busted[side] = true;
            stood[side] = true;
            log.add(nameOf(side) + " 爆牌（" + total + "）");
            advance();
        } else if (total == 21) {
            stood[side] = true;
            log.add(nameOf(side) + " 正好 21 点，自动停牌");
            advance();
        } else {
            log.add(nameOf(side) + " 要牌，当前 " + total + " 点");
        }
        return true;
    }

    @Override
    public boolean endTurn(int side) {
        if (!isAwaitingAction(side)) return false;
        return stand(side);
    }

    private boolean stand(int side) {
        stood[side] = true;
        lastEffects.clear();
        lastEffects.add(new int[]{side, ACTION_STAND, 0, 0, total(hands[side])});
        history.add(new int[]{side, ACTION_STAND, 0, 0, total(hands[side])});
        moveSeq++;
        log.add(nameOf(side) + " 停牌（" + total(hands[side]) + " 点）");
        advance();
        return true;
    }

    private void advance() {
        int next = firstPlaying();
        if (next == 0) dealerPlay();
        else currentTurn = next;
    }

    private int firstPlaying() {
        for (int side = 1; side <= playerCount; side++) {
            if (!stood[side]) return side;
        }
        return 0;
    }

    private void dealerPlay() {
        while (total(dealer) < DEALER_STAND) dealer.add(drawCard());
        over = true;
        currentTurn = 0;
        int dealerTotal = total(dealer);
        log.add("庄家最终 " + dealerTotal + " 点" + (dealerTotal > 21 ? "，爆牌" : ""));
        for (int side = 1; side <= playerCount; side++) {
            log.add(nameOf(side) + "：" + total(hands[side]) + " 点 · " + resultLabel(side, dealerTotal));
        }
    }

    private String resultLabel(int side, int dealerTotal) {
        if (busted[side]) return "负";
        if (dealerTotal > 21) return natural[side] ? "黑杰克胜" : "胜";
        int mine = total(hands[side]);
        if (mine > dealerTotal) return natural[side] ? "黑杰克胜" : "胜";
        if (mine == dealerTotal) return natural[side] && !isNaturalDealer() ? "黑杰克胜" : "平";
        return "负";
    }

    private boolean isNaturalDealer() { return dealer.size() == 2 && total(dealer) == 21; }

    @Override
    public boolean forfeit(int side) {
        if (side < 1 || side > playerCount || stood[side] || over) return false;
        busted[side] = true;
        stood[side] = true;
        log.add(nameOf(side) + " 离场");
        history.add(new int[]{side, -1, 0, 0, 0});
        moveSeq++;
        advance();
        return true;
    }

    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return over; }

    @Override
    public Integer getWinner() {
        List<Integer> wins = getWinningSides();
        return wins.size() == 1 ? wins.get(0) : null;
    }

    @Override
    public List<Integer> getWinningSides() {
        if (!over) return Collections.emptyList();
        int dealerTotal = total(dealer);
        List<Integer> wins = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            int mine = total(hands[side]);
            if (busted[side]) continue;
            if (dealerTotal > 21) { wins.add(side); continue; }
            if (mine > dealerTotal) wins.add(side);
            else if (mine == dealerTotal && natural[side] && !isNaturalDealer()) wins.add(side);
        }
        return wins;
    }

    @Override
    public List<Integer> getDrawingSides() {
        if (!over) return Collections.emptyList();
        int dealerTotal = total(dealer);
        List<Integer> pushes = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            if (busted[side]) continue;
            int mine = total(hands[side]);
            if (mine == dealerTotal && !(natural[side] && !isNaturalDealer())) pushes.add(side);
        }
        return pushes;
    }

    @Override
    public String getScoreSummary() {
        if (!over) return null;
        StringBuilder sb = new StringBuilder("庄家 ").append(total(dealer)).append(" 点");
        for (int side = 1; side <= playerCount; side++) {
            sb.append(" · ").append(nameOf(side)).append(" ").append(total(hands[side])).append(" 点");
        }
        return sb.toString();
    }

    @Override public List<int[]> getMoveHistory() { return new ArrayList<>(history); }
    @Override public List<int[]> getLastMoveEffects() { return new ArrayList<>(lastEffects); }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return moveSeq; }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }

    public int getTotal(int side) { return side >= 1 && side <= MAX_PLAYERS ? total(hands[side]) : 0; }
    public boolean isBusted(int side) { return side >= 1 && side <= MAX_PLAYERS && busted[side]; }
    public int getDealerTotal() { return total(dealer); }

    /** 只暴露庄家的明牌点数；未终局时 AI 和玩家都不能看到暗牌。 */
    public int getDealerVisibleTotal() { return dealer.isEmpty() ? 0 : total(Collections.singletonList(dealer.get(0))); }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "BLACKJACK");
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("dealerTotal", over ? total(dealer) : null);
        view.put("dealerVisibleTotal", getDealerVisibleTotal());
        List<Map<String, Object>> dealerCards = new ArrayList<>();
        for (int i = 0; i < dealer.size(); i++) {
            boolean hidden = !over && i == 1;
            dealerCards.add(cardView(dealer.get(i), hidden));
        }
        view.put("dealer", dealerCards);

        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("side", side);
            p.put("name", nameOf(side));
            p.put("total", total(hands[side]));
            p.put("stood", stood[side]);
            p.put("busted", busted[side]);
            p.put("blackjack", natural[side]);
            List<Map<String, Object>> cards = new ArrayList<>();
            for (int card : hands[side]) cards.add(cardView(card, false));
            p.put("cards", cards);
            if (over) {
                int dealerTotal = total(dealer);
                int result = busted[side] ? -1
                        : (dealerTotal > 21 || total(hands[side]) > dealerTotal
                        || (total(hands[side]) == dealerTotal && natural[side] && !isNaturalDealer())) ? 1
                        : total(hands[side]) == dealerTotal ? 0 : -1;
                p.put("result", result); // 1=胜 0=平 -1=负
            }
            players.add(p);
        }
        view.put("players", players);
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 12), log.size())));
        return view;
    }

    private static Map<String, Object> cardView(int card, boolean hidden) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (hidden) {
            view.put("hidden", true);
            view.put("label", "?");
        } else {
            view.put("hidden", false);
            view.put("rank", rankOf(card));
            view.put("suit", suitOf(card));
            view.put("label", rankLabel(card));
            view.put("suitLabel", suitLabel(card));
        }
        return view;
    }

    static int rankOf(int card) { return card % 13 + 1; }
    static int suitOf(int card) { return card / 13; }

    static String rankLabel(int card) {
        int rank = rankOf(card);
        if (rank == 1) return "A";
        if (rank == 11) return "J";
        if (rank == 12) return "Q";
        if (rank == 13) return "K";
        return String.valueOf(rank);
    }

    static String suitLabel(int card) {
        String[] suits = {"♠", "♥", "♦", "♣"};
        return suits[Math.max(0, Math.min(3, suitOf(card)))];
    }

    static int total(List<Integer> cards) {
        int total = 0;
        int aces = 0;
        for (int card : cards) {
            int rank = rankOf(card);
            if (rank == 1) { aces++; total += 11; }
            else total += Math.min(rank, 10);
        }
        while (total > 21 && aces > 0) { total -= 10; aces--; }
        return total;
    }

    private String nameOf(int side) {
        return names[side] == null ? "玩家 " + side : names[side];
    }
}
