package com.gomoku.dto;

import java.util.List;

/**
 * 游戏结束分数 DTO
 */
public class ScoreDataDto {
    private List<ScoreItemDto> scores;

    public ScoreDataDto() {}

    public List<ScoreItemDto> getScores() { return scores; }
    public void setScores(List<ScoreItemDto> scores) { this.scores = scores; }
}
