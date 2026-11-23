package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 西洋跳棋（8×8，国际跳棋简化规则）。
 *
 * 盘面编码与象棋一致：cell = side * 10 + 棋子类型（0 = 空）。
 *   side 1 = 下方（深色格，先行），side 2 = 上方。
 *   类型：1 = 兵，2 = 王。
 *
 * 规则：
 * - 兵只能斜向前进一格；王可以斜向任意方向前进一格。
 * - 吃子必须跳过相邻的敌方棋子，落到其正后方空格；吃子后落在底线升王。
 * - 只要存在吃子必须吃；连吃时同一方继续行动，直到不能继续吃为止。
 * - 连续 80 个半回合没有吃子或升王判和，避免残余王在线上无限往返。
 * - 一方无子或无合法走法即判负。
 */
public class CheckersBoard implements BoardGameEngine {

    public static final int ROWS = 8;
    public static final int COLS = 8;
    public static final int MAN = 1;
    public static final int KING = 2;
    public static final int DRAW_AFTER_QUIET_TURNS = 80;

    private static final int STEP_TIME_SECONDS = 45;
    private static final int TOTAL_TIME_SECONDS = 480;
    private static final int[][] DIAG = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    private final int[][] board = new int[ROWS][COLS];
    private final List<int[]> moveHistory = new ArrayList<>();
    private List<int[]> lastEffects = new ArrayList<>();
    private int currentTurn = 1;
    private int moveCount;
    private int moveSeq;
    private boolean gameOver;
    private Integer winner;
    private boolean draw;
    private int quietTurns;
    /** 连吃进行中：当前必须继续吃子的位置 */
    private int chainRow = -1;
    private int chainCol = -1;

    public CheckersBoard() { reset(); }

    @Override public GameType type() { return GameType.CHECKERS; }
    @Override public int getRows() { return ROWS; }
    @Override public int getCols() { return COLS; }

    @Override
    public void reset() {
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) board[r][c] = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < COLS; c++) if (isDark(r, c)) board[r][c] = 2 * 10 + MAN;
        }
        for (int r = 5; r < 8; r++) {
            for (int c = 0; c < COLS; c++) if (isDark(r, c)) board[r][c] = 1 * 10 + MAN;
        }
        currentTurn = 1;
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
        draw = false;
        quietTurns = 0;
        chainRow = chainCol = -1;
        // 若开局方无合法走法（理论上不会发生），保持可继续开局的状态。
    }

    private static boolean inBoard(int r, int c) { return r >= 0 && r < ROWS && c >= 0 && c < COLS; }
    private static boolean isDark(int r, int c) { return (r + c) % 2 == 1; }
    private static int sideOf(int piece) { return piece / 10; }
    private static int typeOf(int piece) { return piece % 10; }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        if (!inBoard(row, col)) return false;
        int v = board[row][col];
        return v != 0 && sideOf(v) == side;
    }

    /** 当前必须移动的起点；null 表示可以自由选择任何己方棋子。 */
    public int[] getChainFrom() {
        return chainRow >= 0 ? new int[]{chainRow, chainCol} : null;
    }

    /** 某格的简单走法（不含吃子）。 */
    static List<int[]> simpleMoves(int[][] b, int fromRow, int fromCol) {
        List<int[]> moves = new ArrayList<>();
        if (!inBoard(fromRow, fromCol)) return moves;
        int piece = b[fromRow][fromCol];
        if (piece == 0) return moves;
        int side = sideOf(piece);
        int type = typeOf(piece);
        for (int[] d : DIAG) {
            if (type == MAN && d[0] != (side == 1 ? -1 : 1)) continue;
            int r = fromRow + d[0], c = fromCol + d[1];
            if (inBoard(r, c) && b[r][c] == 0) moves.add(new int[]{r, c});
        }
        return moves;
    }

    /** 某格可吃的目标（含被吃棋子坐标）。 */
    static List<int[]> captureMoves(int[][] b, int fromRow, int fromCol) {
        List<int[]> moves = new ArrayList<>();
        if (!inBoard(fromRow, fromCol)) return moves;
        int piece = b[fromRow][fromCol];
        if (piece == 0) return moves;
        int side = sideOf(piece);
        int type = typeOf(piece);
        for (int[] d : DIAG) {
            if (type == MAN && d[0] != (side == 1 ? -1 : 1)) continue;
            int midR = fromRow + d[0], midC = fromCol + d[1];
            int toR = fromRow + d[0] * 2, toC = fromCol + d[1] * 2;
            if (!inBoard(toR, toC) || b[toR][toC] != 0) continue;
            int mid = b[midR][midC];
            if (mid != 0 && sideOf(mid) != side) moves.add(new int[]{toR, toC, midR, midC});
        }
        return moves;
    }

    /** 当前行动方是否至少有一个吃子机会。 */
    public boolean hasCapture(int side) {
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (board[r][c] != 0 && sideOf(board[r][c]) == side && !captureMoves(board, r, c).isEmpty()) return true;
        }
        return false;
    }

    /** 当前行动方所有合法走法：{fromRow, fromCol, toRow, toCol, capture}。 */
    public List<int[]> legalMoves(int side) {
        List<int[]> result = new ArrayList<>();
        if (gameOver || side != currentTurn) return result;
        if (chainRow >= 0) {
            for (int[] m : captureMoves(board, chainRow, chainCol)) {
                result.add(new int[]{chainRow, chainCol, m[0], m[1], 1});
            }
            return result;
        }
        boolean mustCapture = hasCapture(side);
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (board[r][c] == 0 || sideOf(board[r][c]) != side) continue;
            for (int[] m : captureMoves(board, r, c)) result.add(new int[]{r, c, m[0], m[1], 1});
            if (!mustCapture) for (int[] m : simpleMoves(board, r, c)) result.add(new int[]{r, c, m[0], m[1], 0});
        }
        return result;
    }

    public boolean isLegalMove(int fromRow, int fromCol, int toRow, int toCol, int side) {
        for (int[] m : legalMoves(side)) {
            if (m[0] == fromRow && m[1] == fromCol && m[2] == toRow && m[3] == toCol) return true;
        }
        return false;
    }

    @Override
    public boolean placeStone(int fromRow, int fromCol, int toRow, int toCol, int side) {
        if (gameOver || side != currentTurn) return false;
        if (!isLegalMove(fromRow, fromCol, toRow, toCol, side)) return false;
        int piece = board[fromRow][fromCol];
        boolean capture = Math.abs(toRow - fromRow) == 2;
        List<int[]> effects = new ArrayList<>();
        board[fromRow][fromCol] = 0;
        effects.add(new int[]{fromRow, fromCol, 0});
        if (capture) {
            int midR = (fromRow + toRow) / 2, midC = (fromCol + toCol) / 2;
            board[midR][midC] = 0;
            effects.add(new int[]{midR, midC, 0});
        }
        boolean promoted = false;
        if (typeOf(piece) == MAN && ((side == 1 && toRow == 0) || (side == 2 && toRow == ROWS - 1))) {
            piece = side * 10 + KING;
            promoted = true;
        }
        board[toRow][toCol] = piece;
        effects.add(new int[]{toRow, toCol, piece});
        lastEffects = effects;
        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{toRow, toCol, side, fromRow, fromCol, promoted ? 1 : 0});
        if (capture || promoted) quietTurns = 0;
        else quietTurns++;

        // 连吃：升王后即使还能跳也不再继续；否则同一子继续吃。
        if (capture && !promoted && !captureMoves(board, toRow, toCol).isEmpty()) {
            chainRow = toRow;
            chainCol = toCol;
        } else {
            chainRow = chainCol = -1;
            if (quietTurns >= DRAW_AFTER_QUIET_TURNS) {
                gameOver = true;
                draw = true;
                winner = null;
            } else {
                advanceTurn();
            }
        }
        return true;
    }

    @Override
    public boolean placeStone(int row, int col, int side) {
        // 跳棋必须提供起点
        return false;
    }

    private void advanceTurn() {
        currentTurn = currentTurn == 1 ? 2 : 1;
        if (legalMoves(currentTurn).isEmpty()) {
            gameOver = true;
            winner = currentTurn == 1 ? 2 : 1;
        }
    }

    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return gameOver; }
    @Override public Integer getWinner() { return winner; }

    @Override
    public String getScoreSummary() {
        if (!gameOver) return null;
        int[] counts = pieceCounts();
        return draw
            ? "连续 " + DRAW_AFTER_QUIET_TURNS + " 个半回合无吃子或升王 · 和棋"
            : "红方 " + counts[0] + " 子 · 黑方 " + counts[1] + " 子";
    }

    @Override
    public List<Integer> getDrawingSides() {
        return draw ? java.util.Arrays.asList(1, 2) : java.util.Collections.emptyList();
    }

    /** {side1 子数, side2 子数}，含兵与王。 */
    private int[] pieceCounts() {
        int a = 0, b = 0;
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            int v = board[r][c];
            if (v == 0) continue;
            if (sideOf(v) == 1) a++; else if (sideOf(v) == 2) b++;
        }
        return new int[]{a, b};
    }

    @Override public List<int[]> getMoveHistory() { return Collections.unmodifiableList(moveHistory); }
    @Override public List<int[]> getLastMoveEffects() { return lastEffects; }
    @Override public int getMoveCount() { return moveCount; }
    @Override public int getMoveSeq() { return moveSeq; }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }

    @Override
    public int[][] getBoardSnapshot() {
        int[][] copy = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) copy[r] = board[r].clone();
        return copy;
    }

    /** 供前端/AI 复用的当前合法走法（深拷贝）。 */
    public List<int[]> getLegalMovesFor(int side) {
        return new ArrayList<>(legalMoves(side));
    }

    /**
     * 公开视图：当前行动方的全部合法走法。
     *
     * 跳棋没有隐藏信息，合法走法本身就是公开规则的一部分；下发给前端后，
     * 玩家能直接看到「哪些棋子必须吃、可以落到哪」，而不是靠反复试错。
     */
    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("turn", currentTurn);
        view.put("chainFrom", chainRow >= 0
                ? new int[]{chainRow, chainCol} : null);
        List<Map<String, Object>> moves = new ArrayList<>();
        for (int[] move : legalMoves(currentTurn)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromRow", move[0]);
            item.put("fromCol", move[1]);
            item.put("toRow", move[2]);
            item.put("toCol", move[3]);
            item.put("capture", move[4] > 0);
            moves.add(item);
        }
        view.put("legalMoves", moves);
        view.put("quietTurns", quietTurns);
        view.put("drawAfterQuietTurns", DRAW_AFTER_QUIET_TURNS);
        view.put("over", gameOver);
        return view;
    }

    /** 测试辅助：清空盘面并回到红方先手的初始回合状态，便于构造残局。 */
    void resetTurnForTest() {
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) board[r][c] = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        currentTurn = 1;
        moveCount = 0;
        moveSeq = 0;
        gameOver = false;
        winner = null;
        draw = false;
        quietTurns = 0;
        chainRow = chainCol = -1;
    }

    /** 测试辅助：强制指定行动方（用于验证升王后的走法）。 */
    void forceTurnForTest(int side) {
        currentTurn = side;
        chainRow = chainCol = -1;
    }

    /** 测试辅助：模拟已积累的安静半回合数。 */
    void setQuietTurnsForTest(int value) {
        quietTurns = value;
    }
}
