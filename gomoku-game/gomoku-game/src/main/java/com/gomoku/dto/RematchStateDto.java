package com.gomoku.dto;

import java.util.List;

/**
 * 一局结束后的「再来一局」协作状态。
 *
 * 结算页只展示结果还不够：多人局里先点重开的人看不到谁还没准备，
 * 也不知道请求是否已经过期。把准备名单与截止时间作为房间状态下发，
 * 客户端就能画出真实进度，而不是各弹各的确认框。
 */
public class RematchStateDto {
    /** 本轮重开是否仍在等待确认 */
    private boolean pending;
    /** 截止时间（epoch millis）；未在等待时为 null */
    private Long expiresAt;
    /** 已准备的玩家 ID */
    private List<String> readyPlayerIds;
    /** 尚未准备、且仍需等待的真人玩家 ID */
    private List<String> waitingPlayerIds;
    /** 当前房间内的真人数量（电脑不参与确认） */
    private int humanPlayers;

    public RematchStateDto() {}

    public boolean isPending() { return pending; }
    public void setPending(boolean value) { pending = value; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long value) { expiresAt = value; }

    public List<String> getReadyPlayerIds() { return readyPlayerIds; }
    public void setReadyPlayerIds(List<String> value) { readyPlayerIds = value; }

    public List<String> getWaitingPlayerIds() { return waitingPlayerIds; }
    public void setWaitingPlayerIds(List<String> value) { waitingPlayerIds = value; }

    public int getHumanPlayers() { return humanPlayers; }
    public void setHumanPlayers(int value) { humanPlayers = value; }
}
