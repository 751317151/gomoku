package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 服务端签发游客凭证；磁盘仅保存哈希，凭证丢失不会凭昵称找回。 */
@Service
public class GuestIdentityService {
    private final Path path;
    private final Map<String, String> identities = new HashMap<>();
    public GuestIdentityService() { this(Paths.get("data", "guests.json")); }
    public GuestIdentityService(Path path) { this.path = path; }
    @PostConstruct public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, String> stored = new Gson().fromJson(Files.readString(path), new TypeToken<Map<String, String>>() {}.getType());
            if (stored == null) throw new IllegalStateException("游客身份文件为空");
            identities.putAll(stored);
        } catch (Exception e) { throw new IllegalStateException("无法读取游客身份文件", e); }
    }
    public synchronized Map<String, String> issue() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = "guest-" + UUID.randomUUID();
        String hash = hash(token); identities.put(hash, id);
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, new Gson().toJson(identities), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { identities.remove(hash); throw new IllegalStateException("无法保存游客身份", e); }
        Map<String, String> result = new LinkedHashMap<>(); result.put("token", token); result.put("id", id); return result;
    }
    /**
     * 为一个已存在的身份签发新 token。账号登录时用它把登录态转成现有身份凭证，
     * 这样战绩/ELO/成长全部延续，下游（GameServerHandler、/api/me/*）无需改动。
     * 与 issue() 的区别：不生成新 identityId，而是绑定到传入的 id。
     */
    public synchronized String issueForId(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("identityId 不能为空");
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token); identities.put(hash, id);
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, new Gson().toJson(identities), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { identities.remove(hash); throw new IllegalStateException("无法保存身份凭证", e); }
        return token;
    }

    public synchronized String resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return identities.get(hash(token));
    }
    private static String hash(String token) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
