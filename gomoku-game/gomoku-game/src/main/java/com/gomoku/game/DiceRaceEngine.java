package com.gomoku.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 六轮同时行动竞速：先暗选策略，冒险成功后再暗选收分或加掷。 */
public class DiceRaceEngine implements GameEngine {
    public static final int ROUNDS = 6;
    public static final int CHOICE_SAFE = 0;
    public static final int CHOICE_RISK = 1;
    public static final int CHOICE_CHASE = 2;
    public static final int CHOICES = 3;
    public static final int ACTION_BANK = 3;
    public static final int ACTION_PUSH = 4;
    public static final int ACTION_SHIELD = 5;
    static final int STEP_TIME_SECONDS = 25;
    static final int TOTAL_TIME_SECONDS = 240;
    static final int MAX_PLAYERS = 8;
    /** 前四列兼容旧回放；新增轮次、首骰两颗、加掷点数。每个有效行动一条。 */
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
    private final int[] decisions = new int[MAX_PLAYERS + 1];
    private final int[] pots = new int[MAX_PLAYERS + 1];
    private final int[][] dice = new int[MAX_PLAYERS + 1][2];
    private final int[] pushes = new int[MAX_PLAYERS + 1];
    private final int[] choiceHistory = new int[MAX_PLAYERS + 1];
    private final int[] decisionHistory = new int[MAX_PLAYERS + 1];
    private final int[] lastChoices = new int[MAX_PLAYERS + 1];
    private final int[] lastDecisions = new int[MAX_PLAYERS + 1];
    private final int[] lastRoundScores = new int[MAX_PLAYERS + 1];
    private final int[][] lastDice = new int[MAX_PLAYERS + 1][2];
    private final int[] lastPushes = new int[MAX_PLAYERS + 1];
    private final boolean[] active = new boolean[MAX_PLAYERS + 1];
    private final boolean[] shields = new boolean[MAX_PLAYERS + 1];
    private int playerCount = 2;
    private int currentTurn = 1;
    private int roundIndex;
    private boolean over;
    private boolean pressing;
    private boolean revealed;
    private Integer winner;

    public DiceRaceEngine() { this(new Random()); }
    DiceRaceEngine(Random random) { this.random = random; reset(); }
    @Override public GameType type() { return GameType.DICE_RACE; }
    @Override public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("骰子竞速支持 2–8 人");
        playerCount = count;
    }
    @Override public void reset() {
        history.clear(); lastEffects.clear(); log.clear();
        roundIndex = 0; currentTurn = 1; over = false; pressing = false; revealed = false; winner = null;
        Arrays.fill(scores, 0); Arrays.fill(streaks, 0);
        Arrays.fill(lastChoices, -1); Arrays.fill(lastDecisions, -1);
        Arrays.fill(lastRoundScores, 0); Arrays.fill(lastPushes, 0);
        for (int side = 1; side <= MAX_PLAYERS; side++) {
            active[side] = side <= playerCount; shields[side] = true;
            Arrays.fill(lastDice[side], 0);
        }
        clearRound();
        log.add("骰子竞速开始：六轮竞速，冒险成功可收分或加掷，每局一枚护盾");
    }
    private void clearRound() {
        Arrays.fill(choices, -1); Arrays.fill(decisions, -1);
        Arrays.fill(pots, 0); Arrays.fill(pushes, 0);
        Arrays.fill(choiceHistory, -1); Arrays.fill(decisionHistory, -1);
        for (int[] roll : dice) Arrays.fill(roll, 0);
    }
    @Override public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= MAX_PLAYERS && name != null && !name.isEmpty()) names[side] = name;
    }
    @Override public boolean autoEndsTurn() { return false; }
    @Override public boolean simultaneousTurn() { return true; }
    // 两个独立行动窗口，跨窗口才重新给尚需决策的席位步时。
    @Override public int getActionRound() { return roundIndex * 2 + (pressing ? 1 : 0); }
    public boolean isPressing() { return pressing; }
    public int getRound() { return Math.min(roundIndex + 1, ROUNDS); }
    @Override public boolean isAwaitingAction(int side) {
        return !over && side >= 1 && side <= playerCount && active[side]
                && (pressing ? choices[side] == CHOICE_RISK && pots[side] > 0 && decisions[side] < 0
                : choices[side] < 0);
    }
    public List<Integer> legalActions(int side) {
        if (!isAwaitingAction(side)) return Collections.emptyList();
        if (!pressing) return Arrays.asList(CHOICE_SAFE, CHOICE_RISK, CHOICE_CHASE);
        return shields[side] ? Arrays.asList(ACTION_BANK, ACTION_PUSH, ACTION_SHIELD)
                : Arrays.asList(ACTION_BANK, ACTION_PUSH);
    }
    @Override public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction() || !legalActions(side).contains(command.getAction())) return false;
        int action = command.getAction();
        lastEffects.clear();
        history.add(new int[]{side, action, 0, scores[side], roundIndex + 1, 0, 0, 0});
        if (pressing) {
            decisions[side] = action; decisionHistory[side] = history.size() - 1;
            log.add(nameOf(side) + " 已提交收分/加掷决定");
        } else {
            revealed = false; choices[side] = action; choiceHistory[side] = history.size() - 1;
            log.add(nameOf(side) + " 已提交策略");
        }
        if (firstAwaiting() == 0) {
            if (pressing) settleRound(); else revealRolls();
        } else currentTurn = firstAwaiting();
        return true;
    }
    private int firstAwaiting() {
        for (int side = 1; side <= playerCount; side++) if (isAwaitingAction(side)) return side;
        return 0;
    }
    private void revealRolls() {
        int leader = 0;
        for (int side = 1; side <= playerCount; side++) if (active[side]) leader = Math.max(leader, scores[side]);
        for (int side = 1; side <= playerCount; side++) if (active[side]) {
            dice[side][0] = roll();
            if (choices[side] == CHOICE_RISK) {
                dice[side][1] = roll();
                pots[side] = dice[side][0] == 1 || dice[side][1] == 1 ? 0 : dice[side][0] + dice[side][1];
            } else {
                pots[side] = Math.max(0, dice[side][0] + (choices[side] == CHOICE_CHASE
                        ? scores[side] < leader ? 3 : -1 : 0));
            }
            int[] record = history.get(choiceHistory[side]);
            record[5] = dice[side][0]; record[6] = dice[side][1];
        }
        pressing = true;
        currentTurn = firstAwaiting();
        if (currentTurn == 0) settleRound();
        else log.add("第 " + (roundIndex + 1) + " 轮首骰揭晓：冒险成功者暗选收分或加掷；护盾只保护加掷");
    }
    private void settleRound() {
        for (int side = 1; side <= playerCount; side++) if (active[side]) {
            int points = pots[side];
            int action = decisions[side];
            if (action == ACTION_PUSH || action == ACTION_SHIELD) {
                pushes[side] = roll();
                if (action == ACTION_SHIELD) shields[side] = false;
                if (pushes[side] == 1) { if (action != ACTION_SHIELD) points = 0; }
                else points += pushes[side];
            }
            scores[side] += points;
            streaks[side] = points > 0 ? streaks[side] + 1 : 0;
            lastChoices[side] = choices[side]; lastDecisions[side] = action;
            lastRoundScores[side] = points; lastDice[side] = dice[side].clone(); lastPushes[side] = pushes[side];
            int[] record = history.get(action >= ACTION_BANK ? decisionHistory[side] : choiceHistory[side]);
            record[2] = points; record[3] = scores[side]; record[7] = pushes[side];
            lastEffects.add(record.clone());
        }
        log.add(roundSummary());
        roundIndex++; pressing = false; revealed = true;
        if (roundIndex >= ROUNDS) finish();
        else { clearRound(); currentTurn = firstAwaiting(); }
    }
    private int roll() { return random.nextInt(6) + 1; }
    private void finish() {
        over = true;
        List<Integer> winners = getWinningSides();
        winner = winners.size() == 1 ? winners.get(0) : null;
        log.add(winner == null ? "最高分并列，本局平局" : nameOf(winner) + " 以 " + scores[winner] + " 分获胜");
    }
    @Override public boolean forfeit(int side) {
        if (side < 1 || side > playerCount || !active[side] || over) return false;
        active[side] = false;
        log.add(nameOf(side) + " 离场，退出本局");
        int remaining = 0;
        for (int i = 1; i <= playerCount; i++) if (active[i]) { remaining++; winner = i; }
        if (remaining <= 1) { over = true; if (remaining == 0) winner = null; }
        else {
            winner = null;
            currentTurn = firstAwaiting();
            if (currentTurn == 0) { if (pressing) settleRound(); else revealRolls(); }
        }
        return true;
    }
    private String nameOf(int side) { return names[side] == null ? "第 " + side + " 方" : names[side]; }
    private String choiceName(int choice) { return choice == CHOICE_SAFE ? "稳健" : choice == CHOICE_RISK ? "冒险" : "追击"; }
    private String roundSummary() {
        StringBuilder line = new StringBuilder("第 " + (roundIndex + 1) + " 轮结算：");
        for (int side = 1; side <= playerCount; side++) if (active[side]) {
            if (line.charAt(line.length() - 1) != '：') line.append("；");
            line.append(nameOf(side)).append(' ').append(choiceName(lastChoices[side])).append(' ').append(lastDice[side][0]);
            if (lastDice[side][1] > 0) line.append('+').append(lastDice[side][1]);
            if (lastDecisions[side] == ACTION_BANK) line.append(" 收分");
            else if (lastDecisions[side] >= ACTION_PUSH) {
                line.append(lastDecisions[side] == ACTION_SHIELD ? " 护盾加掷 " : " 加掷 ").append(lastPushes[side]);
                if (lastPushes[side] == 1) line.append(lastDecisions[side] == ACTION_SHIELD ? "（护盾保分）" : "（爆掉）");
            }
            line.append("，+").append(lastRoundScores[side]).append("（").append(scores[side]).append(" 分）");
        }
        return line.toString();
    }
    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }
    @Override public List<Integer> getWinningSides() {
        if (!over) return Collections.emptyList();
        int best = -1; List<Integer> result = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) if (active[side]) best = Math.max(best, scores[side]);
        for (int side = 1; side <= playerCount; side++) if (active[side] && scores[side] == best) result.add(side);
        return result;
    }
    @Override public String getScoreSummary() {
        if (!over) return null;
        StringBuilder summary = new StringBuilder();
        for (int side = 1; side <= playerCount; side++) {
            if (side > 1) summary.append(" · ");
            summary.append(nameOf(side)).append(' ').append(scores[side]);
        }
        return summary.toString();
    }
    private List<int[]> snapshot(List<int[]> source) {
        List<int[]> copy = new ArrayList<>();
        for (int[] row : source) copy.add(row.clone());
        return copy;
    }
    @Override public List<int[]> getMoveHistory() { return snapshot(history); }
    @Override public List<int[]> getLastMoveEffects() { return snapshot(lastEffects); }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return history.size(); }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }
    public int getScore(int side) { return side >= 1 && side <= playerCount ? scores[side] : 0; }
    public int getPendingScore(int side) { return pressing && side >= 1 && side <= playerCount && active[side] ? pots[side] : 0; }
    @Override public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "DICE_RACE"); view.put("round", getRound()); view.put("totalRounds", ROUNDS);
        view.put("phase", over ? "OVER" : pressing ? "PRESS" : "CHOOSE");
        view.put("turn", currentTurn); view.put("over", over); view.put("winner", winner);
        view.put("revealed", revealed); view.put("lastRound", roundIndex);
        view.put("legalActions", new ArrayList<>(legalActions(viewerSide)));
        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("side", side); player.put("name", nameOf(side)); player.put("score", scores[side]);
            player.put("streak", streaks[side]); player.put("active", active[side]);
            player.put("answered", !isAwaitingAction(side)); player.put("shieldAvailable", shields[side]);
            if (viewerSide == side || pressing || over) player.put("choice", choices[side]);
            if (pressing) {
                player.put("pendingScore", pots[side]); player.put("dice", dice[side].clone());
                if (viewerSide == side) player.put("decision", decisions[side]);
            }
            if (roundIndex > 0) {
                player.put("lastChoice", lastChoices[side]); player.put("lastDecision", lastDecisions[side]);
                player.put("lastRoundScore", lastRoundScores[side]); player.put("lastRoll", lastDice[side][0] + lastDice[side][1]);
                player.put("lastDice", lastDice[side].clone()); player.put("lastPush", lastPushes[side]);
            }
            players.add(player);
        }
        view.put("players", players);
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 12), log.size())));
        return view;
    }
}
