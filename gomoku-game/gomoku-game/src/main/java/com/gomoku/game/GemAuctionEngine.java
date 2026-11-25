package com.gomoku.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 五轮暗投：公开图录、三色收藏与弃拍回收；只有正出价才能中标。 */
public class GemAuctionEngine implements GameEngine {
    public static final int ROUNDS = 5, STARTING_COINS = 30, MAX_BID = 10;
    public static final int PASS_RECOVERY = 2, PAIR_BONUS = 3, SET_BONUS = 8;
    public static final int[] GEM_VALUES = {3, 5, 7, 9, 12};
    static final int STEP_TIME_SECONDS = 30, TOTAL_TIME_SECONDS = 300, MAX_PLAYERS = 8;
    private static final String[] COLORS = {"红宝石", "蓝宝石", "祖母绿"};
    private final Random random;
    private final List<int[]> history = new ArrayList<>(), lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final String[] names = new String[MAX_PLAYERS + 1];
    private final int[] coins = new int[MAX_PLAYERS + 1], gems = new int[MAX_PLAYERS + 1];
    private final int[] baseScores = new int[MAX_PLAYERS + 1], bids = new int[MAX_PLAYERS + 1];
    private final int[] bidRecords = new int[MAX_PLAYERS + 1], lastBids = new int[MAX_PLAYERS + 1];
    private final int[] lastBonuses = new int[MAX_PLAYERS + 1], lastRecovered = new int[MAX_PLAYERS + 1];
    private final int[][] collections = new int[MAX_PLAYERS + 1][3];
    private final boolean[] lastWon = new boolean[MAX_PLAYERS + 1], active = new boolean[MAX_PLAYERS + 1];
    private final int[] colors = new int[ROUNDS];
    private int playerCount = 2, round, currentTurn = 1;
    private boolean over, revealed;
    private Integer winner;

    public GemAuctionEngine() { this(new Random()); }
    GemAuctionEngine(Random random) { this.random = random; reset(); }
    @Override public GameType type() { return GameType.GEM_AUCTION; }
    @Override public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("宝石拍卖支持 2–8 人");
        playerCount = count;
    }
    @Override public void reset() {
        history.clear(); lastEffects.clear(); log.clear();
        round = 0; currentTurn = 1; over = false; revealed = false; winner = null;
        Arrays.fill(coins, STARTING_COINS); Arrays.fill(gems, 0); Arrays.fill(baseScores, 0);
        Arrays.fill(bids, -1); Arrays.fill(bidRecords, -1); Arrays.fill(lastBids, -1);
        Arrays.fill(lastBonuses, 0); Arrays.fill(lastRecovered, 0); Arrays.fill(lastWon, false);
        for (int side = 1; side <= MAX_PLAYERS; side++) {
            Arrays.fill(collections[side], 0); active[side] = side <= playerCount;
        }
        // 每局至少各有一枚，整份图录开局公开。
        List<Integer> catalog = new ArrayList<>(Arrays.asList(0, 1, 2, random.nextInt(3), random.nextInt(3)));
        Collections.shuffle(catalog, random);
        for (int i = 0; i < ROUNDS; i++) colors[i] = catalog.get(i);
        log.add("五轮收藏拍卖开始：同色每两枚 +3，三色每套 +8；弃拍回收 2 金币（上限 30）");
    }
    @Override public void setPlayerName(int side, String name) {
        if (side > 0 && side <= MAX_PLAYERS && name != null && !name.isEmpty()) names[side] = name;
    }
    @Override public boolean autoEndsTurn() { return false; }
    @Override public boolean simultaneousTurn() { return true; }
    @Override public int getActionRound() { return round; }
    @Override public boolean isAwaitingAction(int side) { return !over && isActive(side) && bids[side] < 0; }
    public boolean isActive(int side) { return side >= 1 && side <= playerCount && active[side]; }
    public List<Integer> legalActions(int side) {
        List<Integer> legal = new ArrayList<>();
        if (isAwaitingAction(side)) for (int bid = 0; bid <= Math.min(MAX_BID, coins[side]); bid++) legal.add(bid);
        return legal;
    }
    @Override public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction() || !isAwaitingAction(side)) return false;
        int bid = command.getAction();
        if (bid > MAX_BID || bid > coins[side]) return false;
        revealed = false; lastEffects.clear(); bids[side] = bid;
        bidRecords[side] = history.size();
        // 前五列兼容旧战报；新增轮次、颜色、基础得分、组合奖励、回收金币。
        history.add(new int[]{side, bid, 0, coins[side], gems[side], round + 1, getCurrentColor(), 0, 0, 0});
        log.add(nameOf(side) + " 已提交出价");
        currentTurn = firstAwaiting();
        if (currentTurn == 0) revealRound();
        return true;
    }
    private int firstAwaiting() {
        for (int side = 1; side <= playerCount; side++) if (isAwaitingAction(side)) return side;
        return 0;
    }
    private void revealRound() {
        revealed = true; int best = 0; int color = getCurrentColor();
        for (int side = 1; side <= playerCount; side++) if (active[side]) best = Math.max(best, bids[side]);
        StringBuilder result = new StringBuilder("第 " + (round + 1) + " 轮 " + COLORS[color] + " " + getCurrentValue() + " 分：");
        for (int side = 1; side <= playerCount; side++) if (active[side]) {
            int bid = bids[side], bonusBefore = collectionBonus(collections[side]);
            int recovered = bid == 0 ? Math.min(PASS_RECOVERY, STARTING_COINS - coins[side]) : 0;
            coins[side] += recovered - bid; lastBids[side] = bid; lastRecovered[side] = recovered;
            lastWon[side] = bid > 0 && bid == best;
            int base = lastWon[side] ? getCurrentValue() : 0;
            if (lastWon[side]) collections[side][color]++;
            lastBonuses[side] = collectionBonus(collections[side]) - bonusBefore;
            baseScores[side] += base; gems[side] += base + lastBonuses[side];
            int[] record = history.get(bidRecords[side]);
            record[2] = base + lastBonuses[side]; record[3] = coins[side]; record[4] = gems[side];
            record[7] = base; record[8] = lastBonuses[side]; record[9] = recovered;
            lastEffects.add(record.clone());
            if (result.charAt(result.length() - 1) != '：') result.append("；");
            result.append(nameOf(side)).append(bid == 0 ? " 弃拍" : " 出价 " + bid);
            if (bid == 0) result.append("（回收 ").append(recovered).append("）");
            else if (lastWon[side]) result.append(" 中标 +").append(base).append(lastBonuses[side] > 0 ? " / 组合 +" + lastBonuses[side] : "");
            else result.append(" 未中标");
        }
        log.add(result.toString()); round++;
        if (round >= ROUNDS) finish();
        else { Arrays.fill(bids, -1); Arrays.fill(bidRecords, -1); currentTurn = firstAwaiting(); }
    }
    private static int collectionBonus(int[] collection) {
        int pairs = 0, sets = Integer.MAX_VALUE;
        for (int count : collection) { pairs += count / 2; sets = Math.min(sets, count); }
        return pairs * PAIR_BONUS + sets * SET_BONUS;
    }
    public int getCurrentColor() { return colors[Math.min(round, ROUNDS - 1)]; }
    public int getCurrentValue() { return GEM_VALUES[Math.min(round, ROUNDS - 1)]; }
    public int getBonusGain(int side) {
        if (!isActive(side) || over) return 0;
        int[] next = collections[side].clone(); next[getCurrentColor()]++;
        return collectionBonus(next) - collectionBonus(collections[side]);
    }
    public int getLastBid(int side) { return side >= 1 && side <= playerCount ? lastBids[side] : -1; }
    private void finish() {
        over = true; List<Integer> winning = getWinningSides();
        winner = winning.size() == 1 ? winning.get(0) : null;
        log.add(winner == null ? "收藏总分并列，本局平局" : nameOf(winner) + " 以 " + gems[winner] + " 分获胜（含组合奖励）");
    }
    @Override public boolean forfeit(int side) {
        if (over || !isActive(side)) return false;
        active[side] = false; bids[side] = -1; lastEffects.clear(); log.add(nameOf(side) + " 离场");
        int remaining = 0, last = 0;
        for (int i = 1; i <= playerCount; i++) if (active[i]) { remaining++; last = i; }
        if (remaining <= 1) { over = true; winner = remaining == 1 ? last : null; }
        else { currentTurn = firstAwaiting(); if (currentTurn == 0) revealRound(); }
        return true;
    }
    private String nameOf(int side) { return names[side] == null ? "第 " + side + " 方" : names[side]; }
    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }
    @Override public List<Integer> getWinningSides() {
        if (!over) return Collections.emptyList();
        int best = -1; List<Integer> winning = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) if (active[side]) best = Math.max(best, gems[side]);
        for (int side = 1; side <= playerCount; side++) if (active[side] && gems[side] == best) winning.add(side);
        return winning;
    }
    @Override public String getScoreSummary() {
        if (!over) return null;
        StringBuilder result = new StringBuilder();
        for (int side = 1; side <= playerCount; side++) {
            if (side > 1) result.append(" · ");
            result.append(nameOf(side)).append(' ').append(gems[side]).append(" 分（组合 +")
                    .append(gems[side] - baseScores[side]).append("，余 ").append(coins[side]).append(" 金币）");
        }
        return result.toString();
    }
    private List<int[]> snapshot(List<int[]> source) {
        List<int[]> copy = new ArrayList<>(); for (int[] record : source) copy.add(record.clone()); return copy;
    }
    @Override public List<int[]> getMoveHistory() { return snapshot(history); }
    @Override public List<int[]> getLastMoveEffects() { return snapshot(lastEffects); }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return history.size(); }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }
    public int getCoins(int side) { return side >= 1 && side <= playerCount ? coins[side] : 0; }
    public int getGems(int side) { return side >= 1 && side <= playerCount ? gems[side] : 0; }
    @Override public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "GEM_AUCTION"); view.put("round", Math.min(round + 1, ROUNDS)); view.put("totalRounds", ROUNDS);
        view.put("gemValue", getCurrentValue()); view.put("color", getCurrentColor()); view.put("colorName", COLORS[getCurrentColor()]);
        view.put("turn", currentTurn); view.put("over", over); view.put("winner", winner);
        view.put("revealed", revealed); view.put("lastRound", round); view.put("legalActions", legalActions(viewerSide));
        List<Map<String, Object>> lots = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            Map<String, Object> lot = new LinkedHashMap<>();
            lot.put("round", i + 1); lot.put("color", colors[i]); lot.put("name", COLORS[colors[i]]); lot.put("value", GEM_VALUES[i]); lots.add(lot);
        }
        view.put("lots", lots);
        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("side", side); player.put("name", nameOf(side)); player.put("coins", coins[side]);
            player.put("gems", gems[side]); player.put("baseScore", baseScores[side]);
            player.put("bonusScore", gems[side] - baseScores[side]); player.put("collection", collections[side].clone());
            player.put("bonusGain", getBonusGain(side)); player.put("active", active[side]); player.put("answered", !isAwaitingAction(side));
            if (revealed || viewerSide == side) player.put("bid", revealed ? lastBids[side] : bids[side]);
            if (round > 0) {
                player.put("lastBid", lastBids[side]); player.put("lastWon", lastWon[side]);
                player.put("lastBonus", lastBonuses[side]); player.put("lastRecovered", lastRecovered[side]);
            }
            players.add(player);
        }
        view.put("players", players); view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 12), log.size())));
        return view;
    }
}
