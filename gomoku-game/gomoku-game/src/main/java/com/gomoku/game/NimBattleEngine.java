package com.gomoku.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取石子博弈（Nim，三堆版本）— 第三个非棋盘玩法
 *
 * 为什么选它：这个玩法的行动语义是「从第几堆拿几个」，既不是落子坐标，
 * 也不是卡牌下标那种「选第几张」，用来验证 {@link MoveCommand} 的表达力。
 * 编码上用 row = 堆号、col = 拿走的数量，action 保持 -1 —— 不需要新协议字段。
 *
 * 规则：
 * - 三堆石子，初始 {5, 7, 9}
 * - 每回合选择一堆，拿走任意正数颗（不能超过该堆剩余）
 * - 拿走最后一颗石子的人获胜（普通玩法 normal play）
 * - 无法行动（三堆全空）即失败
 *
 * 数学性质：这是公平组合博弈，胜负完全由异或和决定（Bouton 定理）。
 * 异或和 ≠ 0 是必胜态，= 0 是必败态。AI 因此能做「完美」决策，
 * 难度差异只体现在「是否总是走最优」。
 */
public class NimBattleEngine implements GameEngine {

    /** 初始三堆石子 */
    static final int[] INITIAL_PILES = {5, 7, 9};

    static final int STEP_TIME_SECONDS = 30;
    static final int TOTAL_TIME_SECONDS = 300;

    /** 硬上限：理论上不会到，兜底防止异常状态无限对局 */
    static final int MAX_MOVES = 200;

    /** 历史编码：{side, pileIndex, removed} */
    public static final int HIST_SIDE = 0;
    public static final int HIST_PILE = 1;
    public static final int HIST_REMOVED = 2;

    private final int[] piles = new int[INITIAL_PILES.length];
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private int currentTurn = 1;
    private int moveSeq = 0;
    private boolean over = false;
    private Integer winner;
    /** 上一次行动造成的石子变化，供前端做动画/高亮：{pileIndex, remaining} */
    private int lastPile = -1;
    private int lastRemoved = 0;
    /** 双方昵称（下标 1、2 有效），供观战面板显示「轮到谁」 */
    private final String[] playerNames = new String[]{"", "红方", "蓝方"};

    public NimBattleEngine() {
        reset();
    }

    @Override
    public GameType type() {
        return GameType.NIM;
    }

    @Override
    public void reset() {
        System.arraycopy(INITIAL_PILES, 0, piles, 0, piles.length);
        history.clear();
        lastEffects.clear();
        log.clear();
        currentTurn = 1;
        moveSeq = 0;
        over = false;
        winner = null;
        lastPile = -1;
        lastRemoved = 0;
        log.add("取石子博弈开始：三堆共 " + totalStones() + " 颗，拿走最后一颗者胜");
    }

    @Override
    public void setPlayerName(int side, String name) {
        // 之前这里是空实现，导致观战面板只能显示「第 1 方」这种占位文案。
        // 日志也确实用不到昵称，但 view 要给前端用，所以还是存下来。
        if (side >= 1 && side <= 2 && name != null && !name.isEmpty()) {
            playerNames[side] = name;
        }
    }

    /**
     * 行动：row = 堆号，col = 拿走的数量。
     *
     * 注意这里刻意不接受 action 指令 —— 拿几颗是「数量」，不是「选项下标」，
     * 用坐标表达更自然。串线会被拒绝（见 MoveCommandBridgeTest 的约定）。
     */
    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (command == null) return false;
        if (command.hasAction()) return false; // 本玩法不接受行动序号
        if (over) return false;
        if (currentTurn != side) return false;

        int pile = command.getRow();
        int take = command.getCol();
        if (pile < 0 || pile >= piles.length) return false;
        if (take <= 0 || take > piles[pile]) return false;

        lastEffects.clear();
        moveSeq++;
        piles[pile] -= take;
        lastPile = pile;
        lastRemoved = take;

        history.add(new int[]{side, pile, take});
        lastEffects.add(new int[]{pile, piles[pile]});

        log.add(nameOf(side) + " 从第 " + (pile + 1) + " 堆拿走 " + take
                + " 颗（剩 " + piles[pile] + "）");

        // 拿走最后一颗的人获胜
        if (totalStones() == 0) {
            over = true;
            winner = side;
            log.add(nameOf(winner) + " 拿走最后一颗，获胜");
            return true;
        }

        if (history.size() >= MAX_MOVES) {
            over = true;
            winner = null;
            log.add("达到最大手数，判为平局");
            return true;
        }

        currentTurn = 3 - side;
        return true;
    }

    /** 本玩法不接受「停一手」：Nim 里无处可拿就等于输 */
    @Override
    public boolean pass(int side) {
        return false;
    }

    private static String nameOf(int side) {
        return side == 1 ? "红方" : "蓝方";
    }

    /** 剩余石子总数 */
    public int totalStones() {
        int sum = 0;
        for (int p : piles) sum += p;
        return sum;
    }

    /** 各堆剩余石子（副本，供 AI/前端读取） */
    public int[] getPiles() {
        return piles.clone();
    }

    /** 异或和：非 0 为必胜态（Bouton 定理） */
    public int nimSum() {
        int x = 0;
        for (int p : piles) x ^= p;
        return x;
    }

    /** 当前是否还有任何合法行动 */
    public boolean hasAnyMove() {
        return totalStones() > 0;
    }

    @Override
    public int getCurrentTurn() {
        return currentTurn;
    }

    @Override
    public boolean isOver() {
        return over;
    }

    @Override
    public Integer getWinner() {
        return winner;
    }

    @Override
    public String getScoreSummary() {
        if (!over) return null;
        if (winner == null) return "平局（达到手数上限）";
        return nameOf(winner) + " 拿走最后一颗石子获胜";
    }

    @Override
    public List<int[]> getMoveHistory() {
        return new ArrayList<>(history);
    }

    @Override
    public List<int[]> getLastMoveEffects() {
        return new ArrayList<>(lastEffects);
    }

    @Override
    public int getMoveCount() {
        return history.size();
    }

    @Override
    public int getMoveSeq() {
        return moveSeq;
    }

    @Override
    public int getStepTimeSeconds() {
        return STEP_TIME_SECONDS;
    }

    @Override
    public int getTotalTimeSeconds() {
        return TOTAL_TIME_SECONDS;
    }

    /** 石子数量是公开信息，双方看到的一致，无需按视角裁剪 */
    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "NIM");
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("winner", winner);
        view.put("piles", getPiles());
        view.put("total", totalStones());
        view.put("nimSum", nimSum());
        view.put("lastPile", lastPile);
        view.put("lastRemoved", lastRemoved);

        // 双方信息（公开）：名字 + 手里还有多少石子可拿（就是各堆总和，双方一致）
        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= 2; side++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("side", side);
            p.put("name", playerNames[side]);
            players.add(p);
        }
        view.put("players", players);

        // 可拿数量上界直接给前端，避免它自己重复推规则（点第 i 堆最多拿 piles[i] 颗）
        List<Integer> maxTake = new ArrayList<>();
        for (int p : piles) maxTake.add(p);
        view.put("maxTake", maxTake);

        int from = Math.max(0, log.size() - 12);
        view.put("log", new ArrayList<>(log.subList(from, log.size())));
        return view;
    }
}
