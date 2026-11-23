package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 暗棋 / 翻棋（Banqi），4 行 × 8 列，共 32 枚棋子全部背面朝上随机落位。
 *
 * 盘面编码沿用项目约定：cell = side * 10 + type，0 为空，{@link #HIDDEN} 表示背面棋子。
 *   side 1 = 红方（先行），side 2 = 黑方。
 *   type: 1 帅/将、2 仕/士、3 相/象、4 车、5 马、6 炮、7 兵/卒。
 *
 * 规则：
 * - 每回合二选一：翻开任意一枚背面棋子（无论归属），或移动一枚已翻开的己方棋子。
 * - 帅/将一步正交；仕/士一步斜线；相/象两步斜线且象眼不能有子；
 *   车任意距离直线且路径无阻挡；马走日且蹩马腿；炮移动同车，吃子必须隔一枚炮架；
 *   兵/卒只能向前一步。
 * - 已翻开的棋子按大小吃子：帅 > 仕 > 相 > 车 > 马 > 炮 > 兵；
 *   兵可以吃帅，帅不能吃兵。炮吃子不受大小限制，但必须隔一枚棋子。
 * - 背面棋子可以被任意棋子直接吃掉；被吃后不再翻面。
 * - 对方棋子被吃光，或对方无子可翻、无子可走，即获胜；120 个无吃子且无翻子的
 *   半回合后判和，避免终盘无限往返。
 *
 * 隐藏信息：
 * - {@link #getBoardSnapshot()} 是服务端内部真实盘面，仅给 AI/测试使用；
 * - {@link #getVisibleBoardSnapshot()} 会把所有背面棋子统一编码成 {@link #HIDDEN}，
 *   房间同步包只使用这一份，避免对手从网络包里读到底牌。
 */
public class DarkChessBoard implements BoardGameEngine {

    public static final int ROWS = 4;
    public static final int COLS = 8;

    public static final int KING = 1;
    public static final int GUARD = 2;
    public static final int ELEPHANT = 3;
    public static final int ROOK = 4;
    public static final int HORSE = 5;
    public static final int CANNON = 6;
    public static final int SOLDIER = 7;

    /** 背面棋子对客户端统一使用的编码；真实身份只留在服务端 board 内。 */
    public static final int HIDDEN = 9;

    public static final int DRAW_AFTER_QUIET_TURNS = 120;

    private static final int STEP_TIME_SECONDS = 30;
    private static final int TOTAL_TIME_SECONDS = 600;

    private static final int[][] ORTHO = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] DIAG = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    private static final int[][] HORSE_MOVES = {
            {-2, -1, -1, 0}, {-2, 1, -1, 0}, {2, -1, 1, 0}, {2, 1, 1, 0},
            {-1, -2, 0, -1}, {1, -2, 0, -1}, {-1, 2, 0, 1}, {1, 2, 0, 1}
    };

    private static final String[] RED_NAMES = {"", "帅", "仕", "相", "车", "马", "炮", "兵"};
    private static final String[] BLACK_NAMES = {"", "将", "士", "象", "车", "马", "炮", "卒"};

    private final int[][] board = new int[ROWS][COLS];
    /** true = 该格棋子仍背面朝上 */
    private final boolean[][] hidden = new boolean[ROWS][COLS];
    private final List<int[]> moveHistory = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final String[] playerNames = {"", "红方", "黑方"};
    private final Random random;

    private List<int[]> lastEffects = new ArrayList<>();
    private int currentTurn = 1;
    private int moveCount;
    private int moveSeq;
    private boolean gameOver;
    private Integer winner;
    private boolean draw;
    private int quietTurns;
    private int lastCapturedRow = -1;
    private int lastCapturedCol = -1;
    private int lastCapturedPiece;

    public DarkChessBoard() {
        this(new Random());
    }

    /** 固定种子构造器：只用于单测复现随机开局。 */
    DarkChessBoard(long seed) {
        this(new Random(seed));
    }

    private DarkChessBoard(Random random) {
        this.random = random;
        reset();
    }

    @Override public GameType type() { return GameType.DARK_CHESS; }
    @Override public int getRows() { return ROWS; }
    @Override public int getCols() { return COLS; }

    @Override
    public void reset() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = 0;
                hidden[r][c] = false;
            }
        }
        List<Integer> pieces = new ArrayList<>(32);
        for (int side = 1; side <= 2; side++) {
            pieces.add(sidePiece(side, KING));
            pieces.add(sidePiece(side, GUARD));
            pieces.add(sidePiece(side, GUARD));
            pieces.add(sidePiece(side, ELEPHANT));
            pieces.add(sidePiece(side, ELEPHANT));
            pieces.add(sidePiece(side, ROOK));
            pieces.add(sidePiece(side, ROOK));
            pieces.add(sidePiece(side, HORSE));
            pieces.add(sidePiece(side, HORSE));
            pieces.add(sidePiece(side, CANNON));
            pieces.add(sidePiece(side, CANNON));
            for (int i = 0; i < 5; i++) pieces.add(sidePiece(side, SOLDIER));
        }
        Collections.shuffle(pieces, random);
        for (int i = 0; i < pieces.size(); i++) {
            int r = i / COLS, c = i % COLS;
            board[r][c] = pieces.get(i);
            hidden[r][c] = true;
        }
        currentTurn = 1;
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
        log.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
        draw = false;
        quietTurns = 0;
        lastCapturedRow = lastCapturedCol = -1;
        lastCapturedPiece = 0;
        playerNames[1] = "红方";
        playerNames[2] = "黑方";
        log.add("暗棋开始：32 枚棋子全部背面朝上，红方先手");
    }

    private static int sidePiece(int side, int type) { return side * 10 + type; }
    private static int sideOf(int piece) { return piece / 10; }
    private static int typeOf(int piece) { return piece % 10; }

    static boolean inBoard(int r, int c) { return r >= 0 && r < ROWS && c >= 0 && c < COLS; }

    @Override
    public void setPlayerName(int side, String name) {
        if (side == 1 || side == 2) {
            if (name != null && !name.trim().isEmpty()) playerNames[side] = name.trim();
        }
    }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        if (!inBoard(row, col) || hidden[row][col]) return false;
        int v = board[row][col];
        return v != 0 && sideOf(v) == side;
    }

    /** 当前可翻开的背面棋子。 */
    public List<int[]> legalFlips(int side) {
        List<int[]> result = new ArrayList<>();
        if (gameOver || side != currentTurn) return result;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (hidden[r][c]) result.add(new int[]{r, c});
            }
        }
        return result;
    }

    /**
     * 已翻开棋子的合法走法。
     * 返回 {fromRow, fromCol, toRow, toCol, capture, capturedType}。
     */
    public List<int[]> legalMoves(int side) {
        List<int[]> result = new ArrayList<>();
        if (gameOver || side != currentTurn) return result;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int piece = board[r][c];
                if (piece == 0 || hidden[r][c] || sideOf(piece) != side) continue;
                switch (typeOf(piece)) {
                    case KING: addSteps(result, side, r, c, ORTHO); break;
                    case GUARD: addSteps(result, side, r, c, DIAG); break;
                    case ELEPHANT: addElephant(result, side, r, c); break;
                    case ROOK: addRook(result, side, r, c); break;
                    case HORSE: addHorse(result, side, r, c); break;
                    case CANNON: addCannon(result, side, r, c); break;
                    case SOLDIER: addSoldier(result, side, r, c); break;
                    default: break;
                }
            }
        }
        return result;
    }

    public boolean isLegalFlip(int row, int col, int side) {
        if (gameOver || side != currentTurn || !inBoard(row, col)) return false;
        return hidden[row][col];
    }

    public boolean isLegalMove(int fromRow, int fromCol, int toRow, int toCol, int side) {
        for (int[] move : legalMoves(side)) {
            if (move[0] == fromRow && move[1] == fromCol
                    && move[2] == toRow && move[3] == toCol) return true;
        }
        return false;
    }

    private void addSteps(List<int[]> result, int side, int r, int c, int[][] dirs) {
        int piece = board[r][c];
        for (int[] d : dirs) {
            int tr = r + d[0], tc = c + d[1];
            if (inBoard(tr, tc) && canLand(side, piece, tr, tc)) {
                addMove(result, r, c, tr, tc, board[tr][tc]);
            }
        }
    }

    private void addElephant(List<int[]> result, int side, int r, int c) {
        int piece = board[r][c];
        for (int[] d : DIAG) {
            int mr = r + d[0], mc = c + d[1];
            int tr = r + d[0] * 2, tc = c + d[1] * 2;
            if (!inBoard(tr, tc) || !inBoard(mr, mc)) continue;
            if (board[mr][mc] != 0) continue; // 塞象眼
            if (canLand(side, piece, tr, tc)) addMove(result, r, c, tr, tc, board[tr][tc]);
        }
    }

    private void addRook(List<int[]> result, int side, int r, int c) {
        int piece = board[r][c];
        for (int[] d : ORTHO) {
            int tr = r + d[0], tc = c + d[1];
            while (inBoard(tr, tc)) {
                if (board[tr][tc] == 0) {
                    addMove(result, r, c, tr, tc, 0);
                } else {
                    if (canLand(side, piece, tr, tc)) addMove(result, r, c, tr, tc, board[tr][tc]);
                    break;
                }
                tr += d[0];
                tc += d[1];
            }
        }
    }

    private void addHorse(List<int[]> result, int side, int r, int c) {
        int piece = board[r][c];
        for (int[] m : HORSE_MOVES) {
            int lr = r + m[2], lc = c + m[3];
            if (!inBoard(lr, lc) || board[lr][lc] != 0) continue; // 蹩马腿
            int tr = r + m[0], tc = c + m[1];
            if (inBoard(tr, tc) && canLand(side, piece, tr, tc)) {
                addMove(result, r, c, tr, tc, board[tr][tc]);
            }
        }
    }

    private void addCannon(List<int[]> result, int side, int r, int c) {
        for (int[] d : ORTHO) {
            int tr = r + d[0], tc = c + d[1];
            boolean screen = false;
            while (inBoard(tr, tc)) {
                int target = board[tr][tc];
                if (!screen) {
                    if (target == 0) {
                        addMove(result, r, c, tr, tc, 0);
                    } else {
                        screen = true;
                    }
                } else if (target != 0) {
                    // 炮吃子不受大小限制，但必须正好隔一个炮架；背面棋子也可以吃。
                    if (hidden[tr][tc] || sideOf(target) != side) {
                        addMove(result, r, c, tr, tc, target);
                    }
                    break;
                }
                tr += d[0];
                tc += d[1];
            }
        }
    }

    private void addSoldier(List<int[]> result, int side, int r, int c) {
        int piece = board[r][c];
        int tr = r + (side == 1 ? -1 : 1);
        if (inBoard(tr, c) && canLand(side, piece, tr, c)) {
            addMove(result, r, c, tr, c, board[tr][c]);
        }
    }

    /** 目标格是否可进入；背面棋子对任何进攻方都是可吃目标。 */
    private boolean canLand(int side, int attacker, int tr, int tc) {
        int target = board[tr][tc];
        if (target == 0) return true;
        if (hidden[tr][tc]) return true;
        if (sideOf(target) == side) return false;
        return canCapture(typeOf(attacker), typeOf(target));
    }

    /** 明牌对明牌的吃子大小判定。 */
    static boolean canCapture(int attackerType, int targetType) {
        if (attackerType == SOLDIER && targetType == KING) return true;
        if (attackerType == KING && targetType == SOLDIER) return false;
        return attackerType <= targetType;
    }

    /** 棋子战力，越大越强；只用于 AI 评估与可读日志。 */
    static int rank(int type) {
        return 8 - type;
    }

    private static void addMove(List<int[]> result, int fromRow, int fromCol,
                                int toRow, int toCol, int captured) {
        result.add(new int[]{fromRow, fromCol, toRow, toCol,
                captured == 0 ? 0 : 1, captured == 0 ? 0 : typeOf(captured)});
    }

    @Override
    public boolean placeStone(int row, int col, int side) {
        if (gameOver || side != currentTurn) return false;
        if (!inBoard(row, col) || !hidden[row][col]) return false;

        int piece = board[row][col];
        hidden[row][col] = false;
        lastEffects = new ArrayList<>();
        lastEffects.add(new int[]{row, col, piece});
        moveCount++;
        moveSeq++;
        // 棋谱：[row, col, side, -1, -1, 0(翻子), 翻出的棋子编码, 0]
        moveHistory.add(new int[]{row, col, side, -1, -1, 0, piece, 0});
        log.add(playerNames[side] + " 翻开 " + pieceName(piece)
                + "（" + coordinate(row, col) + "）");
        lastCapturedRow = lastCapturedCol = -1;
        lastCapturedPiece = 0;
        advanceTurn(side, false, true);
        return true;
    }

    @Override
    public boolean placeStone(int fromRow, int fromCol, int toRow, int toCol, int side) {
        // 无起点的 MOVE 是「翻子」；BoardGameEngine.applyMove 统一走本重载。
        if (fromRow < 0 || fromCol < 0) return placeStone(toRow, toCol, side);
        if (gameOver || side != currentTurn) return false;
        int[] matched = null;
        for (int[] move : legalMoves(side)) {
            if (move[0] == fromRow && move[1] == fromCol
                    && move[2] == toRow && move[3] == toCol) {
                matched = move;
                break;
            }
        }
        if (matched == null) return false;

        int piece = board[fromRow][fromCol];
        int captured = board[toRow][toCol];
        boolean capturedHidden = hidden[toRow][toCol];
        List<int[]> effects = new ArrayList<>();
        board[fromRow][fromCol] = 0;
        hidden[fromRow][fromCol] = false;
        effects.add(new int[]{fromRow, fromCol, 0});
        if (captured != 0) {
            board[toRow][toCol] = 0;
            hidden[toRow][toCol] = false;
            effects.add(new int[]{toRow, toCol, 0});
            if (capturedHidden) {
                // 被吃暗子的身份从未公开，实时视图与棋谱都必须保持脱敏。
                lastCapturedRow = lastCapturedCol = -1;
                lastCapturedPiece = 0;
            } else {
                lastCapturedRow = toRow;
                lastCapturedCol = toCol;
                lastCapturedPiece = captured;
            }
        } else {
            lastCapturedRow = lastCapturedCol = -1;
            lastCapturedPiece = 0;
        }
        board[toRow][toCol] = piece;
        hidden[toRow][toCol] = false;
        effects.add(new int[]{toRow, toCol, piece});
        lastEffects = effects;
        moveCount++;
        moveSeq++;
        // 棋谱：[toRow, toCol, side, fromRow, fromCol, 1(走子/2(吃子), 移动的棋子编码, 被吃编码]
        moveHistory.add(new int[]{toRow, toCol, side, fromRow, fromCol,
                captured == 0 ? 1 : 2, piece, capturedHidden ? 0 : captured});

        String target = coordinate(toRow, toCol);
        if (captured == 0) {
            log.add(playerNames[side] + " " + pieceName(piece) + " "
                    + coordinate(fromRow, fromCol) + " → " + target);
        } else {
            log.add(playerNames[side] + " " + pieceName(piece) + " "
                    + coordinate(fromRow, fromCol) + " → " + target
                    + (capturedHidden ? "，吃掉一枚暗棋" : "，吃掉" + pieceName(captured)));
        }
        advanceTurn(side, captured != 0, false);
        return true;
    }

    private void advanceTurn(int side, boolean captured, boolean flipped) {
        if (captured || flipped) quietTurns = 0; else quietTurns++;
        if (quietTurns >= DRAW_AFTER_QUIET_TURNS) {
            gameOver = true;
            draw = true;
            winner = null;
            log.add("连续 " + DRAW_AFTER_QUIET_TURNS + " 个无吃子且无翻子的半回合，判和");
            return;
        }
        int next = side == 1 ? 2 : 1;
        currentTurn = next;
        if (countPieces(next) == 0) {
            gameOver = true;
            winner = side;
            log.add(playerNames[side] + " 吃光对方棋子，赢得本局");
            return;
        }
        if (legalFlips(next).isEmpty() && legalMoves(next).isEmpty()) {
            gameOver = true;
            winner = side;
            log.add(playerNames[next] + " 无子可翻、无子可走，" + playerNames[side] + " 获胜");
        }
    }

    private int countPieces(int side) {
        int count = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int v = board[r][c];
                if (v != 0 && sideOf(v) == side) count++;
            }
        }
        return count;
    }

    private static String coordinate(int row, int col) {
        return String.valueOf((char) ('A' + col)) + (row + 1);
    }

    private static String pieceName(int piece) {
        int side = sideOf(piece), type = typeOf(piece);
        String[] names = side == 1 ? RED_NAMES : BLACK_NAMES;
        return (side == 1 ? "红" : "黑") + (type >= 1 && type < names.length ? names[type] : "?");
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
        int red = countPieces(1), black = countPieces(2);
        if (draw) return "连续 " + DRAW_AFTER_QUIET_TURNS + " 个半回合无吃子 · 和棋";
        return "红方剩 " + red + " 子 · 黑方剩 " + black + " 子";
    }

    @Override public List<int[]> getMoveHistory() { return Collections.unmodifiableList(moveHistory); }
    @Override public List<int[]> getLastMoveEffects() { return lastEffects; }
    @Override public int getMoveCount() { return moveCount; }
    @Override public int getMoveSeq() { return moveSeq; }
    @Override public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }
    @Override public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }

    /** 服务端内部真实盘面（AI 和单测使用），不要直接下发给客户端。 */
    @Override
    public int[][] getBoardSnapshot() {
        int[][] copy = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) copy[r] = board[r].clone();
        return copy;
    }

    /** 客户端只能看到已翻开的棋子，背面统一为 {@link #HIDDEN}。 */
    @Override
    public int[][] getVisibleBoardSnapshot() {
        int[][] copy = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                copy[r][c] = hidden[r][c] ? HIDDEN : board[r][c];
            }
        }
        return copy;
    }

    /** 开局全部背面朝上，回放时按这个快照起盘。 */
    @Override
    public int[][] getInitialBoardSnapshot() {
        int[][] initial = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) initial[r][c] = HIDDEN;
        }
        return initial;
    }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("turn", currentTurn);
        view.put("over", gameOver);
        view.put("winner", winner);
        view.put("draw", draw);
        view.put("quietTurns", quietTurns);
        view.put("drawAfterQuietTurns", DRAW_AFTER_QUIET_TURNS);
        view.put("hiddenCount", countHidden());
        view.put("redRemaining", countPieces(1));
        view.put("blackRemaining", countPieces(2));

        List<Map<String, Object>> flips = new ArrayList<>();
        for (int[] flip : legalFlips(currentTurn)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("row", flip[0]);
            item.put("col", flip[1]);
            flips.add(item);
        }
        view.put("legalFlips", flips);

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
        view.put("lastCaptured", lastCapturedPiece == 0 ? null
                : new int[]{lastCapturedRow, lastCapturedCol, lastCapturedPiece});
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 6), log.size())));
        return view;
    }

    private int countHidden() {
        int count = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) if (hidden[r][c]) count++;
        }
        return count;
    }

    static String pieceLabel(int piece) { return pieceName(piece); }

    // ============ 测试辅助 ============

    void clearForTest() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = 0;
                hidden[r][c] = false;
            }
        }
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
        log.clear();
    }

    void putForTest(int row, int col, int side, int type) {
        board[row][col] = sidePiece(side, type);
        hidden[row][col] = false;
    }

    void putHiddenForTest(int row, int col, int side, int type) {
        board[row][col] = sidePiece(side, type);
        hidden[row][col] = true;
    }

    void forceTurnForTest(int side) { currentTurn = side; }
    void setQuietTurnsForTest(int value) { quietTurns = value; }
}
