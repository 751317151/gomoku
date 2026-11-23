package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * 服务端签发身份凭证；磁盘仅保存凭证哈希，凭证丢失不会凭昵称找回。
 *
 * 账号登录、游客试玩和换设备登录共用同一套身份解析链路。账号会话管理
 * 直接作用在这张哈希表上，因此撤销后所有 REST 与 WebSocket 请求都会
 * 在下一次解析时失效，不依赖某个控制器单独记黑名单。
 */
@Service
public class GuestIdentityService {
    private final Path path;
    private final Map<String, String> identities = new HashMap<>();

    public GuestIdentityService() { this(Paths.get("data", "guests.json")); }
    public GuestIdentityService(Path path) { this.path = path; }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, String> stored = new Gson().fromJson(
                    Files.readString(path), new TypeToken<Map<String, String>>() {}.getType());
            if (stored == null) throw new IllegalStateException("游客身份文件为空");
            identities.putAll(stored);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取游客身份文件", e);
        }
    }

    public synchronized Map<String, String> issue() {
        String id = "guest-" + UUID.randomUUID();
        String token = issueForId(id);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("id", id);
        return result;
    }

    /**
     * 为一个已存在的身份签发新 token。账号登录时用它把登录态转成现有身份凭证，
     * 这样战绩/ELO/成长全部延续，下游（GameServerHandler、/api/me/*）无需改动。
     */
    public synchronized String issueForId(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("identityId 不能为空");
        Map<String, String> before = new HashMap<>(identities);
        String token = newToken();
        identities.put(hash(token), id);
        persistOrRestore(before, "无法保存身份凭证");
        return token;
    }

    /** 撤销当前凭证。撤销后的 token 对所有 HTTP/WS 入口同时失效。 */
    public synchronized boolean revoke(String token) {
        if (token == null) return false;
        Map<String, String> before = new HashMap<>(identities);
        boolean removed = identities.remove(hash(token)) != null;
        if (removed) persistOrRestore(before, "无法撤销身份凭证");
        return removed;
    }

    /**
     * 撤销同一账号身份下除当前凭证外的全部会话。
     * 返回实际撤销数量；当前凭证保持有效，便于用户留在本设备。
     */
    public synchronized int revokeOtherSessions(String currentToken) {
        String identityId = resolve(currentToken);
        if (identityId == null) return 0;
        String currentHash = hash(currentToken);
        Map<String, String> before = new HashMap<>(identities);
        int removed = 0;
        Iterator<Map.Entry<String, String>> iterator = identities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            if (identityId.equals(entry.getValue()) && !currentHash.equals(entry.getKey())) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) persistOrRestore(before, "无法撤销其他会话");
        return removed;
    }

    /**
     * 账号改密时一次性撤销该身份的全部旧凭证并签发一张新凭证。
     * 删除与新增只在一次磁盘替换中完成，避免中途失败留下半套会话。
     */
    public synchronized String replaceAllForIdentity(String identityId) {
        if (identityId == null || identityId.isEmpty()) {
            throw new IllegalArgumentException("identityId 不能为空");
        }
        Map<String, String> before = new HashMap<>(identities);
        identities.entrySet().removeIf(entry -> identityId.equals(entry.getValue()));
        String token = newToken();
        identities.put(hash(token), identityId);
        persistOrRestore(before, "无法轮换身份凭证");
        return token;
    }

    public synchronized String resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return identities.get(hash(token));
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void persistOrRestore(Map<String, String> before, String message) {
        try {
            persist();
        } catch (RuntimeException e) {
            identities.clear();
            identities.putAll(before);
            throw new IllegalStateException(message, e);
        }
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, new Gson().toJson(identities), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存游客身份", e);
        }
    }

    private static String hash(String token) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
