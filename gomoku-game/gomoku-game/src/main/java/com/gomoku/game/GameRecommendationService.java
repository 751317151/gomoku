package com.gomoku.game;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大厅「下一局」推荐。
 *
 * 这不是排位承诺，也不是营销随机弹窗；排序必须能被解释：
 * 1. 当前有真人等待的玩法优先，玩家点了就能和别人玩；
 * 2. 本人没玩过或只玩过很少的玩法优先，帮助探索全部内容；
 * 3. 最近全站热门玩法作为空场兜底。
 *
 * 全部只读内存/REST 已有数据，不另存推荐状态；匿名与未登录也能得到推荐。
 */
@Service
public class GameRecommendationService {

    private static final long POPULARITY_CACHE_MS = 10_000L;
    private final GameRecordService records;
    private volatile Map<String, Long> cachedPopularity = Map.of();
    private volatile long popularityExpiresAt;

    public GameRecommendationService(GameRecordService records) {
        this.records = records;
    }

    /**
     * @param identityId 当前持久身份，可为 null（游客/匿名）
     * @param overview   RoomManager.getMatchOverview() 的结果
     * @param limit      最多返回几个，夹在 1..6
     */
    public Map<String, Object> nextGames(String identityId, Map<String, Object> overview, int limit) {
        int capped = Math.max(1, Math.min(6, limit));
        Map<String, Integer> personalGames = personalGameCounts(identityId);
        Map<String, Long> popularGames = popularGameCounts();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (GameType type : GameType.values()) {
            int waitingPlayers = waitingPlayers(type, overview);
            int personal = personalGames.getOrDefault(type.name(), 0);
            long popular = popularGames.getOrDefault(type.name(), 0L);
            int score = waitingPlayers * 100;
            String reason;
            if (waitingPlayers > 0) {
                reason = "现在有 " + waitingPlayers + " 名真人在等待";
            } else if (personal == 0) {
                score += 35;
                reason = "还没玩过，适合探索新玩法";
            } else if (personal == 1) {
                score += 18;
                reason = "只玩过 1 局，再来一局熟悉规则";
            } else {
                score += Math.min(15, personal / 2);
                reason = popular > 0 ? "你常玩的玩法，最近也很热门" : "你熟悉的玩法";
            }
            score += (int) Math.min(20L, popular);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gameType", type.name());
            row.put("name", type.getDisplayName());
            row.put("reason", reason);
            row.put("waitingPlayers", waitingPlayers);
            row.put("personalGames", personal);
            row.put("recentGlobalGames", popular);
            row.put("minPlayers", type.getMinPlayers());
            row.put("maxPlayers", type.getMaxPlayers());
            row.put("defaultPlayers", type.getDefaultPlayers());
            row.put("score", score);
            candidates.add(row);
        }
        candidates.sort(Comparator
                .comparingInt((Map<String, Object> r) -> (Integer) r.get("score")).reversed()
                .thenComparing(r -> String.valueOf(r.get("gameType"))));
        List<Map<String, Object>> result = new ArrayList<>(candidates.subList(0, Math.min(capped, candidates.size())));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recommendations", result);
        response.put("identityBound", identityId != null);
        response.put("basis", "等待人数 → 新玩法/少玩 → 最近热门");
        response.put("generatedAt", System.currentTimeMillis());
        return response;
    }

    private Map<String, Integer> personalGameCounts(String identityId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (identityId == null) return counts;
        Set<String> seen = new HashSet<>();
        records.visitIdentityRecords(identityId, record -> {
            if (!seen.add(record.getId())) return;
            GameRecord.Participant me = participantOf(record, identityId);
            if (me != null && !me.isAi() && record.getGameType() != null) {
                counts.merge(record.getGameType().toUpperCase(java.util.Locale.ROOT), 1, Integer::sum);
            }
        });
        return counts;
    }

    /** 最近 7 天真人局计数；人机局不计，避免单人练习把推荐榜单刷成某一玩法。 */
    private Map<String, Long> popularGameCounts() {
        long now = System.currentTimeMillis();
        if (now < popularityExpiresAt) return cachedPopularity;
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now < popularityExpiresAt) return cachedPopularity;
            long cutoff = now - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
            cachedPopularity = Map.copyOf(records.getSocialGameCounts(cutoff, Long.MAX_VALUE));
            popularityExpiresAt = now + POPULARITY_CACHE_MS;
            return cachedPopularity;
        }
    }

    private static GameRecord.Participant participantOf(GameRecord record, String identityId) {
        for (GameRecord.Participant p : record.getParticipants()) {
            if (identityId.equals(p.getIdentityId()) && !p.isAi()) return p;
        }
        return null;
    }

    private static int waitingPlayers(GameType type, Map<String, Object> overview) {
        if (overview == null) return 0;
        Object direct = overview.get(type.name());
        if (direct instanceof Number) return Math.max(0, ((Number) direct).intValue());
        Object games = overview.get("games");
        if (!(games instanceof List)) return 0;
        for (Object item : (List<?>) games) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) item;
            if (!type.name().equals(String.valueOf(row.get("gameType")))) continue;
            Object waiting = row.get("waitingPlayers");
            if (waiting instanceof Number) return Math.max(0, ((Number) waiting).intValue());
        }
        return 0;
    }
}
