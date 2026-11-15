package com.gomoku.game;

import java.util.*;

/** 2–8 人、五轮同时暗选的比大小。每人独立持有 1–10，最大者得一分，并列都得分。 */
public class HighCardEngine implements GameEngine {
    public static final int ACE_ACTION = 100;
    private int count = 4, turn = 1, round = 1, seq;
    private boolean over;
    private int[] scores, choices, lastChoices, effectiveChoices, lastEffectiveChoices;
    private boolean[] aceUsed, lastAceChoices;
    private boolean[] active;
    private String[] names;
    private List<Set<Integer>> hands;
    private final List<int[]> history = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    public HighCardEngine() { reset(); }
    public GameType type() { return GameType.HIGH_CARD; }
    public void configurePlayers(int count) { this.count = type().resolvePlayerCount(count); }
    public void reset() {
        turn = 1; round = 1; seq = 0; over = false;
        scores = new int[count + 1]; choices = new int[count + 1]; lastChoices = null; effectiveChoices = new int[count + 1]; lastEffectiveChoices = null;
        aceUsed = new boolean[count + 1]; lastAceChoices = null;
        active = new boolean[count + 1]; names = new String[count + 1];
        Arrays.fill(choices, -1); Arrays.fill(active, true); active[0] = false;
        hands = new ArrayList<>(); hands.add(Collections.emptySet());
        for (int side = 1; side <= count; side++) {
            names[side] = "玩家 " + side;
            Set<Integer> hand = new LinkedHashSet<>();
            for (int card = 1; card <= 10; card++) hand.add(card);
            hands.add(hand);
        }
        history.clear(); log.clear(); log.add("五轮比大小：同时暗选，每轮最大者得一分，并列均得分");
    }
    public void setPlayerName(int side, String name) { if (side > 0 && side <= count) names[side] = name; }
    public boolean autoEndsTurn() { return false; }
    public boolean simultaneousTurn() { return true; }
    public boolean isAwaitingAction(int side) {
        return !over && side >= 1 && side <= count && active[side] && choices[side] < 0;
    }
    public int getActionRound() { return round; }
    public boolean applyMove(MoveCommand command, int side) {
        if (over || side < 1 || side > count || !active[side] || choices[side] >= 0
                || command == null || !command.hasAction()) return false;
        int card = command.getAction();
        boolean ace = card == ACE_ACTION;
        if (ace) {
            if (aceUsed[side] || !command.hasTarget() || command.getTargetSide() != 1) return false;
            card = command.getTargetIndex();
            if (card < 1 || card > 10) return false;
        }
        if (!hands.get(side).remove(card)) return false;
        if (ace) aceUsed[side] = true;
        choices[side] = card; effectiveChoices[side] = ace ? card * 2 : card; seq++;
        history.add(new int[]{side, card, round, ace ? 1 : 0});
        finishRoundIfReady();
        return true;
    }
    private void finishRoundIfReady() {
        for (int side = 1; side <= count; side++) {
            if (active[side] && choices[side] < 0) { turn = side; return; }
        }
        int best = -1;
        for (int side = 1; side <= count; side++) if (active[side]) best = Math.max(best, effectiveChoices[side]);
        List<String> winners = new ArrayList<>();
        int roundPoints = round == 5 ? 2 : 1;
        for (int side = 1; side <= count; side++) if (active[side] && effectiveChoices[side] == best) {
            scores[side] += roundPoints; winners.add(names[side]);
        }
        lastChoices = choices.clone(); lastEffectiveChoices = effectiveChoices.clone(); lastAceChoices = new boolean[count + 1];
        for (int side = 1; side <= count; side++) lastAceChoices[side] = effectiveChoices[side] == choices[side] * 2;
        log.add("第 " + round + " 轮：" + String.join("、", winners) + " 得 " + roundPoints + " 分（最大牌 " + best + "）");
        if (round == 5) { over = true; turn = 0; }
        else { round++; Arrays.fill(choices, -1); Arrays.fill(effectiveChoices, -1); turn = firstActive(); }
    }
    private int firstActive() { for (int i = 1; i <= count; i++) if (active[i]) return i; return 0; }
    public boolean forfeit(int side) {
        if (over || side < 1 || side > count || !active[side]) return false;
        active[side] = false; choices[side] = -1;
        history.add(new int[]{side, -1, round}); seq++;
        log.add(names[side] + " 退出本局");
        int remaining = 0;
        for (int i = 1; i <= count; i++) if (active[i]) remaining++;
        if (remaining <= 1) { over = true; turn = 0; }
        else finishRoundIfReady();
        return true;
    }
    public List<Integer> getWinningSides() {
        List<Integer> winners = new ArrayList<>();
        if (!over) return winners;
        int best = Integer.MIN_VALUE;
        for (int i = 1; i <= count; i++) if (active[i]) best = Math.max(best, scores[i]);
        for (int i = 1; i <= count; i++) if (active[i] && scores[i] == best) winners.add(i);
        return winners;
    }
    public Integer getWinner() { List<Integer> winners = getWinningSides(); return winners.size() == 1 ? winners.get(0) : null; }
    public int getCurrentTurn() { return turn; }
    public boolean isOver() { return over; }
    public String getScoreSummary() {
        if (!over) return null;
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add(names[i] + " " + scores[i] + " 分" + (active[i] ? "" : "（退出）"));
        return String.join(" · ", result);
    }
    public List<int[]> getMoveHistory() { return new ArrayList<>(history); }
    public List<int[]> getLastMoveEffects() { return Collections.emptyList(); }
    public int getMoveCount() { return history.size(); }
    public int getMoveSeq() { return seq; }
    public int getStepTimeSeconds() { return 30; }
    public int getTotalTimeSeconds() { return 300; }
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "HIGH_CARD"); view.put("turn", turn); view.put("round", round); view.put("roundPoints", round == 5 ? 2 : 1);
        view.put("over", over); view.put("winningSides", getWinningSides());
        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= count; side++) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("side", side); player.put("name", names[side]); player.put("score", scores[side]);
            player.put("active", active[side]); player.put("answered", choices[side] >= 0);
            player.put("cardsLeft", hands.get(side).size());
            player.put("aceAvailable", !aceUsed[side]);
            // 对手的余牌同样属于隐藏信息，不能只隐藏当前出牌。
            if (viewerSide == side) { player.put("hand", new ArrayList<>(hands.get(side))); player.put("choice", choices[side]); player.put("aceChoice", choices[side] > 0 && effectiveChoices[side] == choices[side] * 2); }
            if (lastChoices != null) { player.put("lastChoice", lastChoices[side]); player.put("lastAce", lastAceChoices[side]); player.put("lastEffectiveChoice", lastEffectiveChoices[side]); }
            players.add(player);
        }
        view.put("players", players); view.put("log", new ArrayList<>(log));
        return view;
    }
}
