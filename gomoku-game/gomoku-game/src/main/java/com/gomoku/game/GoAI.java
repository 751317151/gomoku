package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 围棋 AI — 启发式评估（提子/逃叫吃/叫吃威胁/扩张/避免填真眼/避免自撞一气）
 *
 * 不做深度搜索（9 路休闲对局够用），难度控制选择随机性：
 * 2=简单（前12候选随机） 4=中等（前4随机） 6=困难（最优）
 */
public class GoAI implements GameAI {

    static final GoAI INSTANCE = new GoAI();

    private static final int SIZE = GoBoard.SIZE;
    private static final int EMPTY = GoBoard.EMPTY;
    private static final int BLACK = GoBoard.BLACK;
    private static final int WHITE = GoBoard.WHITE;

    private static final int[][] NEIGHBORS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    // 评分权重
    private static final int SCORE_CAPTURE = 10000;      // 每提一子
    private static final int SCORE_RESCUE = 8000;        // 解救叫吃中的己方块
    private static final int SCORE_ATARI_THREAT = 300;   // 叫吃威胁
    private static final int SCORE_SELF_ATARI = -4000;   // 自撞一气
    private static final int SCORE_FILL_OWN_EYE = -50000;// 填自己真眼
    private static final int SCORE_CENTER = 60;          // 中心偏好
    private static final int SCORE_NEIGHBOR_STONE = 25;  // 靠近已有棋子

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof GoBoard)) return null;
        GoBoard go = (GoBoard) engine;
        int[][] board = go.getBoardSnapshot();

        // 收集所有合法点（引擎过滤自杀/劫）
        List<int[]> candidates = new ArrayList<>();
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == EMPTY && go.isLegalMove(r, c, side)) {
                    candidates.add(new int[]{r, c});
                }
            }
        }
        if (candidates.isEmpty()) return null; // 无合法点 → 由房间决定停一手

        // 空棋盘：下在靠近天元的位置
        if (go.getMoveCount() == 0) {
            return new int[]{SIZE / 2, SIZE / 2};
        }

        // 候选评分
        List<long[]> scored = new ArrayList<>(); // {row, col, score}
        for (int[] cand : candidates) {
            int r = cand[0], c = cand[1];
            long score = evaluate(board, r, c, side);
            scored.add(new long[]{r, c, score});
        }
        scored.sort((a, b) -> Long.compare(b[2], a[2]));

        int pool = randomPool(difficulty);
        int pick = pool <= 1 ? 0 : ThreadLocalRandom.current().nextInt(Math.min(pool, scored.size()));
        return new int[]{(int) scored.get(pick)[0], (int) scored.get(pick)[1]};
    }

    private int randomPool(int difficulty) {
        switch (difficulty) {
            case 2: return 12;
            case 6: return 1;
            default: return 4;
        }
    }

    /** 评估在 (row, col) 落 side 子的价值 */
    private long evaluate(int[][] board, int row, int col, int side) {
        int opponent = (side == BLACK) ? WHITE : BLACK;

        // 模拟落子
        int[][] sim = snapshot(board);
        sim[row][col] = side;
        int captured = removeDeadOpponentGroups(sim, row, col, opponent);

        // 落子后己方连通块
        List<int[]> ownGroup = findGroup(sim, row, col);
        int ownLibs = libertiesOf(sim, ownGroup);

        long score = 0;

        // 1. 提子
        score += (long) captured * SCORE_CAPTURE;

        // 2. 解救叫吃中的己方块（相邻己方块落子前仅 1 气，落子后整体 >= 2 气）
        int atariGroups = 0;
        for (int[] n : NEIGHBORS) {
            int nr = row + n[0], nc = col + n[1];
            if (inBounds(nr, nc) && board[nr][nc] == side) {
                List<int[]> g = findGroup(board, nr, nc);
                if (libertiesOf(board, g) == 1) atariGroups++;
            }
        }
        if (atariGroups > 0 && ownLibs >= 2) {
            score += (long) atariGroups * SCORE_RESCUE;
        }

        // 3. 自撞一气（无提子的自杀式紧气）
        if (ownLibs <= 1 && captured == 0) {
            score += SCORE_SELF_ATARI;
        }

        // 4. 叫吃威胁：对方相邻块被压到 1 气
        for (int[] n : NEIGHBORS) {
            int nr = row + n[0], nc = col + n[1];
            if (inBounds(nr, nc) && sim[nr][nc] == opponent) {
                List<int[]> g = findGroup(sim, nr, nc);
                int libs = libertiesOf(sim, g);
                if (libs == 1) score += SCORE_ATARI_THREAT;
                else if (libs == 2) score += SCORE_ATARI_THREAT / 3;
            }
        }

        // 5. 填自己真眼（四邻全是己方/边界）
        if (captured == 0 && isOwnEye(board, row, col, side)) {
            score += SCORE_FILL_OWN_EYE;
        }

        // 6. 扩张：靠近已有棋子
        int nearScore = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == EMPTY) continue;
                int dist = Math.max(Math.abs(r - row), Math.abs(c - col));
                if (dist == 1) nearScore += SCORE_NEIGHBOR_STONE;
                else if (dist == 2) nearScore += SCORE_NEIGHBOR_STONE / 3;
            }
        }
        score += nearScore;

        // 7. 中心偏好 + 早期布局（第 3/4 线加成）
        int centerDist = Math.abs(row - (SIZE - 1) / 2) + Math.abs(col - (SIZE - 1) / 2);
        score += (SIZE - centerDist) * SCORE_CENTER / 2;
        int edgeDist = Math.min(Math.min(row, SIZE - 1 - row), Math.min(col, SIZE - 1 - col));
        if (edgeDist == 2 || edgeDist == 3) score += 40;
        else if (edgeDist == 0) score -= 30;

        // 少量随机扰动，避免每局完全一样
        score += ThreadLocalRandom.current().nextInt(15);
        return score;
    }

    /** 四邻全是己方棋子（或边界）→ 近似真眼 */
    private boolean isOwnEye(int[][] board, int row, int col, int side) {
        for (int[] n : NEIGHBORS) {
            int nr = row + n[0], nc = col + n[1];
            if (!inBounds(nr, nc)) continue;
            if (board[nr][nc] != side) return false;
        }
        return true;
    }

    /** 移除 (row,col) 周围无气的对方连通块，返回提子数（修改 sim） */
    private int removeDeadOpponentGroups(int[][] sim, int row, int col, int opponent) {
        int captured = 0;
        boolean[][] handled = new boolean[SIZE][SIZE];
        for (int[] n : NEIGHBORS) {
            int nr = row + n[0], nc = col + n[1];
            if (!inBounds(nr, nc) || sim[nr][nc] != opponent || handled[nr][nc]) continue;
            List<int[]> group = findGroup(sim, nr, nc);
            for (int[] p : group) handled[p[0]][p[1]] = true;
            if (libertiesOf(sim, group) == 0) {
                for (int[] p : group) {
                    sim[p[0]][p[1]] = EMPTY;
                    captured++;
                }
            }
        }
        return captured;
    }

    private List<int[]> findGroup(int[][] board, int row, int col) {
        int color = board[row][col];
        List<int[]> group = new ArrayList<>();
        boolean[][] visited = new boolean[SIZE][SIZE];
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[]{row, col});
        visited[row][col] = true;
        while (!stack.isEmpty()) {
            int[] p = stack.remove(stack.size() - 1);
            group.add(p);
            for (int[] n : NEIGHBORS) {
                int nr = p[0] + n[0], nc = p[1] + n[1];
                if (inBounds(nr, nc) && !visited[nr][nc] && board[nr][nc] == color) {
                    visited[nr][nc] = true;
                    stack.add(new int[]{nr, nc});
                }
            }
        }
        return group;
    }

    private int libertiesOf(int[][] board, List<int[]> group) {
        boolean[][] counted = new boolean[SIZE][SIZE];
        int liberties = 0;
        for (int[] p : group) {
            for (int[] n : NEIGHBORS) {
                int nr = p[0] + n[0], nc = p[1] + n[1];
                if (inBounds(nr, nc) && board[nr][nc] == EMPTY && !counted[nr][nc]) {
                    counted[nr][nc] = true;
                    liberties++;
                }
            }
        }
        return liberties;
    }

    private static int[][] snapshot(int[][] src) {
        int[][] dst = new int[SIZE][];
        for (int i = 0; i < SIZE; i++) dst[i] = src[i].clone();
        return dst;
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }
}
