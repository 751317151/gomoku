package com.gomoku.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 骰子竞速：2–8 人同时选择策略，六轮累计得分。
 *
 * 每轮三种策略：稳健（1d6）、冒险（2d6，掷出 1 即爆掉）和追击（1d6，
 * 落后第一名时获得 +3，否则只有 -1 加成）。选择在揭晓前隐藏，所有仍在场
 * 玩家提交后一次性结算，形成短局、低门槛但有读心空间的多人玩法。
 */
public class DiceRaceEngine implements GameEngine {

    public static final int ROUNDS = 6;
    public static final int CHOICE_SAFE = 0;
    public static final int CHOICE_RISK = 1;
    public static final int CHOICE_CHASE = 2;
    public static final int CHOICES = 3;

    static final int STEP_TIME_SECONDS = 25;
    static final int TOTAL_TIME_SECONDS = 240;
    static final int MAX_PLAYERS = 8;

    /** 回放编码：{side, choice, roundScore, totalScore} */
    public static final int HIST_SIDE = 0;
    public static final int HIST_CHOICE = 1;
    public static final int HIST_ROUND_SCORE = 2;
    public static final int HIST_TOTAL_SCORE = 3;

    private final Random random;
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final String[] names = new String[MAX_PLAYERS + 1];
    private final int[] scores = new int[MAX_PLAYERS + 1];
    private final int[] streaks = new int[MAX_PLAYERS + 1];
    private final int[] choices = new int[MAX_PLAYERS + 1];
    private final int[] lastChoices = new int[MAX_PLAYERS + 1];
    private final int[] lastRoundScores = new int[MAX_PLAYERS + 1];
    private final int[] lastRolls = new int[MAX_PLAYERS + 1];
    private final boolean[] active = new boolean[MAX_PLAYERS + 1];

    private int playerCount = 2;
    private int currentTurn = 1;
    private int roundIndex;
    private int moveSeq;
    private boolean over;
    private boolean revealed;
    private Integer winner;
    private int lastRoll;
    private boolean hasRevealedRound;

    public DiceRaceEngine() { this(new Random()); }

    DiceRaceEngine(Random random) {
        this.random = random;
        reset();
    }

    @Override public GameType type() { return GameType.DICE_RACE; }

    @Override
    public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("骰子竞速支持 2–8 人");
        playerCount = count;
    }

    @Override
    public void reset() {
        history.clear();
        lastEffects.clear();
        log.clear();
        roundIndex = 0;
        moveSeq = 0;
        currentTurn = 1;
        over = false;
        revealed = false;
        winner = null;
        lastRoll = 0;
        hasRevealedRound = false;
        for (int i = 1; i <= MAX_PLAYERS; i++) {
            scores[i] = 0;
            streaks[i] = 0;
            choices[i] = -1;
            lastChoices[i] = -1;
            lastRoundScores[i] = 0;
            lastRolls[i] = 0;
            active[i] = i <= playerCount;
        }
        log.add("骰子竞速开始：共 " + ROUNDS + " 轮，选择策略后同时掷骰");
    }

    @Override public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= MAX_PLAYERS && name != null && !name.isEmpty()) names[side] = name;
    }

    @Override public boolean autoEndsTurn() { return false; }
    @Override public boolean simultaneousTurn() { return true; }
    @Override public int getActionRound() { return roundIndex; }

    @Override
    public boolean isAwaitingAction(int side) {
        return !over && side >= 1 && side <= playerCount && active[side] && choices[side] < 0;
    }

    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction() || !isAwaitingAction(side)) return false;
        // 上一轮揭晓结果在第一个新选择提交前保留，方便前端播放/阅读结算。
        if (revealed) revealed = false;
        int choice = command.getAction();
        if (choice < 0 || choice >= CHOICES) return false;
        choices[side] = choice;
        moveSeq++;
        history.add(new int[]{side, choice, 0, scores[side]});
        log.add(nameOf(side) + " 已提交策略");
        if (allActiveSubmitted()) revealRound();
        else currentTurn = nextAwaiting(side);
        return true;
    }

    private boolean allActiveSubmitted() {
        boolean any = false;
        for (int side = 1; side <= playerCount; side++) {
            if (!active[side]) continue;
            any = true;
            if (choices[side] < 0) return false;
        }
        return any;
    }

    private int nextAwaiting(int from) {
        for (int n = 1; n <= playerCount; n++) {
            int side = (from + n - 1) % playerCount + 1;
            if (isAwaitingAction(side)) return side;
        }
        return from;
    }

    private void revealRound() {
        revealed = true;
        hasRevealedRound = true;
        lastEffects.clear();
        int leader = 0;
        for (int side = 1; side <= playerCount; side++) if (active[side]) leader = Math.max(leader, scores[side]);
        for (int side = 1; side <= playerCount; side++) {
            if (!active[side]) continue;
            int choice = choices[side];
            int roundScore = rollFor(choice);
            lastRolls[side] = roundScore;
            if (choice == CHOICE_CHASE) roundScore += scores[side] < leader ? 3 : -1;
            roundScore = Math.max(0, roundScore);
            if (roundScore > 0) streaks[side]++; else streaks[side] = 0;
            scores[side] += roundScore;
            lastChoices[side] = choice;
            lastRoundScores[side] = roundScore;
            lastRoll = roundScore;
            lastEffects.add(new int[]{side, choice, roundScore, scores[side]});
            for (int hidx = history.size() - 1; hidx >= 0; hidx--) {
                int[] h = history.get(hidx);
                if (h.length >= 4 && h[0] == side && h[1] == choice && h[3] == scores[side] - roundScore) {
                    h[2] = roundScore;
                    h[3] = scores[side];
                    break;
                }
            }
        }
        log.add(roundSummary());
        roundIndex++;
        if (roundIndex >= ROUNDS) finish();
        else {
            for (int side = 1; side <= playerCount; side++) choices[side] = -1;
            currentTurn = firstActive();
        }
    }

    private int rollFor(int choice) {
        if (choice == CHOICE_SAFE) return random.nextInt(6) + 1;
        if (choice == CHOICE_RISK) {
            int a = random.nextInt(6) + 1, b = random.nextInt(6) + 1;
            return (a == 1 || b == 1) ? 0 : a + b;
        }
        return random.nextInt(6) + 1;
    }

    private void finish() {
        over = true;
        int best = -1;
        for (int side = 1; side <= playerCount; side++) if (active[side]) best = Math.max(best, scores[side]);
        List<Integer> winners = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) if (active[side] && scores[side] == best) winners.add(side);
        winner = winners.size() == 1 ? winners.get(0) : null;
        log.add(winners.size() == 1 ? nameOf(winners.get(0)) + " 以 " + best + " 分获胜" : "最高分并列，本局平局");
    }

    @Override
    public boolean forfeit(int side) {
        if (side < 1 || side > playerCount || !active[side] || over) return false;
        active[side] = false;
        choices[side] = -1;
        log.add(nameOf(side) + " 离场，退出本局");
        int remaining = 0;
        for (int i = 1; i <= playerCount; i++) if (active[i]) remaining++;
        if (remaining <= 1) {
            over = true;
            winner = remaining == 1 ? firstActive() : null;
        } else if (allActiveSubmitted()) revealRound();
        return true;
    }

    private int firstActive() { for (int i = 1; i <= playerCount; i++) if (active[i]) return i; return 1; }
    private String nameOf(int side) { return names[side] == null ? "第 " + side + " 方" : names[side]; }
    private String choiceName(int choice) { return choice == CHOICE_SAFE ? "稳健" : choice == CHOICE_RISK ? "冒险" : "追击"; }

    private String roundSummary() {
        StringBuilder sb = new StringBuilder("第 ").append(roundIndex + 1).append(" 轮：");
        for (int side = 1; side <= playerCount; side++) if (active[side]) {
            if (sb.length() > 7) sb.append("；");
            sb.append(nameOf(side)).append(" ").append(choiceName(lastChoices[side]))
                    .append(" 掷出 ").append(lastRolls[side])
                    .append("，+ ").append(lastRoundScores[side]).append("（").append(scores[side]).append("分）");
        }
        return sb.toString();
    }

    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }

    @Override
    public List<Integer> getWinningSides() {
        if (!over) return java.util.Collections.emptyList();
        int best = -1; List<Integer> result = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) if (active[side]) best = Math.max(best, scores[side]);
        for (int side = 1; side <= playerCount; side++) if (active[side] && scores[side] == best) result.add(side);
        return result;
    }

    @Override public String getScoreSummary() { return over ? scoreLine() : null; }
    private String scoreLine() { StringBuilder s = new StringBuilder(); for (int i=1;i<=playerCount;i++){ if(i>1)s.append(" · "); s.append(nameOf(i)).append(" ").append(scores[i]); } return s.toString(); }
    @Override public List<int[]> getMoveHistory() { return new ArrayList<>(history); }
    @Override public List<int[]> getLastMoveEffects() { return new ArrayList<>(lastEffects); }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return moveSeq; }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }

    public int getScore(int side) { return side >= 1 && side <= playerCount ? scores[side] : 0; }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "DICE_RACE");
        view.put("round", Math.min(roundIndex + 1, ROUNDS));
        view.put("totalRounds", ROUNDS);
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("winner", winner);
        view.put("revealed", revealed || (hasRevealedRound && allActiveSubmitted()));
        view.put("lastRoll", lastRoll);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("side", side); p.put("name", nameOf(side)); p.put("score", scores[side]);
            p.put("streak", streaks[side]); p.put("active", active[side]);
            p.put("answered", choices[side] >= 0);
            if (revealed || viewerSide == side) p.put("choice", revealed ? lastChoices[side] : choices[side]);
            if (hasRevealedRound) {
                p.put("lastChoice", lastChoices[side]);
                p.put("lastRoundScore", lastRoundScores[side]);
                p.put("lastRoll", lastRolls[side]);
            }
            ps.add(p);
        }
        view.put("players", ps);
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 12), log.size())));
        return view;
    }
}
