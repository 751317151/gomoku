package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 对局记录 — 一局完整对局的可持久化快照
 *
 * 用途：
 * - 战绩页查询（谁跟谁、什么时候、谁赢了、ELO 变化多少）
 * - 事后复盘（moveHistory + moveEffects 足以在任意端重建终局盘面）
 *
 * 约定：
 * - {@link #ranked} 为 true 表示 PvP 计分局（双方都是真人），战绩统计只认这个
 * - AI 局也会存档（便于复盘），但 ranked=false、ELO 变化恒为 0
 * - 手数过多时 {@link #moveHistory} 可能为 null（只留元信息），避免单文件过大
 */
public class GameRecord {

    /** 记录 ID（时间戳 + 短随机串，同时作为文件名） */
    private String id;
    /** 游戏类型名（GOMOKU / REVERSI / GO9 / XIANGQI / CONNECT4） */
    private String gameType;
    /** 房间 ID */
    private String roomId;
    /** 开局时间戳（毫秒） */
    private long startedAt;
    /** 结束时间戳（毫秒） */
    private long endedAt;
    /** 胜者昵称；平局为 null */
    private String winnerName;
    /** 结束原因（五子连珠 / 认输 / 断线超时 …） */
    private String reason;
    /** 总手数 */
    private int moveCount;
    /** 是否计分局（双方真人） */
    private boolean ranked;

    /** 参战方（真人 + AI），每方一条 */
    private List<Participant> participants = new ArrayList<>();
    /** 棋谱：每手 {row, col, side}，象棋为五元素；停一手为负数行 */
    private List<int[]> moveHistory;
    /** 与 moveHistory 对齐的每手盘面变化（提子/翻转/移动） */
    private List<List<int[]>> moveEffects;

    /**
     * 一方的对局结果与 ELO 变化
     */
    public static class Participant {
        private String playerId;
        private String identityId;
        public String getIdentityId() { return identityId; }
        public void setIdentityId(String identityId) { this.identityId = identityId; }
        private String name;
        private int side;
        /** WIN / LOSE / DRAW */
        private String result;
        /** AI 玩家为 true（不计 ELO、不进真人战绩） */
        private boolean ai;
        private int eloBefore;
        private int eloAfter;
        private int eloChange;
        /**
         * 本局实际使用的套牌名（仅卡牌对战；其他玩法为 null）。
         * 存的是引擎实际生效的套牌，而不是玩家提交值 —— 自定义配比非法时
         * 服务端会回落均衡，若照抄提交值，记录会撒谎。
         */
        private String deck;
        /** 套牌中文标签（均衡/快攻/控制/自定义），便于前端直接展示 */
        private String deckLabel;
        /** 自定义套牌配比摘要，如「步兵×6 · 弓箭手×6…」；预组为 null */
        private String deckSummary;

        public Participant() {}

        public Participant(String playerId, String name, int side, String result, boolean ai,
                           int eloBefore, int eloAfter, int eloChange) {
            this.playerId = playerId;
            this.name = name;
            this.side = side;
            this.result = result;
            this.ai = ai;
            this.eloBefore = eloBefore;
            this.eloAfter = eloAfter;
            this.eloChange = eloChange;
        }

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getSide() { return side; }
        public void setSide(int side) { this.side = side; }

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        public boolean isAi() { return ai; }
        public void setAi(boolean ai) { this.ai = ai; }

        public int getEloBefore() { return eloBefore; }
        public void setEloBefore(int eloBefore) { this.eloBefore = eloBefore; }

        public int getEloAfter() { return eloAfter; }
        public void setEloAfter(int eloAfter) { this.eloAfter = eloAfter; }

        public int getEloChange() { return eloChange; }
        public void setEloChange(int eloChange) { this.eloChange = eloChange; }

        public String getDeck() { return deck; }
        public void setDeck(String deck) { this.deck = deck; }

        public String getDeckLabel() { return deckLabel; }
        public void setDeckLabel(String deckLabel) { this.deckLabel = deckLabel; }

        public String getDeckSummary() { return deckSummary; }
        public void setDeckSummary(String deckSummary) { this.deckSummary = deckSummary; }
    }

    // Getters / Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGameType() { return gameType; }
    public void setGameType(String gameType) { this.gameType = gameType; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public long getEndedAt() { return endedAt; }
    public void setEndedAt(long endedAt) { this.endedAt = endedAt; }

    public String getWinnerName() { return winnerName; }
    public void setWinnerName(String winnerName) { this.winnerName = winnerName; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public int getMoveCount() { return moveCount; }
    public void setMoveCount(int moveCount) { this.moveCount = moveCount; }

    public boolean isRanked() { return ranked; }
    public void setRanked(boolean ranked) { this.ranked = ranked; }

    public List<Participant> getParticipants() { return participants; }
    public void setParticipants(List<Participant> participants) { this.participants = participants; }

    public List<int[]> getMoveHistory() { return moveHistory; }
    public void setMoveHistory(List<int[]> moveHistory) { this.moveHistory = moveHistory; }

    public List<List<int[]>> getMoveEffects() { return moveEffects; }
    public void setMoveEffects(List<List<int[]>> moveEffects) { this.moveEffects = moveEffects; }
}
