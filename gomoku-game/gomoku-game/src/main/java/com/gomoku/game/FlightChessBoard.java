package com.gomoku.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 飞行棋：2–4 人轮流掷骰、移动棋子的轻量多人玩法。
 *
 * 棋盘采用 15×15 外圈：周长正好 56 格，与每枚棋子绕行一圈的步数一致。
 * 每方 4 枚棋子，从自己的起飞格出发，绕行一圈后进入中心终点。
 *
 * 规则：
 * - 每回合服务端掷骰后，玩家选择一枚可移动的棋子；
 * - 只有掷出 6 点才能让基地中的棋子起飞；掷出 6 点可再掷一次；
 * - 连续三次掷出 6 点后强制移交回合，避免无限连投；
 * - 落在跳格上会前进到对应格；落在对方棋子格会把对方撞回基地；
 * - 必须刚好走到第 57 格（中心）才算到达，超过则这枚棋子本回合不能走；
 * - 当前座位没有合法走法时必须显式「跳过」，AI 自动跳过；
 * - 先让 4 枚棋子全部到达终点者获胜。
 *
 * 编码约定（与前端共用）：
 * - 盘面值：side * 10 + pieceIndex（pieceIndex 0–3），0 为空；
 * - 手数记录：{side, pieceIndex, fromPos, toPos, dice, finished, kickedSide}；
 *   跳过记录为 {side, -1, -1, -1, dice, 0, 0}；
 * - 行动坐标：fromRow/fromCol = 被选棋子的棋盘格；row/col = 目标格。
 */
public class FlightChessBoard implements BoardGameEngine {

    public static final int ROWS = 15;
    public static final int COLS = 15;
    public static final int MAX_PLAYERS = 4;
    public static final int PIECES_PER_PLAYER = 4;
    public static final int TRACK_CELLS = 56;
    public static final int HOME_POS = TRACK_CELLS + 1;
    public static final int DICE_MAX = 6;
    public static final int MAX_CONSECUTIVE_SIXES = 3;

    /** 前端据此区分「等待掷骰」「选择棋子」「无棋可走」和终局。 */
    public enum Phase {
        ROLLING, MOVE, SKIP, FINISHED
    }

    /**
     * 四个基地各 2×2 格，每枚棋子占一格。
     * 四枚棋子不能挤在同一个锚点，否则盘面快照只能显示最后一枚，玩家也无法分别点选。
     */
    private static final int[][][] BASE_CELLS = {
        {{1, 1}, {1, 2}, {2, 1}, {2, 2}},
        {{1, 13}, {1, 12}, {2, 13}, {2, 12}},
        {{13, 1}, {13, 2}, {12, 1}, {12, 2}},
        {{13, 13}, {13, 12}, {12, 13}, {12, 12}}
    };
    /** 四个起飞格在环形跑道上的下标（0 起）。 */
    private static final int[] START_RINGS = {0, 14, 28, 42};
    private static final int HOME_ROW = 7;
    private static final int HOME_COL = 7;

    /** 跳格：pos -> 前进目标。只在前进方向设置，避免循环。 */
    private static final Map<Integer, Integer> JUMPS = new LinkedHashMap<>();
    static {
        JUMPS.put(6, 17);
        JUMPS.put(19, 30);
        JUMPS.put(33, 45);
        JUMPS.put(48, 54);
    }

    private Random random;
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private int playerCount = 2;
    private int currentTurn = 1;
    private int moveSeq;
    private boolean over;
    private Integer winner;
    private int dice;
    private int sixStreak;
    private int lastDice;
    private int lastKickedSide;
    private int lastJumpedFrom;
    private int lastJumpedTo;
    private int lastFinishedPiece = -1;
    private int passedTurns;
    /** 仅用于测试断言「开局只掷一次骰」，不参与规则判断。 */
    private int rollCount;

    /** 0 = 基地，1..56 = 跑道，57 = 终点。 */
    private final int[][] positions = new int[MAX_PLAYERS + 1][PIECES_PER_PLAYER];
    private final int[] finishedCount = new int[MAX_PLAYERS + 1];
    /** 认输/离场后不再参与轮转；其余玩家继续对局。 */
    private final boolean[] eliminated = new boolean[MAX_PLAYERS + 1];
    private final String[] names = new String[MAX_PLAYERS + 1];

    public FlightChessBoard() {
        this(new Random());
    }

    FlightChessBoard(Random random) {
        this.random = random;
        reset();
    }

    @Override
    public GameType type() { return GameType.FLIGHT_CHESS; }

    @Override
    public void configurePlayers(int count) {
        if (count < 2 || count > MAX_PLAYERS) throw new IllegalArgumentException("飞行棋支持 2–4 人");
        playerCount = count;
    }

    @Override
    public void reset() {
        history.clear();
        lastEffects.clear();
        log.clear();
        currentTurn = 1;
        moveSeq = 0;
        over = false;
        winner = null;
        dice = 0;
        sixStreak = 0;
        lastDice = 0;
        lastKickedSide = 0;
        lastJumpedFrom = 0;
        lastJumpedTo = 0;
        lastFinishedPiece = -1;
        passedTurns = 0;
        rollCount = 0;
        for (int side = 0; side <= MAX_PLAYERS; side++) {
            Arrays.fill(positions[side], 0);
            finishedCount[side] = 0;
            eliminated[side] = false;
            names[side] = "玩家" + side;
        }
        log.add("飞行棋开始：掷出 6 点起飞，绕行一圈后进入中心终点");
    }

    @Override
    public void setPlayerName(int side, String name) {
        if (side >= 1 && side <= MAX_PLAYERS && name != null && !name.isBlank()) names[side] = name;
    }

    /**
     * 房间在注入昵称后调用：掷出首回合骰子。
     * reset() 本身不掷骰，避免「构造/重置」与「真正开局」各掷一次导致首个骰子被覆盖。
     */
    public void beginTurnRoll() {
        if (over) return;
        sixStreak = 0;
        currentTurn = 1;
        rollDice();
        log.add(names[currentTurn] + " 首先掷出 " + dice + " 点");
    }

    @Override public int getRows() { return ROWS; }
    @Override public int getCols() { return COLS; }

    @Override
    public boolean placeStone(int row, int col, int side) {
        return false;
    }

    @Override
    public boolean placeStone(int fromRow, int fromCol, int toRow, int toCol, int side) {
        if (over || side != currentTurn || dice <= 0 || eliminated[side]) return false;
        int piece = findOwnPieceAt(side, fromRow, fromCol);
        if (piece < 0) return false;
        int targetPos = positionAt(toRow, toCol);
        if (targetPos < 0) return false;

        int fromPos = positions[side][piece];
        if (fromPos == 0) {
            if (dice != DICE_MAX) return false;
            int startPos = START_RINGS[side - 1] + 1;
            if (targetPos != startPos) return false;
            return movePiece(side, piece, startPos, true);
        }
        if (fromPos >= HOME_POS) return false;
        int destination = fromPos + dice;
        if (destination > HOME_POS) return false;
        if (destination == HOME_POS) {
            if (!(toRow == HOME_ROW && toCol == HOME_COL)) return false;
        } else if (targetPos != destination) {
            return false;
        }
        return movePiece(side, piece, destination, false);
    }

    private boolean movePiece(int side, int piece, int destination, boolean launched) {
        int fromPos = positions[side][piece];
        passedTurns = 0;
        lastDice = dice;
        lastKickedSide = 0;
        lastJumpedFrom = 0;
        lastJumpedTo = 0;
        lastEffects.clear();

        if (destination == HOME_POS) {
            positions[side][piece] = HOME_POS;
            finishedCount[side]++;
            lastFinishedPiece = piece;
            log.add(names[side] + " 的第 " + (piece + 1) + " 枚棋子到达终点");
            history.add(new int[]{side, piece, fromPos, HOME_POS, dice, 1, 0});
            if (finishedCount[side] >= PIECES_PER_PLAYER) {
                over = true;
                winner = side;
                log.add(names[side] + " 四枚棋子全部到达，赢得本局！");
            }
            moveSeq++;
            emitSnapshotEffects();
            if (!over) endTurnAfterMove();
            return true;
        }

        int landed = destination;
        positions[side][piece] = landed;
        if (JUMPS.containsKey(landed)) {
            int jumpTo = JUMPS.get(landed);
            lastJumpedFrom = landed;
            lastJumpedTo = jumpTo;
            landed = jumpTo;
            positions[side][piece] = landed;
            log.add(names[side] + " 触发跳格：" + lastJumpedFrom + " → " + lastJumpedTo);
        }

        int kicked = kickOpponents(side, landed);
        if (kicked > 0) lastKickedSide = kicked;

        log.add(names[side] + " 掷出 " + dice + " 点，" + (launched ? "棋子起飞" : "移动棋子")
                + "到第 " + landed + " 格" + (kicked > 0 ? "，撞回对手一枚棋子" : ""));
        history.add(new int[]{side, piece, fromPos, landed, dice, 0, kicked});
        moveSeq++;
        emitSnapshotEffects();
        endTurnAfterMove();
        return true;
    }

    /** 撞回落在该格上的所有对手棋子，返回第一个被撞的座位号。 */
    private int kickOpponents(int side, int landed) {
        if (landed < 1 || landed > TRACK_CELLS) return 0;
        int firstKickedSide = 0;
        for (int other = 1; other <= playerCount; other++) {
            if (other == side) continue;
            for (int p = 0; p < PIECES_PER_PLAYER; p++) {
                if (positions[other][p] == landed) {
                    positions[other][p] = 0;
                    if (firstKickedSide == 0) firstKickedSide = other;
                    log.add(names[other] + " 的棋子被撞回基地");
                }
            }
        }
        return firstKickedSide;
    }

    /** 掷出 6 点保留回合，否则换下一位；换人后自动掷骰。 */
    private void endTurnAfterMove() {
        if (dice == DICE_MAX) {
            sixStreak++;
            if (sixStreak >= MAX_CONSECUTIVE_SIXES) {
                log.add(names[currentTurn] + " 连续三次掷出 6 点，移交回合");
                sixStreak = 0;
                advanceToNextPlayer();
                rollDice();
                return;
            }
            rollDice();
            return;
        }
        sixStreak = 0;
        advanceToNextPlayer();
        rollDice();
    }

    private void advanceToNextPlayer() {
        int guard = 0;
        do {
            currentTurn = currentTurn % playerCount + 1;
            guard++;
        } while ((eliminated[currentTurn] || finishedCount[currentTurn] >= PIECES_PER_PLAYER)
                && guard <= MAX_PLAYERS * 2);
    }

    /** 当前座位是否有任何合法走法（用于显示「跳过」与房间 AI 调度）。 */
    public boolean hasLegalMove(int side) {
        if (over || side < 1 || side > playerCount || side != currentTurn || eliminated[side]
                || dice <= 0 || finishedCount[side] >= PIECES_PER_PLAYER) {
            return false;
        }
        for (int p = 0; p < PIECES_PER_PLAYER; p++) {
            int pos = positions[side][p];
            if (pos == 0) {
                if (dice == DICE_MAX) return true;
            } else if (pos < HOME_POS && pos + dice <= HOME_POS) {
                return true;
            }
        }
        return false;
    }

    /** 完整盘面快照：基地 + 跑道 + 终点。叠子由 getViewState().players 精确描述。 */
    @Override
    public int[][] getBoardSnapshot() {
        int[][] board = new int[ROWS][COLS];
        for (int side = 1; side <= playerCount; side++) {
            for (int p = 0; p < PIECES_PER_PLAYER; p++) {
                int pos = positions[side][p];
                int[] cell = cellFor(side, p, pos);
                if (board[cell[0]][cell[1]] == 0) board[cell[0]][cell[1]] = side * 10 + p;
            }
        }
        return board;
    }

    private int[] cellFor(int side, int piece, int pos) {
        if (pos == 0) return BASE_CELLS[side - 1][piece];
        if (pos >= HOME_POS) return new int[]{HOME_ROW, HOME_COL};
        return ringCell(pos);
    }

    /** 跑道位置 1..56 → 环形格坐标。 */
    static int[] ringCell(int pos) {
        int ring = pos - 1;
        if (ring < 15) return new int[]{0, ring};
        if (ring < 29) return new int[]{ring - 14, 14};
        if (ring < 43) return new int[]{14, 42 - ring};
        return new int[]{56 - ring, 0};
    }

    /** 环形格坐标 → 跑道位置 1..56；不是跑道格返回 -1。 */
    static int ringPosition(int row, int col) {
        if (row == 0 && col >= 0 && col < 15) return col + 1;
        if (col == 14 && row >= 1 && row < 15) return 14 + row + 1;
        if (row == 14 && col >= 0 && col < 14) return 42 - col + 1;
        if (col == 0 && row >= 1 && row < 14) return 56 - row + 1;
        return -1;
    }

    /** 该格对应的跑道位置；终点返回 HOME_POS，基地返回 0，其他返回 -1。 */
    private int positionAt(int row, int col) {
        if (row == HOME_ROW && col == HOME_COL) return HOME_POS;
        for (int side = 1; side <= playerCount; side++) {
            for (int p = 0; p < PIECES_PER_PLAYER; p++) {
                if (BASE_CELLS[side - 1][p][0] == row && BASE_CELLS[side - 1][p][1] == col) return 0;
            }
        }
        return ringPosition(row, col);
    }

    /** 找出该格上的己方棋子；基地格返回最小编号未完成棋子。 */
    public int findOwnPieceAt(int side, int row, int col) {
        if (side < 1 || side > playerCount) return -1;
        for (int p = 0; p < PIECES_PER_PLAYER; p++) {
            int[] cell = cellFor(side, p, positions[side][p]);
            if (cell[0] == row && cell[1] == col) return p;
        }
        return -1;
    }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        return findOwnPieceAt(side, row, col) >= 0;
    }

    /** 客户端只在自己的回合看到合法起点/目标。 */
    public List<int[]> legalMoves(int side) {
        List<int[]> moves = new ArrayList<>();
        if (over || side != currentTurn || dice <= 0 || eliminated[side]) return moves;
        for (int p = 0; p < PIECES_PER_PLAYER; p++) {
            int pos = positions[side][p];
            int[] from = cellFor(side, p, pos);
            if (pos == 0) {
                if (dice == DICE_MAX) {
                    int[] to = ringCell(START_RINGS[side - 1] + 1);
                    moves.add(new int[]{from[0], from[1], to[0], to[1], p});
                }
            } else if (pos < HOME_POS) {
                int destination = pos + dice;
                if (destination == HOME_POS) {
                    moves.add(new int[]{from[0], from[1], HOME_ROW, HOME_COL, p});
                } else if (destination < HOME_POS) {
                    int[] to = ringCell(destination);
                    moves.add(new int[]{from[0], from[1], to[0], to[1], p});
                }
            }
        }
        return moves;
    }

    /** 全量快照效果：先清空所有可见格，再写回全部棋子。 */
    private void emitSnapshotEffects() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                boolean visible = ringPosition(r, c) > 0 || (r == HOME_ROW && c == HOME_COL);
                for (int side = 1; side <= playerCount && !visible; side++) {
                    for (int p = 0; p < PIECES_PER_PLAYER && !visible; p++) {
                        visible = BASE_CELLS[side - 1][p][0] == r && BASE_CELLS[side - 1][p][1] == c;
                    }
                }
                if (visible) lastEffects.add(new int[]{r, c, 0});
            }
        }
        for (int side = 1; side <= playerCount; side++) {
            for (int p = 0; p < PIECES_PER_PLAYER; p++) {
                int[] cell = cellFor(side, p, positions[side][p]);
                lastEffects.add(new int[]{cell[0], cell[1], side * 10 + p});
            }
        }
    }

    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }
    @Override public List<int[]> getMoveHistory() { return Collections.unmodifiableList(history); }
    @Override public List<int[]> getLastMoveEffects() { return lastEffects; }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return moveSeq; }
    @Override public int getStepTimeSeconds() { return 30; }
    @Override public int getTotalTimeSeconds() { return 600; }

    @Override
    public String getScoreSummary() {
        StringBuilder sb = new StringBuilder();
        List<Integer> order = getRanking();
        for (int i = 0; i < order.size(); i++) {
            int side = order.get(i);
            if (i > 0) sb.append(" · ");
            sb.append(names[side]).append(' ').append(finishedCount[side]).append("/4");
        }
        return sb.toString();
    }

    /** 按完成数、剩余距离排序；认输者排在最后，用于多人局终局的完整名次。 */
    public List<Integer> getRanking() {
        List<Integer> active = new ArrayList<>();
        List<Integer> quit = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            if (eliminated[side]) quit.add(side); else active.add(side);
        }
        active.sort(Comparator
                .comparingInt((Integer side) -> -finishedCount[side])
                .thenComparingInt(this::remainingDistance));
        active.addAll(quit);
        return active;
    }

    private int remainingDistance(int side) {
        int total = 0;
        for (int p = 0; p < PIECES_PER_PLAYER; p++) {
            if (positions[side][p] < HOME_POS) total += Math.max(0, HOME_POS - positions[side][p]);
        }
        return total;
    }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("turn", currentTurn);
        view.put("dice", dice);
        view.put("phase", getPhase().name());
        view.put("canSkip", !over && viewerSide == currentTurn && dice > 0 && !hasLegalMove(currentTurn));
        view.put("lastDice", lastDice);
        view.put("lastKickedSide", lastKickedSide);
        view.put("lastJumpedFrom", lastJumpedFrom);
        view.put("lastJumpedTo", lastJumpedTo);
        view.put("lastFinishedPiece", lastFinishedPiece);
        view.put("playerCount", playerCount);
        view.put("over", over);
        view.put("winner", winner);
        view.put("consecutiveSixes", sixStreak);
        view.put("passedTurns", passedTurns);
        view.put("ranking", getRanking());

        List<Map<String, Object>> moves = new ArrayList<>();
        if (viewerSide >= 1 && viewerSide <= playerCount) {
            for (int[] move : legalMoves(viewerSide)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fromRow", move[0]);
                item.put("fromCol", move[1]);
                item.put("toRow", move[2]);
                item.put("toCol", move[3]);
                item.put("piece", move[4]);
                moves.add(item);
            }
        }
        view.put("legalMoves", moves);

        List<Map<String, Object>> players = new ArrayList<>();
        for (int side = 1; side <= playerCount; side++) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("side", side);
            player.put("name", names[side]);
            player.put("positions", positions[side].clone());
            player.put("finishedCount", finishedCount[side]);
            player.put("remainingDistance", remainingDistance(side));
            player.put("active", !over && !eliminated[side] && side == currentTurn);
            player.put("eliminated", eliminated[side]);
            List<Boolean> finished = new ArrayList<>();
            for (int p = 0; p < PIECES_PER_PLAYER; p++) finished.add(positions[side][p] >= HOME_POS);
            player.put("finished", finished);
            players.add(player);
        }
        view.put("players", players);
        view.put("log", new ArrayList<>(log.subList(Math.max(0, log.size() - 6), log.size())));
        return view;
    }

    public int getPlayerCount() { return playerCount; }
    public int getDice() { return dice; }
    public int getPiecePosition(int side, int piece) { return positions[side][piece]; }
    public boolean isPieceFinished(int side, int piece) { return positions[side][piece] >= HOME_POS; }
    public int getFinishedCount(int side) { return finishedCount[side]; }

    /** AI 评估撞子收益用：目标跑道格上是否有其他仍在场的玩家棋子。 */
    public boolean hasOpponentAt(int position, int side) {
        if (position < 1 || position > TRACK_CELLS) return false;
        for (int other = 1; other <= playerCount; other++) {
            if (other == side || eliminated[other]) continue;
            for (int p = 0; p < PIECES_PER_PLAYER; p++) {
                if (positions[other][p] == position) return true;
            }
        }
        return false;
    }

    public Phase getPhase() {
        if (over) return Phase.FINISHED;
        if (dice <= 0) return Phase.ROLLING;
        return hasLegalMove(currentTurn) ? Phase.MOVE : Phase.SKIP;
    }

    /** 房间确认当前座位确实没有合法走法后，推进到下一位并重新掷骰。 */
    public void advanceTurnForStuckSeat() {
        skipTurn(currentTurn);
    }

    /** 当前玩家无棋可走时显式跳过；掷出 6 点仍无棋可走时也移交回合。 */
    public boolean skipTurn(int side) {
        if (over || side != currentTurn || dice <= 0 || eliminated[side] || hasLegalMove(side)) return false;
        log.add(names[side] + " 掷出 " + dice + " 点，无棋可走");
        history.add(new int[]{side, -1, -1, -1, dice, 0, 0});
        lastDice = dice;
        lastEffects.clear();
        passedTurns++;
        sixStreak = 0;
        moveSeq++;
        advanceToNextPlayer();
        rollDice();
        return true;
    }

    /** 飞行棋的「跳过」复用通用停一手通道。 */
    @Override
    public boolean pass(int side) {
        return skipTurn(side);
    }

    private void rollDice() {
        dice = random.nextInt(DICE_MAX) + 1;
        rollCount++;
    }

    /**
     * 多人局有人认输/离场时只淘汰该座位，其余玩家继续；
     * 只剩最后一人时由该玩家获胜。返回 true 表示本局仍可继续或已由引擎终局。
     */
    @Override
    public boolean forfeit(int side) {
        if (over || side < 1 || side > playerCount || eliminated[side]) return false;
        eliminated[side] = true;
        Arrays.fill(positions[side], 0);
        finishedCount[side] = 0;
        lastEffects.clear();
        log.add(names[side] + " 退出本局");

        int remaining = 0;
        int lastActive = 0;
        for (int s = 1; s <= playerCount; s++) {
            if (!eliminated[s]) { remaining++; lastActive = s; }
        }
        if (remaining <= 1) {
            over = true;
            winner = remaining == 1 ? lastActive : null;
            if (winner != null) log.add(names[winner] + " 成为最后留在场上的玩家，赢得本局！");
            return true;
        }
        if (side == currentTurn) {
            advanceToNextPlayer();
            rollDice();
        }
        return true;
    }

    /** AI 计算跳格收益时读取规则表（不暴露可变引用）。 */
    static Integer jumpTargetForTest(int position) {
        return JUMPS.get(position);
    }

    // ============ 测试辅助（包内可见，不参与线上路径） ============

    void setDiceForTest(int value) {
        dice = value;
    }

    void setPositionForTest(int side, int piece, int position) {
        boolean wasFinished = positions[side][piece] >= HOME_POS;
        positions[side][piece] = position;
        boolean nowFinished = position >= HOME_POS;
        if (!wasFinished && nowFinished) finishedCount[side]++;
        if (wasFinished && !nowFinished) finishedCount[side]--;
    }

    void setFinishedCountForTest(int side, int count) {
        finishedCount[side] = count;
    }

    void setTurnForTest(int side) {
        currentTurn = side;
    }

    boolean isEliminatedForTest(int side) {
        return eliminated[side];
    }

    void setRandomForTest(Random random) {
        this.random = random;
    }

    int getRollCountForTest() {
        return rollCount;
    }
}
