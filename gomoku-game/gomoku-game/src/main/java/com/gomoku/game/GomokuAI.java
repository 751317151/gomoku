package com.gomoku.game;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 五子棋 AI 引擎（Minimax + Alpha-Beta 剪枝 + 方向级评估）
 */
public class GomokuAI {

    private static final Logger logger = LoggerFactory.getLogger(GomokuAI.class);

    // 评分常量 — 冲四 > 活三 > 眠三，确保紧迫性正确
    private static final int SCORE_FIVE = 100000;
    private static final int SCORE_LIVE_FOUR = 50000;
    private static final int SCORE_RUSH_FOUR = 10000;
    private static final int SCORE_LIVE_THREE = 5000;
    private static final int SCORE_SLEEP_THREE = 500;
    private static final int SCORE_LIVE_TWO = 500;
    private static final int SCORE_SLEEP_TWO = 50;
    private static final int SCORE_LIVE_ONE = 50;
    private static final int SCORE_SLEEP_ONE = 5;

    // 防守权重：堵截对手比进攻略高
    private static final double DEFENSE_WEIGHT = 1.1;

    // 搜索深度范围（动态调整）
    private static final int SEARCH_DEPTH_MIN = 2;
    private static final int SEARCH_DEPTH_MAX = 6;
    // 候选数阈值
    private static final int CANDIDATE_HIGH = 40;
    private static final int CANDIDATE_LOW = 20;

    // 候选位置搜索范围
    private static final int SEARCH_RANGE = 2;

    // 最大候选数（限制搜索宽度）
    private static final int MAX_CANDIDATES = 20;

    // 四个方向：横、竖、左斜、右斜
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    public static int[] getBestMove(GomokuBoard boardObj, int aiStone) {
        return getBestMove(boardObj, aiStone, -1);
    }

    /**
     * @param maxDepth 指定最大搜索深度（-1 = 自动动态调整）
     */
    public static int[] getBestMove(GomokuBoard boardObj, int aiStone, int maxDepth) {
        // 使用快照避免竞态条件
        int[][] board = boardObj.getBoardSnapshot();
        int humanStone = (aiStone == GomokuBoard.BLACK) ? GomokuBoard.WHITE : GomokuBoard.BLACK;

        // 棋盘为空时下天元
        if (boardObj.getMoveCount() == 0) {
            return new int[]{GomokuBoard.SIZE / 2, GomokuBoard.SIZE / 2};
        }

        // 只有一个子时下邻近位置
        if (boardObj.getMoveCount() == 1) {
            return findAdjacentMove(board, boardObj.getMoveHistory().get(0)[0],
                    boardObj.getMoveHistory().get(0)[1]);
        }

        // 获取候选位置
        List<int[]> candidates = getCandidates(board);
        if (candidates.isEmpty()) {
            return findAnyEmpty(board);
        }

        // 先检查是否有必胜/必防点（限定在候选位置内）
        int[] urgentMove = findUrgentMove(board, candidates, aiStone, humanStone);
        if (urgentMove != null) {
            return urgentMove;
        }

        // 着法排序：按启发式评分降序，提升 Alpha-Beta 剪枝效率
        candidates = sortCandidates(board, candidates, aiStone, humanStone);

        // 搜索深度：指定 or 动态调整
        int searchDepth;
        if (maxDepth > 0) {
            searchDepth = Math.min(maxDepth, SEARCH_DEPTH_MAX);
        } else {
            searchDepth = calculateSearchDepth(candidates.size());
        }
        logger.info("AI搜索: candidates={}, depth={}", candidates.size(), searchDepth);

        // Minimax 搜索
        int bestScore = Integer.MIN_VALUE;
        int bestRow = -1;
        int bestCol = -1;

        for (int[] pos : candidates) {
            int r = pos[0], c = pos[1];
            board[r][c] = aiStone;

            int score = minimax(board, searchDepth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE, false, aiStone, humanStone, searchDepth);

            board[r][c] = GomokuBoard.EMPTY;

            if (score > bestScore) {
                bestScore = score;
                bestRow = r;
                bestCol = c;
            }
        }

        if (bestRow == -1) {
            return findAnyEmpty(board);
        }
        return new int[]{bestRow, bestCol};
    }

    /**
     * 根据候选点数量动态计算搜索深度
     */
    private static int calculateSearchDepth(int candidateCount) {
        if (candidateCount > CANDIDATE_HIGH) return SEARCH_DEPTH_MIN;
        if (candidateCount > CANDIDATE_LOW) return 4;
        return SEARCH_DEPTH_MAX;
    }

    /**
     * Minimax + Alpha-Beta 剪枝
     */
    private static int minimax(int[][] board, int depth, int alpha, int beta,
                               boolean isMaximizing, int aiStone, int humanStone, int rootDepth) {
        if (depth <= 0) {
            return evaluateBoard(board, aiStone, humanStone);
        }

        List<int[]> candidates = getCandidates(board);

        if (candidates.isEmpty()) {
            return evaluateBoard(board, aiStone, humanStone);
        }

        // 深层搜索时限制候选数，提升性能
        if (depth < rootDepth - 1) {
            candidates = sortCandidates(board, candidates, aiStone, humanStone);
            if (candidates.size() > MAX_CANDIDATES) {
                candidates = candidates.subList(0, MAX_CANDIDATES);
            }
        }

        if (isMaximizing) {
            int maxEval = Integer.MIN_VALUE;
            for (int[] pos : candidates) {
                int r = pos[0], c = pos[1];
                board[r][c] = aiStone;

                if (checkWinAt(board, r, c, aiStone)) {
                    board[r][c] = GomokuBoard.EMPTY;
                    return SCORE_FIVE * 10;
                }

                int eval = minimax(board, depth - 1, alpha, beta, false, aiStone, humanStone, rootDepth);
                board[r][c] = GomokuBoard.EMPTY;

                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return maxEval;
        } else {
            int minEval = Integer.MAX_VALUE;
            for (int[] pos : candidates) {
                int r = pos[0], c = pos[1];
                board[r][c] = humanStone;

                if (checkWinAt(board, r, c, humanStone)) {
                    board[r][c] = GomokuBoard.EMPTY;
                    return -SCORE_FIVE * 10;
                }

                int eval = minimax(board, depth - 1, alpha, beta, true, aiStone, humanStone, rootDepth);
                board[r][c] = GomokuBoard.EMPTY;

                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
            return minEval;
        }
    }

    /**
     * 全局棋盘评估 — 方向级评估，避免重复计算
     * 对每个方向只从连续同色棋子群的起始位置评估一次
     */
    private static int evaluateBoard(int[][] board, int aiStone, int humanStone) {
        int aiScore = 0;
        int humanScore = 0;

        // 使用 visited 集合避免同一连续线段被重复评估
        Set<String> evaluated = new HashSet<>();

        for (int r = 0; r < GomokuBoard.SIZE; r++) {
            for (int c = 0; c < GomokuBoard.SIZE; c++) {
                if (board[r][c] == aiStone) {
                    aiScore += evaluatePosition(board, r, c, aiStone, evaluated);
                } else if (board[r][c] == humanStone) {
                    humanScore += evaluatePosition(board, r, c, humanStone, evaluated);
                }
            }
        }

        return (int) (aiScore - humanScore * DEFENSE_WEIGHT);
    }

    /**
     * 单个位置的方向级评估（带去重）
     * 只从连续同色棋子群的起始位置评估，避免一条四连被 4 个棋子各算一次
     */
    private static int evaluatePosition(int[][] board, int r, int c, int stone, Set<String> evaluated) {
        int score = 0;
        for (int[] d : DIRECTIONS) {
            int dr = d[0], dc = d[1];
            // 找到该方向上连续同色棋子群的起始位置
            int sr = r, sc = c;
            while (true) {
                int pr = sr - dr, pc = sc - dc;
                if (pr >= 0 && pr < GomokuBoard.SIZE && pc >= 0 && pc < GomokuBoard.SIZE && board[pr][pc] == stone) {
                    sr = pr;
                    sc = pc;
                } else {
                    break;
                }
            }
            // 用起始位置+方向做去重 key
            String key = sr + "," + sc + "," + dr + "," + dc;
            if (evaluated.add(key)) {
                score += evaluateDirection(board, sr, sc, dr, dc, stone);
            }
        }
        return score;
    }

    /**
     * 方向评估（从连续同色棋子群起始位置开始）
     */
    private static int evaluateDirection(int[][] board, int r, int c, int dr, int dc, int stone) {
        int count = 0;
        // 正方向计数
        int i = r, j = c;
        while (i >= 0 && i < GomokuBoard.SIZE && j >= 0 && j < GomokuBoard.SIZE && board[i][j] == stone) {
            count++;
            i += dr;
            j += dc;
        }

        int blocked = 0;
        int empty1 = 0; // 正方向第一空位后是否还有同色子（跳活）

        // 正方向端点
        if (i < 0 || i >= GomokuBoard.SIZE || j < 0 || j >= GomokuBoard.SIZE) {
            blocked++;
        } else if (board[i][j] != GomokuBoard.EMPTY) {
            blocked++;
        } else {
            // 检查跳活
            int ni = i + dr, nj = j + dc;
            if (ni >= 0 && ni < GomokuBoard.SIZE && nj >= 0 && nj < GomokuBoard.SIZE && board[ni][nj] == stone) {
                empty1 = 1;
            }
        }

        // 反方向端点
        int ri = r - dr, rj = c - dc;
        if (ri < 0 || ri >= GomokuBoard.SIZE || rj < 0 || rj >= GomokuBoard.SIZE) {
            blocked++;
        } else if (board[ri][rj] != GomokuBoard.EMPTY) {
            blocked++;
        } else {
            int ni = ri - dr, nj = rj - dc;
            if (ni >= 0 && ni < GomokuBoard.SIZE && nj >= 0 && nj < GomokuBoard.SIZE && board[ni][nj] == stone) {
                empty1 = 1;
            }
        }

        // 评分
        if (count >= 5) return SCORE_FIVE;
        if (blocked == 2) return 0;

        switch (count) {
            case 4:
                return blocked == 0 ? SCORE_LIVE_FOUR : SCORE_RUSH_FOUR;
            case 3:
                if (empty1 > 0) return SCORE_LIVE_THREE;
                return blocked == 0 ? SCORE_LIVE_THREE : SCORE_SLEEP_THREE;
            case 2:
                if (empty1 > 0) return SCORE_LIVE_TWO;
                return blocked == 0 ? SCORE_LIVE_TWO : SCORE_SLEEP_TWO;
            case 1:
                if (empty1 > 0) return SCORE_LIVE_ONE;
                return blocked == 0 ? SCORE_LIVE_ONE : SCORE_SLEEP_ONE;
            default:
                return 0;
        }
    }

    /**
     * 快速检查某位置是否形成五连
     */
    private static boolean checkWinAt(int[][] board, int row, int col, int stone) {
        for (int[] dir : DIRECTIONS) {
            int count = 1;
            count += countDir(board, row, col, dir[0], dir[1], stone);
            count += countDir(board, row, col, -dir[0], -dir[1], stone);
            if (count >= 5) return true;
        }
        return false;
    }

    private static int countDir(int[][] board, int row, int col, int dr, int dc, int stone) {
        int count = 0;
        int r = row + dr, c = col + dc;
        while (r >= 0 && r < GomokuBoard.SIZE && c >= 0 && c < GomokuBoard.SIZE && board[r][c] == stone) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    /**
     * 获取候选落子位置（已有棋子附近）
     */
    private static List<int[]> getCandidates(int[][] board) {
        Set<String> visited = new HashSet<>();
        List<int[]> candidates = new ArrayList<>();

        for (int r = 0; r < GomokuBoard.SIZE; r++) {
            for (int c = 0; c < GomokuBoard.SIZE; c++) {
                if (board[r][c] != GomokuBoard.EMPTY) {
                    for (int dr = -SEARCH_RANGE; dr <= SEARCH_RANGE; dr++) {
                        for (int dc = -SEARCH_RANGE; dc <= SEARCH_RANGE; dc++) {
                            int nr = r + dr, nc = c + dc;
                            if (nr >= 0 && nr < GomokuBoard.SIZE && nc >= 0 && nc < GomokuBoard.SIZE
                                    && board[nr][nc] == GomokuBoard.EMPTY) {
                                String key = nr + "," + nc;
                                if (visited.add(key)) {
                                    candidates.add(new int[]{nr, nc});
                                }
                            }
                        }
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * 着法排序：按启发式评分降序，提升 Alpha-Beta 剪枝效率
     * 对每个候选位置模拟落子，快速评估后排序
     */
    private static List<int[]> sortCandidates(int[][] board, List<int[]> candidates, int aiStone, int humanStone) {
        List<int[]> scored = new ArrayList<>();
        for (int[] pos : candidates) {
            int r = pos[0], c = pos[1];
            int attackScore = quickEval(board, r, c, aiStone);
            int defenseScore = quickEval(board, r, c, humanStone);
            int totalScore = attackScore + defenseScore;
            scored.add(new int[]{r, c, totalScore});
        }
        scored.sort((a, b) -> b[2] - a[2]);
        List<int[]> result = new ArrayList<>();
        for (int[] s : scored) {
            result.add(new int[]{s[0], s[1]});
        }
        return result;
    }

    /**
     * 快速评估某位置的攻击/防守价值
     */
    private static int quickEval(int[][] board, int r, int c, int stone) {
        int score = 0;
        board[r][c] = stone;
        for (int[] d : DIRECTIONS) {
            int count = 1;
            count += countDir(board, r, c, d[0], d[1], stone);
            count += countDir(board, r, c, -d[0], -d[1], stone);
            if (count >= 5) score += SCORE_FIVE;
            else if (count == 4) score += SCORE_LIVE_FOUR / 10;
            else if (count == 3) score += SCORE_LIVE_THREE / 10;
            else if (count == 2) score += SCORE_LIVE_TWO / 10;
        }
        board[r][c] = GomokuBoard.EMPTY;
        return score;
    }

    /**
     * 查找紧急落子点（必胜/必防），限定在候选位置范围内
     */
    private static int[] findUrgentMove(int[][] board, List<int[]> candidates, int aiStone, int humanStone) {
        // 1. AI 能否直接五连
        for (int[] pos : candidates) {
            int r = pos[0], c = pos[1];
            board[r][c] = aiStone;
            if (checkWinAt(board, r, c, aiStone)) {
                board[r][c] = GomokuBoard.EMPTY;
                return new int[]{r, c};
            }
            board[r][c] = GomokuBoard.EMPTY;
        }

        // 2. 对手能否直接五连（必须堵）
        for (int[] pos : candidates) {
            int r = pos[0], c = pos[1];
            board[r][c] = humanStone;
            if (checkWinAt(board, r, c, humanStone)) {
                board[r][c] = GomokuBoard.EMPTY;
                return new int[]{r, c};
            }
            board[r][c] = GomokuBoard.EMPTY;
        }

        return null;
    }

    private static int[] findAdjacentMove(int[][] board, int row, int col) {
        int[][] offsets = {{0, 1}, {1, 0}, {1, 1}, {-1, -1}, {0, -1}, {-1, 0}, {1, -1}, {-1, 1}};
        for (int[] o : offsets) {
            int nr = row + o[0], nc = col + o[1];
            if (nr >= 0 && nr < GomokuBoard.SIZE && nc >= 0 && nc < GomokuBoard.SIZE
                    && board[nr][nc] == GomokuBoard.EMPTY) {
                return new int[]{nr, nc};
            }
        }
        return new int[]{GomokuBoard.SIZE / 2, GomokuBoard.SIZE / 2};
    }

    private static int[] findAnyEmpty(int[][] board) {
        for (int r = 0; r < GomokuBoard.SIZE; r++) {
            for (int c = 0; c < GomokuBoard.SIZE; c++) {
                if (board[r][c] == GomokuBoard.EMPTY) return new int[]{r, c};
            }
        }
        return new int[]{GomokuBoard.SIZE / 2, GomokuBoard.SIZE / 2};
    }
}
