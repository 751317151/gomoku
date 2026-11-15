package com.gomoku.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 宝石拍卖：2–8 人同时暗投金币，五轮拍卖不同价值的宝石。
 * 出价会消耗金币；最高价玩家（并列时多人）获得本轮宝石分。
 */
public class GemAuctionEngine implements GameEngine {
    public static final int ROUNDS = 5;
    public static final int STARTING_COINS = 30;
    public static final int MAX_BID = 10;
    public static final int[] GEM_VALUES = {3, 5, 7, 9, 12};
    static final int STEP_TIME_SECONDS = 30;
    static final int TOTAL_TIME_SECONDS = 300;
    private static final int MAX_PLAYERS = 8;

    private final Random random;
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final String[] names = new String[MAX_PLAYERS + 1];
    private final int[] coins = new int[MAX_PLAYERS + 1];
    private final int[] gems = new int[MAX_PLAYERS + 1];
    private final int[] bids = new int[MAX_PLAYERS + 1];
    private final int[] lastBids = new int[MAX_PLAYERS + 1];
    private final boolean[] active = new boolean[MAX_PLAYERS + 1];
    private int playerCount = 2, round, currentTurn = 1, moveSeq;
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
        history.clear(); lastEffects.clear(); log.clear(); round = 0; currentTurn = 1; moveSeq = 0;
        over = false; revealed = false; winner = null;
        for (int i = 1; i <= MAX_PLAYERS; i++) {
            coins[i] = STARTING_COINS; gems[i] = 0; bids[i] = -1; lastBids[i] = -1; active[i] = i <= playerCount;
        }
        log.add("宝石拍卖开始：五轮暗投，价高者获得宝石分");
    }
    @Override public void setPlayerName(int side, String name) { if (side > 0 && side <= MAX_PLAYERS && name != null && !name.isEmpty()) names[side] = name; }
    @Override public boolean autoEndsTurn() { return false; }
    @Override public boolean simultaneousTurn() { return true; }
    @Override public int getActionRound() { return round; }
    @Override public boolean isAwaitingAction(int side) { return !over && side >= 1 && side <= playerCount && active[side] && bids[side] < 0; }

    @Override public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction() || !isAwaitingAction(side)) return false;
        int bid = command.getAction();
        if (bid < 0 || bid > MAX_BID || bid > coins[side]) return false;
        revealed = false; bids[side] = bid; moveSeq++;
        history.add(new int[]{side, bid, 0, coins[side], gems[side]});
        log.add(nameOf(side) + " 已提交出价");
        if (allSubmitted()) revealRound(); else currentTurn = nextAwaiting(side);
        return true;
    }
    private boolean allSubmitted() { for (int i = 1; i <= playerCount; i++) if (active[i] && bids[i] < 0) return false; return true; }
    private int nextAwaiting(int from) { for (int n = 1; n <= playerCount; n++) { int s = (from + n - 1) % playerCount + 1; if (isAwaitingAction(s)) return s; } return from; }
    private void revealRound() {
        revealed = true; lastEffects.clear(); int best = -1;
        for (int i = 1; i <= playerCount; i++) if (active[i]) best = Math.max(best, bids[i]);
        int gem = GEM_VALUES[Math.min(round, GEM_VALUES.length - 1)];
        List<Integer> winners = new ArrayList<>();
        for (int i = 1; i <= playerCount; i++) if (active[i]) {
            coins[i] -= bids[i]; lastBids[i] = bids[i]; if (bids[i] == best) { gems[i] += gem; winners.add(i); }
            lastEffects.add(new int[]{i, bids[i], bids[i] == best ? gem : 0, coins[i], gems[i]});
            for (int h = history.size() - 1; h >= 0; h--) { int[] row = history.get(h); if (row[0] == i && row[1] == bids[i] && row[2] == 0) { row[2] = bids[i] == best ? gem : 0; row[3] = coins[i]; row[4] = gems[i]; break; } }
        }
        log.add("第 " + (round + 1) + " 轮宝石 " + gem + " 分：" + (winners.isEmpty() ? "无人获得" : namesOf(winners)) + "（最高出价 " + best + "）");
        round++;
        if (round >= ROUNDS) finish(); else { Arrays.fill(bids, -1); currentTurn = firstActive(); }
    }
    private void finish() { over = true; int best = -1; List<Integer> winners = new ArrayList<>(); for (int i=1;i<=playerCount;i++) if(active[i]) best=Math.max(best,gems[i]); for(int i=1;i<=playerCount;i++) if(active[i]&&gems[i]==best) winners.add(i); winner=winners.size()==1?winners.get(0):null; log.add(winners.size()==1?nameOf(winners.get(0))+" 以 "+best+" 宝石分获胜":"宝石分并列，本局平局"); }
    @Override public boolean forfeit(int side) { if (over || side < 1 || side > playerCount || !active[side]) return false; active[side]=false; bids[side]=-1; log.add(nameOf(side)+" 离场"); int remaining=0; for(int i=1;i<=playerCount;i++) if(active[i]) remaining++; if(remaining<=1){over=true;winner=remaining==1?firstActive():null;} else if(allSubmitted()) revealRound(); return true; }
    private int firstActive(){for(int i=1;i<=playerCount;i++)if(active[i])return i;return 1;}
    private String nameOf(int side){return names[side]==null?"第 "+side+" 方":names[side];}
    private String namesOf(List<Integer> sides){StringBuilder s=new StringBuilder();for(int i=0;i<sides.size();i++){if(i>0)s.append("、");s.append(nameOf(sides.get(i)));}return s.toString();}
    @Override public int getCurrentTurn(){return currentTurn;}
    @Override public boolean isOver(){return over;}
    @Override public Integer getWinner(){return winner;}
    @Override public List<Integer> getWinningSides(){if(!over)return Collections.emptyList();int best=-1;for(int i=1;i<=playerCount;i++)if(active[i])best=Math.max(best,gems[i]);List<Integer>w=new ArrayList<>();for(int i=1;i<=playerCount;i++)if(active[i]&&gems[i]==best)w.add(i);return w;}
    @Override public String getScoreSummary(){if(!over)return null;StringBuilder s=new StringBuilder();for(int i=1;i<=playerCount;i++){if(i>1)s.append(" · ");s.append(nameOf(i)).append(" ").append(gems[i]).append(" 分（余 ").append(coins[i]).append(" 金币）");}return s.toString();}
    @Override public List<int[]> getMoveHistory(){return new ArrayList<>(history);}
    @Override public List<int[]> getLastMoveEffects(){return new ArrayList<>(lastEffects);}
    @Override public int getMoveCount(){return history.size();}
    @Override public int getMoveSeq(){return moveSeq;}
    @Override public int getStepTimeSeconds(){return STEP_TIME_SECONDS;}
    @Override public int getTotalTimeSeconds(){return TOTAL_TIME_SECONDS;}
    public int getCoins(int side){return side>=1&&side<=playerCount?coins[side]:0;}
    public int getGems(int side){return side>=1&&side<=playerCount?gems[side]:0;}
    @Override public Object getViewState(int viewerSide){
        Map<String,Object> view=new LinkedHashMap<>(); view.put("kind","GEM_AUCTION"); view.put("round",Math.min(round+1,ROUNDS)); view.put("totalRounds",ROUNDS); view.put("gemValue",GEM_VALUES[Math.min(round,GEM_VALUES.length-1)]); view.put("turn",currentTurn); view.put("over",over); view.put("winner",winner); view.put("revealed",revealed);
        List<Map<String,Object>> ps=new ArrayList<>(); for(int i=1;i<=playerCount;i++){Map<String,Object> p=new LinkedHashMap<>();p.put("side",i);p.put("name",nameOf(i));p.put("coins",coins[i]);p.put("gems",gems[i]);p.put("active",active[i]);p.put("answered",bids[i]>=0);if(revealed||viewerSide==i)p.put("bid",revealed?lastBids[i]:bids[i]);ps.add(p);} view.put("players",ps);view.put("log",new ArrayList<>(log.subList(Math.max(0,log.size()-12),log.size())));return view;
    }
}
