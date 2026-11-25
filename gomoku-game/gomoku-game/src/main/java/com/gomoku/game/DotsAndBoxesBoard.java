package com.gomoku.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 5×5 个点、16 个格；坐标奇偶不同是边，奇奇是格，偶偶是点。 */
public class DotsAndBoxesBoard implements BoardGameEngine {
    public static final int SIZE = 9, BOXES = 16, EDGES = 40, MAX_PLAYERS = 4;
    private static final int[][] NEIGHBORS = {{-1,0},{1,0},{0,-1},{0,1}};
    private final int[][] board = new int[SIZE][SIZE];
    private final int[] scores = new int[MAX_PLAYERS + 1];
    private final boolean[] active = new boolean[MAX_PLAYERS + 1];
    private final String[] names = new String[MAX_PLAYERS + 1];
    private final List<int[]> history = new ArrayList<>(), effects = new ArrayList<>();
    private List<Integer> winners = Collections.emptyList();
    private int playerCount = 2, turn = 1, moveCount, lastCaptured, lastMover;
    private boolean over;

    public DotsAndBoxesBoard() { reset(); }
    @Override public GameType type() { return GameType.DOTS_BOXES; }
    @Override public int getRows() { return SIZE; }
    @Override public int getCols() { return SIZE; }
    @Override public int getCurrentTurn() { return turn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winners.size() == 1 ? winners.get(0) : null; }
    @Override public List<Integer> getWinningSides() { return new ArrayList<>(winners); }
    @Override public int getMoveCount() { return moveCount; }
    @Override public int getMoveSeq() { return moveCount; }
    @Override public int getStepTimeSeconds() { return 30; }
    @Override public int getTotalTimeSeconds() { return 600; }
    @Override public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("点格棋支持 2–4 人");
        playerCount = count;
    }
    @Override public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= MAX_PLAYERS && name != null && !name.isEmpty()) names[side] = name;
    }
    @Override public void reset() {
        for (int[] row : board) Arrays.fill(row, 0);
        Arrays.fill(scores, 0); Arrays.fill(active, false);
        for (int side = 1; side <= playerCount; side++) active[side] = true;
        history.clear(); effects.clear(); winners = Collections.emptyList();
        turn = 1; moveCount = 0; lastCaptured = 0; lastMover = 0; over = false;
    }
    public static boolean isEdge(int row, int col) {
        return row >= 0 && row < SIZE && col >= 0 && col < SIZE && (row + col) % 2 == 1;
    }
    public boolean isLegal(int row, int col) { return !over && isEdge(row, col) && board[row][col] == 0; }
    @Override public boolean applyMove(MoveCommand command, int side) {
        return command != null && !command.hasAttack() && BoardGameEngine.super.applyMove(command,side);
    }
    public boolean isActive(int side) { return side >= 1 && side <= playerCount && active[side]; }
    public int getPlayerCount() { return playerCount; }
    public int getScore(int side) { return side >= 1 && side <= playerCount ? scores[side] : 0; }
    public List<int[]> legalMoves() {
        List<int[]> moves = new ArrayList<>();
        for (int row = 0; row < SIZE; row++) for (int col = 0; col < SIZE; col++)
            if (isLegal(row, col)) moves.add(new int[]{row, col});
        return moves;
    }
    private int nextActive(int side) {
        for (int offset = 1; offset <= playerCount; offset++) {
            int next = (side - 1 + offset) % playerCount + 1;
            if (active[next]) return next;
        }
        return side;
    }
    static int sidesAround(int[][] cells, int row, int col) {
        return (cells[row - 1][col] > 0 ? 1 : 0) + (cells[row + 1][col] > 0 ? 1 : 0)
                + (cells[row][col - 1] > 0 ? 1 : 0) + (cells[row][col + 1] > 0 ? 1 : 0);
    }
    /** 两个相邻格中，拥有 n 条已画边的未占格数，供 AI 排序。 */
    int adjacentWithSides(int row, int col, int n) {
        int count = 0;
        for (int[] delta : NEIGHBORS) {
            int r = row + delta[0], c = col + delta[1];
            if (r > 0 && r < SIZE - 1 && c > 0 && c < SIZE - 1 && r % 2 == 1 && c % 2 == 1
                    && board[r][c] == 0 && sidesAround(board, r, c) == n) count++;
        }
        return count;
    }
    @Override public boolean placeStone(int row, int col, int side) {
        if (side != turn || !isActive(side) || !isLegal(row, col)) return false;
        board[row][col] = side; effects.clear(); effects.add(new int[]{row,col,side});
        lastCaptured = 0; lastMover = side;
        for (int[] delta : NEIGHBORS) {
            int r = row + delta[0], c = col + delta[1];
            if (r > 0 && r < SIZE - 1 && c > 0 && c < SIZE - 1 && r % 2 == 1 && c % 2 == 1
                    && board[r][c] == 0 && sidesAround(board, r, c) == 4) {
                board[r][c] = side; scores[side]++; lastCaptured++;
                effects.add(new int[]{r,c,side});
            }
        }
        history.add(new int[]{row,col,side,lastCaptured,scores[side]}); moveCount++;
        if (moveCount == EDGES) finish();
        else if (lastCaptured == 0) turn = nextActive(side);
        return true;
    }
    private void finish() {
        over = true; int best = -1; List<Integer> result = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) if (active[side]) best = Math.max(best, scores[side]);
        for (int side = 1; side <= playerCount; side++) if (active[side] && scores[side] == best) result.add(side);
        winners = playerCount == 2 && result.size() == 2 ? Collections.emptyList() : result;
    }
    @Override public boolean forfeit(int side) {
        if (over || !isActive(side)) return false;
        active[side] = false; effects.clear(); lastCaptured = 0; lastMover = 0;
        List<Integer> remaining = new ArrayList<>();
        for (int next = 1; next <= playerCount; next++) if (active[next]) remaining.add(next);
        // 已画边和已占格保留；离场者不再行动，也不参与最终排名。
        if (remaining.size() <= 1) { over = true; winners = remaining; }
        else if (turn == side) turn = nextActive(side);
        return true;
    }
    private String name(int side) { return names[side] == null ? "第 " + side + " 方" : names[side]; }
    @Override public String getScoreSummary() {
        if (!over) return null;
        StringBuilder text = new StringBuilder("围格得分：");
        for (int side = 1; side <= playerCount; side++) {
            if (side > 1) text.append(" · ");
            text.append(name(side)).append(' ').append(scores[side]).append(" 格").append(active[side] ? "" : "（已退出）");
        }
        return text.toString();
    }
    @Override public int[][] getBoardSnapshot() {
        int[][] copy = new int[SIZE][]; for (int row = 0; row < SIZE; row++) copy[row] = board[row].clone(); return copy;
    }
    private static List<int[]> snapshot(List<int[]> source) {
        List<int[]> copy = new ArrayList<>(); for (int[] row : source) copy.add(row.clone()); return copy;
    }
    @Override public List<int[]> getMoveHistory() { return snapshot(history); }
    @Override public List<int[]> getLastMoveEffects() { return snapshot(effects); }
    DotsAndBoxesBoard copyForSearch() {
        DotsAndBoxesBoard copy = new DotsAndBoxesBoard(); copy.playerCount = playerCount;
        for (int row = 0; row < SIZE; row++) System.arraycopy(board[row],0,copy.board[row],0,SIZE);
        System.arraycopy(scores,0,copy.scores,0,scores.length);
        System.arraycopy(active,0,copy.active,0,active.length);
        copy.turn = turn; copy.moveCount = moveCount; copy.over = over; copy.winners = new ArrayList<>(winners);
        return copy;
    }
    @Override public Object getViewState(int viewerSide) {
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("kind",type().name()); view.put("turn",turn); view.put("over",over);
        view.put("playerCount",playerCount); view.put("remainingEdges",EDGES-moveCount);
        view.put("remainingBoxes",BOXES-Arrays.stream(scores).sum());
        view.put("lastCaptured",lastCaptured); view.put("lastMover",lastMover);
        view.put("continued",!over && lastCaptured > 0 && turn == lastMover);
        view.put("legalMoves",legalMoves());
        List<Map<String,Object>> players = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            Map<String,Object> player = new LinkedHashMap<>();
            player.put("side",side); player.put("name",name(side)); player.put("score",scores[side]);
            player.put("active",active[side]); players.add(player);
        }
        view.put("players",players); return view;
    }
}
