package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 斗兽棋（Jungle Chess / Dou Shou Qi），9 行 × 7 列。
 *
 * 盘面编码沿用项目约定：cell = side * 10 + type，0 为空。
 *   side 1 = 下方（红方，先行），side 2 = 上方（黑方）。
 *   type: 1 鼠、2 猫、3 狗、4 狼、5 豹、6 虎、7 狮、8 象。
 *
 * 规则：
 * - 每回合走一枚己方棋子，正交移动一格；不能进入己方兽穴。
 * - 强弱：象 > 狮 > 虎 > 豹 > 狼 > 狗 > 猫 > 鼠；鼠可吃象，象不能吃鼠。
 * - 鼠是唯一能进入河流的棋子；陆地上的棋子不能吃水中的鼠，水中的鼠也不能吃陆地棋子。
 * - 狮、虎可以沿直线跳过河流；跳跃路径（含落点前的整段河面）有任意鼠时不能跳。
 * - 陷阱中的敌棋战力视为 0，任意己方棋子都可吃；己方棋子站上己方陷阱不受影响。
 * - 进入对方兽穴立即获胜；吃光对方或对方无合法走法也获胜。
 * - 连续 120 个半回合无吃子判和，避免无限往返。
 */
public class JungleBoard implements BoardGameEngine {

    public static final int ROWS = 9;
    public static final int COLS = 7;
    public static final int MOUSE = 1;
    public static final int CAT = 2;
    public static final int DOG = 3;
    public static final int WOLF = 4;
    public static final int LEOPARD = 5;
    public static final int TIGER = 6;
    public static final int LION = 7;
    public static final int ELEPHANT = 8;
    public static final int DRAW_AFTER_QUIET_TURNS = 120;

    private static final int STEP_TIME_SECONDS = 45;
    private static final int TOTAL_TIME_SECONDS = 600;

    /** 两座兽穴：上方 (0,3)、下方 (8,3)。 */
    private static final int[][] DENS = {{0, 3}, {8, 3}};
    /** 六格陷阱：上下各三格。 */
    private static final boolean[][] TRAPS = new boolean[ROWS][COLS];
    /** 河流：行 3–5，列 1–2 与 4–5。 */
    private static final boolean[][] WATER = new boolean[ROWS][COLS];

    static {
        TRAPS[0][2] = TRAPS[0][4] = TRAPS[1][3] = true;
        TRAPS[8][2] = TRAPS[8][4] = TRAPS[7][3] = true;
        for (int r = 3; r <= 5; r++) {
            WATER[r][1] = WATER[r][2] = WATER[r][4] = WATER[r][5] = true;
        }
    }

    private static final int[][] ORTHO = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final String[] NAMES = {"", "鼠", "猫", "狗", "狼", "豹", "虎", "狮", "象"};

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
    /** 上一手的吃子信息，供前端提示与复盘使用。 */
    private int lastCapturedRow = -1;
    private int lastCapturedCol = -1;
    private int lastCapturedPiece;

    public JungleBoard() { reset(); }

    @Override public GameType type() { return GameType.JUNGLE; }
    @Override public int getRows() { return ROWS; }
    @Override public int getCols() { return COLS; }

    @Override
    public void reset() {
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) board[r][c] = 0;
        // 上方（黑方，side 2）
        board[0][0] = sidePiece(2, LION);
        board[0][6] = sidePiece(2, TIGER);
        board[1][1] = sidePiece(2, DOG);
        board[1][5] = sidePiece(2, CAT);
        board[2][0] = sidePiece(2, MOUSE);
        board[2][2] = sidePiece(2, LEOPARD);
        board[2][4] = sidePiece(2, WOLF);
        board[2][6] = sidePiece(2, ELEPHANT);
        // 下方（红方，side 1），与上方 180° 对称
        board[8][0] = sidePiece(1, TIGER);
        board[8][6] = sidePiece(1, LION);
        board[7][1] = sidePiece(1, CAT);
        board[7][5] = sidePiece(1, DOG);
        board[6][0] = sidePiece(1, ELEPHANT);
        board[6][2] = sidePiece(1, WOLF);
        board[6][4] = sidePiece(1, LEOPARD);
        board[6][6] = sidePiece(1, MOUSE);
        currentTurn = 1;
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
        draw = false;
        quietTurns = 0;
        lastCapturedRow = lastCapturedCol = -1;
        lastCapturedPiece = 0;
    }

    private static int sidePiece(int side, int type) { return side * 10 + type; }
    private static int sideOf(int piece) { return piece / 10; }
    private static int typeOf(int piece) { return piece % 10; }
    static boolean inBoard(int r, int c) { return r >= 0 && r < ROWS && c >= 0 && c < COLS; }
    static boolean isWater(int r, int c) { return inBoard(r, c) && WATER[r][c]; }
    static boolean isTrap(int r, int c) { return inBoard(r, c) && TRAPS[r][c]; }
    static boolean isDen(int r, int c) {
        return inBoard(r, c)
                && ((DENS[0][0] == r && DENS[0][1] == c) || (DENS[1][0] == r && DENS[1][1] == c));
    }

    /** 某方兽穴坐标：side 1 的目标是上方，side 2 的目标是下方。 */
    static int[] denOf(int side) { return DENS[side == 1 ? 0 : 1]; }
    /** 某方自己的兽穴（不能进入）。 */
    static int[] ownDenOf(int side) { return DENS[side == 1 ? 1 : 0]; }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        if (!inBoard(row, col)) return false;
        int v = board[row][col];
        return v != 0 && sideOf(v) == side;
    }

    /**
     * 单步走法。返回的每一手为 {fromRow, fromCol, toRow, toCol, capture, jump}，
     * jump 仅用于区分狮虎跳河（前端画提示用）。
     */
    public List<int[]> legalMoves(int side) {
        List<int[]> result = new ArrayList<>();
        if (gameOver || side != currentTurn) return result;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int piece = board[r][c];
                if (piece == 0 || sideOf(piece) != side) continue;
                // 普通一步
                for (int[] d : ORTHO) {
                    int tr = r + d[0], tc = c + d[1];
                    if (!inBoard(tr, tc)) continue;
                    // 只有鼠能进入河流。
                    if (isWater(tr, tc) && typeOf(piece) != MOUSE) continue;
                    if (isOwnDen(side, tr, tc)) continue;
                    int target = board[tr][tc];
                    if (target != 0 && sideOf(target) == side) continue;
                    if (canCapture(piece, r, c, tr, tc, target)) {
                        addMove(result, r, c, tr, tc, target != 0);
                    }
                }
                // 狮虎跳河：沿直线跨过整段河面
                int type = typeOf(piece);
                if (type == LION || type == TIGER) {
                    for (int[] d : ORTHO) {
                        int tr = r + d[0], tc = c + d[1];
                        if (!isWater(tr, tc)) continue;
                        int jumpLen = 0;
                        boolean blocked = false;
                        while (inBoard(tr, tc) && isWater(tr, tc)) {
                            if (board[tr][tc] != 0 && typeOf(board[tr][tc]) == MOUSE) { blocked = true; break; }
                            tr += d[0]; tc += d[1]; jumpLen++;
                        }
                        if (blocked || jumpLen == 0 || !inBoard(tr, tc)) continue;
                        if (isOwnDen(side, tr, tc)) continue;
                        int target = board[tr][tc];
                        if (target != 0 && sideOf(target) == side) continue;
                        if (canCapture(piece, r, c, tr, tc, target)) {
                            addMove(result, r, c, tr, tc, target != 0);
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 同一子到同一格可能同时有普通走法与跳河路线，前端只保留更高效的一手。 */
    private static void addMove(List<int[]> result, int fromRow, int fromCol,
                                int toRow, int toCol, boolean capture) {
        for (int[] existing : result) {
            if (existing[0] == fromRow && existing[1] == fromCol
                    && existing[2] == toRow && existing[3] == toCol) {
                if (capture) existing[4] = 1;
                return;
            }
        }
        int jump = Math.abs(toRow - fromRow) + Math.abs(toCol - fromCol) > 1 ? 1 : 0;
        result.add(new int[]{fromRow, fromCol, toRow, toCol, capture ? 1 : 0, jump});
    }

    private static boolean isOwnDen(int side, int r, int c) {
        int[] own = ownDenOf(side);
        return own[0] == r && own[1] == c;
    }

    public boolean isLegalMove(int fromRow, int fromCol, int toRow, int toCol, int side) {
        for (int[] move : legalMoves(side)) {
            if (move[0] == fromRow && move[1] == fromCol
                    && move[2] == toRow && move[3] == toCol) return true;
        }
        return false;
    }

    /**
     * 能否从 (r,c) 吃 target。
     * 规则优先级：空目标直接可走；同方不可吃；水陆隔离；陷阱降战力；鼠克象。
     */
    private boolean canCapture(int attacker, int attackerRow, int attackerCol,
                               int targetRow, int targetCol, int target) {
        if (target == 0) return true;
        if (sideOf(target) == sideOf(attacker)) return false;
        boolean attackerInWater = isWater(attackerRow, attackerCol);
        boolean targetInWater = isWater(targetRow, targetCol);
        // 水陆互不可吃：只有鼠能下水，水中的鼠也不能攻击岸上棋子。
        if (attackerInWater != targetInWater) return false;
        int attackPower = typeOf(attacker);
        int defendPower = typeOf(target);
        // 站在陷阱里的敌棋战力为 0；己方陷阱不会削弱己方棋子。
        if (isTrap(targetRow, targetCol) && isTrapForSide(targetRow, targetCol, sideOf(target))) {
            defendPower = 0;
        }
        if (defendPower == 0) return true;
        if (attackPower == MOUSE && defendPower == ELEPHANT) return true;
        if (attackPower == ELEPHANT && defendPower == MOUSE) return false;
        return attackPower >= defendPower;
    }

    private static boolean isTrapForSide(int r, int c, int side) {
        if (!isTrap(r, c)) return false;
        // 上方陷阱属于 side 2，下方陷阱属于 side 1。
        return (r <= 1) == (side == 2);
    }

    @Override
    public boolean placeStone(int row, int col, int side) {
        return false;
    }

    @Override
    public boolean placeStone(int fromRow, int fromCol, int toRow, int toCol, int side) {
        if (gameOver || side != currentTurn) return false;
        int[] matched = null;
        for (int[] m : legalMoves(side)) {
            if (m[0] == fromRow && m[1] == fromCol && m[2] == toRow && m[3] == toCol) {
                matched = m;
                break;
            }
        }
        if (matched == null) return false;

        int piece = board[fromRow][fromCol];
        int captured = board[toRow][toCol];
        List<int[]> effects = new ArrayList<>();
        board[fromRow][fromCol] = 0;
        effects.add(new int[]{fromRow, fromCol, 0});
        if (captured != 0) {
            board[toRow][toCol] = 0;
            effects.add(new int[]{toRow, toCol, 0});
            lastCapturedRow = toRow;
            lastCapturedCol = toCol;
            lastCapturedPiece = captured;
        } else {
            lastCapturedRow = lastCapturedCol = -1;
            lastCapturedPiece = 0;
        }
        board[toRow][toCol] = piece;
        effects.add(new int[]{toRow, toCol, piece});
        lastEffects = effects;
        moveCount++;
        moveSeq++;
        // 棋谱：{toRow, toCol, side, fromRow, fromCol, capturedType, jump}
        moveHistory.add(new int[]{toRow, toCol, side, fromRow, fromCol,
                captured == 0 ? 0 : typeOf(captured), matched[5]});
        if (captured != 0) quietTurns = 0; else quietTurns++;

        int[] opponentDen = denOf(side);
        if (toRow == opponentDen[0] && toCol == opponentDen[1]) {
            gameOver = true;
            winner = side;
            return true;
        }
        if (quietTurns >= DRAW_AFTER_QUIET_TURNS) {
            gameOver = true;
            draw = true;
            winner = null;
            return true;
        }
        // 对方无子或无合法走法 → 当前方胜
        int next = side == 1 ? 2 : 1;
        Integer opponentPieces = countPieces(next);
        currentTurn = next;
        if (opponentPieces == 0 || legalMoves(next).isEmpty()) {
            gameOver = true;
            winner = side;
        }
        return true;
    }

    private int countPieces(int side) {
        int count = 0;
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            int v = board[r][c];
            if (v != 0 && sideOf(v) == side) count++;
        }
        return count;
    }

    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return gameOver; }
    @Override public Integer getWinner() { return winner; }

    @Override
    public List<Integer> getDrawingSides() {
        return draw ? java.util.Arrays.asList(1, 2) : Collections.emptyList();
    }

    @Override
    public String getScoreSummary() {
        if (!gameOver) return null;
        if (draw) return "连续 " + DRAW_AFTER_QUIET_TURNS + " 个半回合无吃子 · 和棋";
        int mine = countPieces(1), theirs = countPieces(2);
        return "红方 " + mine + " 子 · 黑方 " + theirs + " 子";
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

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("turn", currentTurn);
        view.put("over", gameOver);
        view.put("winner", winner);
        view.put("quietTurns", quietTurns);
        view.put("drawAfterQuietTurns", DRAW_AFTER_QUIET_TURNS);
        view.put("lastCaptured", lastCapturedPiece == 0 ? null
                : new int[]{lastCapturedRow, lastCapturedCol, lastCapturedPiece});
        List<Map<String, Object>> moves = new ArrayList<>();
        for (int[] move : legalMoves(currentTurn)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromRow", move[0]);
            item.put("fromCol", move[1]);
            item.put("toRow", move[2]);
            item.put("toCol", move[3]);
            item.put("capture", move[4] > 0);
            item.put("jump", move[5] > 0);
            moves.add(item);
        }
        view.put("legalMoves", moves);
        view.put("water", waterCells());
        view.put("traps", trapCells());
        List<Map<String, Object>> dens = new ArrayList<>();
        for (int side = 1; side <= 2; side++) {
            int[] d = denOf(side);
            Map<String, Object> den = new LinkedHashMap<>();
            den.put("row", d[0]);
            den.put("col", d[1]);
            den.put("side", side);
            dens.add(den);
        }
        view.put("dens", dens);
        return view;
    }

    private List<int[]> waterCells() {
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) if (WATER[r][c]) cells.add(new int[]{r, c});
        return cells;
    }

    private List<int[]> trapCells() {
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) if (TRAPS[r][c]) cells.add(new int[]{r, c});
        return cells;
    }

    static String pieceName(int type) { return type >= 1 && type <= 8 ? NAMES[type] : "?"; }

    /** 测试辅助：清空盘面并重置为红方回合。 */
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
        lastCapturedRow = lastCapturedCol = -1;
        lastCapturedPiece = 0;
    }

    /** 测试辅助：直接放子。 */
    void putForTest(int row, int col, int side, int type) {
        board[row][col] = sidePiece(side, type);
    }

    /** 测试辅助：清空一格。 */
    void clearForTest(int row, int col) { board[row][col] = 0; }

    /** 测试辅助：强制行动方。 */
    void forceTurnForTest(int side) { currentTurn = side; }

    /** 测试辅助：模拟安静半回合。 */
    void setQuietTurnsForTest(int value) { quietTurns = value; }
}
