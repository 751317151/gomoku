package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中国象棋规则引擎（GameEngine 实现）
 *
 * 盘面编码：cell = side * 10 + 棋子类型（0 = 空）
 *   side 1 = 红方（下方，先行），side 2 = 黑方（上方）
 *   类型：1 帅/将  2 仕/士  3 相/象  4 马  5 车  6 炮  7 兵/卒
 *
 * 规则：完整走子（蹩马腿/塞象眼/过河兵/炮打隔子/九宫），
 *       将帅照面视为非法，被将军必须解除，无子可动（绝杀/困毙）判负。
 */
public class XiangqiBoard implements BoardGameEngine {

    public static final int ROWS = 10;
    public static final int COLS = 9;

    // 棋子类型
    public static final int KING = 1;    // 帅/将
    public static final int ADVISOR = 2; // 仕/士
    public static final int BISHOP = 3;  // 相/象
    public static final int HORSE = 4;   // 马
    public static final int ROOK = 5;    // 车
    public static final int CANNON = 6;  // 炮
    public static final int PAWN = 7;    // 兵/卒

    private static final int STEP_TIME_SECONDS = 60;
    private static final int TOTAL_TIME_SECONDS = 600;

    private static final int[][] DIRS4 = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private final int[][] board;
    private int currentTurn;
    private int moveCount;
    private int moveSeq;
    private final List<int[]> moveHistory = new ArrayList<>();
    private List<int[]> lastEffects = new ArrayList<>();

    private boolean gameOver;
    private Integer winner;

    public XiangqiBoard() {
        this.board = new int[ROWS][COLS];
        reset();
    }

    @Override
    public GameType type() { return GameType.XIANGQI; }

    @Override
    public int getRows() { return ROWS; }

    @Override
    public int getCols() { return COLS; }

    @Override
    public void reset() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = 0;
            }
        }
        // 黑方（上）
        board[0][0] = 25; board[0][1] = 24; board[0][2] = 23; board[0][3] = 22;
        board[0][4] = 21; board[0][5] = 22; board[0][6] = 23; board[0][7] = 24; board[0][8] = 25;
        board[2][1] = 26; board[2][7] = 26;
        board[3][0] = 27; board[3][2] = 27; board[3][4] = 27; board[3][6] = 27; board[3][8] = 27;
        // 红方（下）
        board[9][0] = 15; board[9][1] = 14; board[9][2] = 13; board[9][3] = 12;
        board[9][4] = 11; board[9][5] = 12; board[9][6] = 13; board[9][7] = 14; board[9][8] = 15;
        board[7][1] = 16; board[7][7] = 16;
        board[6][0] = 17; board[6][2] = 17; board[6][4] = 17; board[6][6] = 17; board[6][8] = 17;

        currentTurn = 1; // 红先
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
    }

    private static int sideOf(int piece) { return piece / 10; }

    private static int typeOf(int piece) { return piece % 10; }

    private static boolean inBoard(int r, int c) {
        return r >= 0 && r < ROWS && c >= 0 && c < COLS;
    }

    /** 九宫：红方 7-9 行，黑方 0-2 行，列 3-5 */
    private static boolean inPalace(int r, int c, int side) {
        if (c < 3 || c > 5) return false;
        return side == 1 ? (r >= 7 && r <= 9) : (r >= 0 && r <= 2);
    }

    /** 兵是否已过河：红过河 r<=4，黑过河 r>=5 */
    private static boolean crossedRiver(int r, int side) {
        return side == 1 ? r <= 4 : r >= 5;
    }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        if (!inBoard(row, col)) return false;
        int v = board[row][col];
        return v != 0 && sideOf(v) == side;
    }

    /**
     * 某方向的伪合法走法（不含将军判定），返回目标点列表（静态供 AI 复用）
     */
    static List<int[]> pseudoMoves(int[][] b, int fromR, int fromC) {
        List<int[]> moves = new ArrayList<>();
        int piece = b[fromR][fromC];
        int side = sideOf(piece);
        int type = typeOf(piece);
        int forward = side == 1 ? -1 : 1;
        int[][] diag = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

        switch (type) {
            case KING:
                for (int[] d : DIRS4) {
                    int r = fromR + d[0], c = fromC + d[1];
                    if (inPalace(r, c, side) && !isOwn(b, r, c, side)) {
                        moves.add(new int[]{r, c});
                    }
                }
                break;

            case ADVISOR:
                for (int[] d : diag) {
                    int r = fromR + d[0], c = fromC + d[1];
                    if (inPalace(r, c, side) && !isOwn(b, r, c, side)) {
                        moves.add(new int[]{r, c});
                    }
                }
                break;

            case BISHOP:
                for (int[] d : diag) {
                    int r = fromR + 2 * d[0], c = fromC + 2 * d[1];
                    int eyeR = fromR + d[0], eyeC = fromC + d[1];
                    if (!inBoard(r, c) || !inPalaceRiverSide(r, side)) continue;
                    if (b[eyeR][eyeC] != 0) continue; // 塞象眼
                    if (!isOwn(b, r, c, side)) moves.add(new int[]{r, c});
                }
                break;

            case HORSE:
                int[][] jumps = {{-2, -1}, {-2, 1}, {2, -1}, {2, 1}, {-1, -2}, {1, -2}, {-1, 2}, {1, 2}};
                for (int[] d : jumps) {
                    int r = fromR + d[0], c = fromC + d[1];
                    // 蹩马腿：走竖走横决定腿的位置
                    int legR = Math.abs(d[0]) == 2 ? fromR + d[0] / 2 : fromR;
                    int legC = Math.abs(d[1]) == 2 ? fromC + d[1] / 2 : fromC;
                    if (!inBoard(r, c)) continue;
                    if (b[legR][legC] != 0) continue; // 蹩马腿
                    if (!isOwn(b, r, c, side)) moves.add(new int[]{r, c});
                }
                break;

            case ROOK:
                for (int[] d : DIRS4) {
                    int r = fromR + d[0], c = fromC + d[1];
                    while (inBoard(r, c)) {
                        if (b[r][c] == 0) {
                            moves.add(new int[]{r, c});
                        } else {
                            if (!isOwn(b, r, c, side)) moves.add(new int[]{r, c});
                            break;
                        }
                        r += d[0];
                        c += d[1];
                    }
                }
                break;

            case CANNON:
                for (int[] d : DIRS4) {
                    int r = fromR + d[0], c = fromC + d[1];
                    boolean screenPassed = false;
                    while (inBoard(r, c)) {
                        if (b[r][c] == 0) {
                            if (!screenPassed) moves.add(new int[]{r, c});
                        } else if (!screenPassed) {
                            screenPassed = true; // 炮架
                        } else {
                            if (!isOwn(b, r, c, side)) moves.add(new int[]{r, c}); // 炮打隔子
                            break;
                        }
                        r += d[0];
                        c += d[1];
                    }
                }
                break;

            case PAWN:
                int fr = fromR + forward;
                if (inBoard(fr, fromC) && !isOwn(b, fr, fromC, side)) {
                    moves.add(new int[]{fr, fromC});
                }
                if (crossedRiver(fromR, side)) {
                    for (int dc : new int[]{-1, 1}) {
                        int c = fromC + dc;
                        if (inBoard(fromR, c) && !isOwn(b, fromR, c, side)) {
                            moves.add(new int[]{fromR, c});
                        }
                    }
                }
                break;

            default:
                break;
        }
        return moves;
    }

    /** 象不能过河：红（side 1）活动区 r>=5，黑（side 2）r<=4 */
    private static boolean inPalaceRiverSide(int r, int side) {
        return side == 1 ? r >= 5 : r <= 4;
    }

    private static boolean isOwn(int[][] b, int r, int c, int side) {
        int v = b[r][c];
        return v != 0 && sideOf(v) == side;
    }

    private static int findKing(int[][] b, int side) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 3; c <= 5; c++) {
                if (sideOf(b[r][c]) == side && typeOf(b[r][c]) == KING) {
                    return r * COLS + c;
                }
            }
        }
        return -1;
    }

    /**
     * side 方是否被将军（含将帅照面）（静态供 AI 复用）
     */
    static boolean inCheck(int[][] b, int side) {
        int king = findKing(b, side);
        if (king < 0) return true; // 无帅=已败
        int kr = king / COLS, kc = king % COLS;
        int enemy = side == 1 ? 2 : 1;

        // 将帅照面：同列且中间无子
        int eKing = findKing(b, enemy);
        if (eKing >= 0) {
            int er = eKing / COLS, ec = eKing % COLS;
            if (ec == kc) {
                boolean blocked = false;
                for (int r = Math.min(kr, er) + 1; r < Math.max(kr, er); r++) {
                    if (b[r][kc] != 0) { blocked = true; break; }
                }
                if (!blocked) return true;
            }
        }

        // 任意敌子伪合法走法能吃到帅
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (isOwn(b, r, c, enemy)) {
                    for (int[] t : pseudoMoves(b, r, c)) {
                        if (t[0] == kr && t[1] == kc) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 综合合法性（伪合法 + 走后不被将军/不照面），实盘 apply/undo
     */
    public boolean isLegalMove(int fromR, int fromC, int toR, int toC, int side) {
        if (gameOver || !inBoard(fromR, fromC) || !inBoard(toR, toC)) return false;
        if (side != currentTurn) return false;
        int piece = board[fromR][fromC];
        if (piece == 0 || sideOf(piece) != side) return false;
        if (isOwn(board, toR, toC, side)) return false;
        if (!containsTarget(pseudoMoves(board, fromR, fromC), toR, toC)) return false;

        // 试走
        int captured = board[toR][toC];
        board[toR][toC] = piece;
        board[fromR][fromC] = 0;
        boolean bad = inCheck(board, side);
        board[fromR][fromC] = piece;
        board[toR][toC] = captured;
        return !bad;
    }

    private static boolean containsTarget(List<int[]> moves, int r, int c) {
        for (int[] t : moves) {
            if (t[0] == r && t[1] == c) return true;
        }
        return false;
    }

    /** side 方是否存在任何合法走法 */
    private boolean hasAnyLegalMove(int side) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (!isOwn(board, r, c, side)) continue;
                int piece = board[r][c];
                for (int[] t : pseudoMoves(board, r, c)) {
                    int captured = board[t[0]][t[1]];
                    board[t[0]][t[1]] = piece;
                    board[r][c] = 0;
                    boolean bad = inCheck(board, side);
                    board[r][c] = piece;
                    board[t[0]][t[1]] = captured;
                    if (!bad) return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean placeStone(int fromRow, int fromCol, int toRow, int toCol, int side) {
        if (gameOver) return false;
        if (side != currentTurn) return false;
        if (!isLegalMove(fromRow, fromCol, toRow, toCol, side)) return false;

        int piece = board[fromRow][fromCol];
        board[toRow][toCol] = piece;
        board[fromRow][fromCol] = 0;
        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{toRow, toCol, side, fromRow, fromCol});

        // 效果：清空起点 + 落子终点（吃子自动被覆盖）
        List<int[]> effects = new ArrayList<>();
        effects.add(new int[]{fromRow, fromCol, 0});
        effects.add(new int[]{toRow, toCol, piece});
        lastEffects = effects;

        // 无子可动（被绝杀或困毙）→ 行棋方胜
        int opponent = side == 1 ? 2 : 1;
        currentTurn = opponent;
        if (!hasAnyLegalMove(opponent)) {
            gameOver = true;
            winner = side;
        }
        return true;
    }

    @Override
    public boolean placeStone(int row, int col, int side) {
        // 象棋必须提供起点
        return false;
    }

    @Override
    public int getCurrentTurn() { return currentTurn; }

    @Override
    public int[][] getBoardSnapshot() {
        int[][] snapshot = new int[ROWS][COLS];
        for (int i = 0; i < ROWS; i++) {
            System.arraycopy(board[i], 0, snapshot[i], 0, COLS);
        }
        return snapshot;
    }

    @Override
    public boolean isOver() { return gameOver; }

    @Override
    public Integer getWinner() { return winner; }

    @Override
    public List<int[]> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
    }

    @Override
    public List<int[]> getLastMoveEffects() {
        return lastEffects;
    }

    @Override
    public int getMoveCount() { return moveCount; }

    @Override
    public int getMoveSeq() { return moveSeq; }

    @Override
    public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }

    @Override
    public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }

    // ============ 测试钩子（包级可见） ============

    void setCell(int row, int col, int piece) {
        board[row][col] = piece;
    }

    void setTurn(int side) {
        this.currentTurn = side;
    }
}
