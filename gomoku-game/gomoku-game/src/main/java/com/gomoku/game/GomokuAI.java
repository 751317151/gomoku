package com.gomoku.game;

/**
 * 五子棋 AI 引擎（基于启发式评分规则）
 */
public class GomokuAI {

    public static int[] getBestMove(GomokuBoard boardObj, int aiStone) {
        int[][] board = boardObj.getBoard();
        int size = GomokuBoard.SIZE;
        int humanStone = (aiStone == GomokuBoard.BLACK) ? GomokuBoard.WHITE : GomokuBoard.BLACK;

        int bestScore = -1;
        int bestRow = -1;
        int bestCol = -1;

        // 遍历所有空位置进行评分
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (board[r][c] == GomokuBoard.EMPTY) {
                    int aiScore = evaluatePosition(board, r, c, aiStone);
                    int humanScore = evaluatePosition(board, r, c, humanStone);
                    
                    // 防守权重大于进攻，优先堵截玩家
                    int totalScore = aiScore + (int)(humanScore * 1.2); 

                    if (totalScore > bestScore) {
                        bestScore = totalScore;
                        bestRow = r;
                        bestCol = c;
                    }
                }
            }
        }

        // 如果没有找到有效步数（通常只有开局第一步时全为0分），则随机下在中心附近
        if (bestScore == 0 && bestRow == -1) {
             return new int[]{size / 2, size / 2};
        }
        
        if (bestRow == -1) {
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    if (board[r][c] == GomokuBoard.EMPTY) return new int[]{r, c};
                }
            }
        }
        return new int[]{bestRow, bestCol};
    }

    /**
     * 评估在指定位置落子的价值
     */
    private static int evaluatePosition(int[][] board, int r, int c, int stone) {
        int score = 0;
        int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
        for (int[] d : dirs) {
            score += evaluateDirection(board, r, c, d[0], d[1], stone);
        }
        return score;
    }

    private static int evaluateDirection(int[][] board, int r, int c, int dr, int dc, int stone) {
        int count = 1;
        int blocked = 0;

        // 正向检查
        int i = r + dr;
        int j = c + dc;
        while (i >= 0 && i < GomokuBoard.SIZE && j >= 0 && j < GomokuBoard.SIZE && board[i][j] == stone) {
            count++;
            i += dr;
            j += dc;
        }
        if (i < 0 || i >= GomokuBoard.SIZE || j < 0 || j >= GomokuBoard.SIZE || board[i][j] != GomokuBoard.EMPTY) {
            blocked++;
        }

        // 反向检查
        i = r - dr;
        j = c - dc;
        while (i >= 0 && i < GomokuBoard.SIZE && j >= 0 && j < GomokuBoard.SIZE && board[i][j] == stone) {
            count++;
            i -= dr;
            j -= dc;
        }
        if (i < 0 || i >= GomokuBoard.SIZE || j < 0 || j >= GomokuBoard.SIZE || board[i][j] != GomokuBoard.EMPTY) {
            blocked++;
        }

        // 评分规则
        if (count >= 5) return 100000; // 连五
        if (count == 4) {
            if (blocked == 0) return 10000; // 活四
            if (blocked == 1) return 1000;  // 冲四
        }
        if (count == 3) {
            if (blocked == 0) return 1000;  // 活三
            if (blocked == 1) return 100;   // 眠三
        }
        if (count == 2) {
            if (blocked == 0) return 100;   // 活二
            if (blocked == 1) return 10;    // 眠二
        }
        return count;
    }
}
