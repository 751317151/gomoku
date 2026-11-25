package com.gomoku.game;

/**
 * 游戏类型 — 大厅中的可玩游戏
 *
 * rows/cols 只对棋盘玩法有意义；非棋盘玩法（RPG）填 0，用 {@link #isBoard()} 判断，
 * 不要在调用方拿 rows > 0 去猜玩法类型。
 */
public enum GameType {

    GOMOKU("五子棋", 15, 15, true),
    REVERSI("黑白棋", 8, 8, true),
    GO9("围棋 9路", 9, 9, true),
    XIANGQI("中国象棋", 10, 9, true),
    /** 西洋跳棋：8×8，强制吃子、连吃与升王，双人回合制 */
    CHECKERS("西洋跳棋", 8, 8, true),
    /** 飞行棋：2-4人经典玩法，投骰子走棋，碰撞打回起点 */
    FLIGHT_CHESS("飞行棋", 15, 15, true, 2, 4, 2),
    /** 斗兽棋：9×7 双人棋子移动，八兽相克、河流与陷阱 */
    JUNGLE("斗兽棋", 9, 7, true),
    CONNECT4("四子棋", 6, 7, true),
    /** 回合制 RPG 对战：HP/MP + 技能，第一个非棋牌玩法 */
    RPG("回合制对战", 0, 0, false),
    /** 卡牌对战：手牌/法力/随从，第一个带隐藏信息的玩法 */
    CARD("卡牌对战", 0, 0, false),
    /** 取石子博弈：组合博弈，AI 有基于异或和的完美解 */
    NIM("取石子", 0, 0, false),
    /** 答题对战：同一题双方各自作答，提交前互相看不到选择 */
    QUIZ("答题对战", 0, 0, false),
    /** 骰子竞速：2–8 人同时选择策略并掷骰，六轮累计得分 */
    DICE_RACE("骰子竞速", 0, 0, false, 2, 8, 4),
    /** 宝石拍卖：2–8 人同时暗投金币，价高者获得宝石分数 */
    GEM_AUCTION("宝石拍卖", 0, 0, false, 2, 8, 4),
    /** 21 点：2–8 人对庄家，依次要牌/停牌 */
    BLACKJACK("21 点", 0, 0, false, 2, 8, 4),
    HIGH_CARD("比大小", 0, 0, false, 2, 8, 4),
    UNO("UNO 对战", 0, 0, false, 2, 8, 4),
    /** 暗棋：4×8 全暗随机开局，翻子与走子二选一，背面棋子对双方隐藏 */
    DARK_CHESS("暗棋", 4, 8, true),
    /** 炸弹猫：2–8 人轮流出牌/抽牌，抽到炸弹且无拆弹器即出局 */
    BOMB_CAT("炸弹猫", 0, 0, false, 2, 8, 4),
    /** 斗地主：3 人经典牌局，地主获得底牌，农民协作对抗地主 */
    DOUDIZHU("斗地主", 0, 0, false, 3, 3, 3),
    /** 终极井字棋：局部落点指定对手小盘，三个小盘连线获胜 */
    ULTIMATE_TTT("终极井字棋", 9, 9, true),
    /** 点格棋：2–4 人画边围格，完成格子得分并继续行动 */
    DOTS_BOXES("点格棋", 9, 9, true, 2, 4, 2);

    private final String displayName;
    private final int rows;
    private final int cols;
    private final boolean board;
    private final int minPlayers;
    private final int maxPlayers;
    private final int defaultPlayers;

    GameType(String displayName, int rows, int cols, boolean board) {
        this(displayName, rows, cols, board, 2, 2, 2);
    }

    GameType(String displayName, int rows, int cols, boolean board,
             int minPlayers, int maxPlayers, int defaultPlayers) {
        if (minPlayers < 2 || maxPlayers > 8 || minPlayers > defaultPlayers || defaultPlayers > maxPlayers) {
            throw new IllegalArgumentException("游戏人数必须在 2–8 人内");
        }
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.defaultPlayers = defaultPlayers;
        this.displayName = displayName;
        this.rows = rows;
        this.cols = cols;
        this.board = board;
    }

    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getDefaultPlayers() { return defaultPlayers; }
    public int resolvePlayerCount(int requested) {
        int count = requested == 0 ? defaultPlayers : requested;
        if (count < minPlayers || count > maxPlayers) throw new IllegalArgumentException("该玩法不支持此人数");
        return count;
    }

    public String getDisplayName() { return displayName; }
    public int getRows() { return rows; }
    public int getCols() { return cols; }

    /** 是否为棋盘玩法（决定前端渲染模块与是否需要盘面数据） */
    public boolean isBoard() { return board; }

    /**
     * 从字符串解析游戏类型，空或未知时默认五子棋（兼容旧客户端）
     */
    public static GameType fromString(String s) {
        if (s == null || s.isEmpty()) return GOMOKU;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return GOMOKU;
        }
    }
}

