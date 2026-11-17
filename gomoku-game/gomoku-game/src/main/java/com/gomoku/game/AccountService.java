package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * 账号体系：用户名 + 密码登录，把游客身份「原地升级」为持久账号。
 *
 * 核心取舍：
 * - 账号不新建身份，而是复用游客的 identityId。战绩、ELO、等级、成就、每日进度
 *   都以 identityId 为键，因此升级后数据一条不丢，下游协议完全不用改。
 * - 密码用 SHA-256 加盐多轮迭代哈希 + 每账号独立随机盐，磁盘只存盐与哈希，绝不存明文。
 * - 登录签发的是「指向同一 identityId 的新 token」，换设备登录即拿回同一份资产。
 * - 用户名大小写不敏感（归一化小写做键），但展示名保留注册时的原样。
 */
@Service
public class AccountService {
    /** 加盐哈希的迭代次数：迭代散列为弱口令提供暴力破解成本，1.2 万次登录延迟可忽略 */
    private static final int HASH_ITERATIONS = 12_000;
    private static final int SALT_BYTES = 16;

    private final Path path;
    private final GuestIdentityService guests;
    private final Gson gson = new Gson();
    /** 归一化用户名 -> 账号 */
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    /** identityId -> 归一化用户名，供「当前账号」反查 */
    private final Map<String, String> byIdentity = new HashMap<>();

    @Autowired
    public AccountService(GuestIdentityService guests) {
        this(Paths.get("data", "accounts.json"), guests);
    }

    /** 供测试注入临时路径 */
    public AccountService(Path path, GuestIdentityService guests) {
        this.path = path;
        this.guests = guests;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, Account> stored = gson.fromJson(
                    Files.readString(path), new TypeToken<Map<String, Account>>() {}.getType());
            if (stored == null) throw new IllegalStateException("账号文件为空");
            accounts.clear();
            byIdentity.clear();
            for (Map.Entry<String, Account> e : stored.entrySet()) {
                accounts.put(normalize(e.getKey()), e.getValue());
                byIdentity.put(e.getValue().identityId, normalize(e.getKey()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取账号文件", e);
        }
    }

    /** 注册并返回登录凭证；带有效游客 token 时把该身份无损升级为账号。 */
    public synchronized Map<String, String> register(String username, String password,
                                                      String nickname, String guestToken) {
        String key = requireUsername(username);
        requirePassword(password);
        if (accounts.containsKey(key)) throw new AccountException("CONFLICT", "用户名已被注册");
        if (nickname == null || nickname.trim().isEmpty()) nickname = username;

        // 优先复用游客身份（保留历史战绩）；若该身份已属于别的账号，则另起新身份避免资产混用。
        String identityId = guests.resolve(guestToken);
        if (identityId == null || byIdentity.containsKey(identityId)) {
            identityId = "acct-" + UUID.randomUUID();
        }

        byte[] salt = randomBytes(SALT_BYTES);
        Account account = new Account();
        account.username = key;
        account.nickname = nickname.trim();
        account.salt = Base64.getEncoder().encodeToString(salt);
        account.passwordHash = Base64.getEncoder().encodeToString(derive(password, salt));
        account.identityId = identityId;
        account.createdAt = System.currentTimeMillis();

        accounts.put(key, account);
        byIdentity.put(identityId, key);
        persistOrRollback(key, identityId);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("token", guests.issueForId(identityId));
        result.put("id", identityId);
        result.put("username", account.username);
        result.put("nickname", account.nickname);
        return result;
    }

    /** 登录：校验密码后签发指向同一身份的新 token。 */
    public synchronized Map<String, String> login(String username, String password) {
        String key = username == null ? "" : normalize(username);
        Account account = accounts.get(key);
        if (account == null || password == null || !matches(password, account)) {
            throw new AccountException("UNAUTHORIZED", "用户名或密码错误");
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("token", guests.issueForId(account.identityId));
        result.put("id", account.identityId);
        result.put("username", account.username);
        result.put("nickname", account.nickname);
        return result;
    }

    /** 由凭证反查当前账号；凭证无效或不属于任何账号时返回 null（仍可按游客游玩）。 */
    public synchronized Map<String, String> current(String token) {
        String identityId = guests.resolve(token);
        if (identityId == null) return null;
        String key = byIdentity.get(identityId);
        if (key == null) return null;
        Account account = accounts.get(key);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", identityId);
        result.put("username", account.username);
        result.put("nickname", account.nickname);
        return result;
    }

    /** 修改密码：需提供当前凭证与旧密码；成功后旧凭证仍有效（同一身份），只是密码变了。 */
    public synchronized void changePassword(String token, String oldPassword, String newPassword) {
        String identityId = guests.resolve(token);
        String key = identityId == null ? null : byIdentity.get(identityId);
        Account account = key == null ? null : accounts.get(key);
        if (account == null) throw new AccountException("UNAUTHORIZED", "请先登录");
        if (oldPassword == null || !matches(oldPassword, account)) {
            throw new AccountException("UNAUTHORIZED", "旧密码错误");
        }
        requirePassword(newPassword);
        byte[] salt = randomBytes(SALT_BYTES);
        account.salt = Base64.getEncoder().encodeToString(salt);
        account.passwordHash = Base64.getEncoder().encodeToString(derive(newPassword, salt));
        persist();
    }

    /** 账号总数，供健康检查与运营观察 */
    public synchronized int size() { return accounts.size(); }

    private void persistOrRollback(String key, String identityId) {
        try {
            persist();
        } catch (RuntimeException e) {
            accounts.remove(key);
            byIdentity.remove(identityId);
            throw e;
        }
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(accounts), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存账号", e);
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            for (int i = 1; i < HASH_ITERATIONS; i++) hash = digest.digest(hash);
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("密码派生失败", e);
        }
    }

    private static boolean matches(String password, Account account) {
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(account.salt);
            expected = Base64.getDecoder().decode(account.passwordHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(derive(password, salt), expected);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireUsername(String username) {
        String key = normalize(username);
        // 3–20 位，字母/数字/下划线/中文，避免空格与符号带来的展示与协议问题。
        if (!key.matches("[\\p{L}\\p{N}_]{3,20}")) {
            throw new AccountException("INVALID", "用户名需 3–20 位字母、数字、下划线或中文");
        }
        return key;
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 128) {
            throw new AccountException("INVALID", "密码需 6–128 位");
        }
    }

    /** 账号持久化模型，字段名保持稳定以便后续平滑演进 */
    public static class Account {
        public String username;
        public String nickname;
        public String salt;
        @SerializedName("passwordHash")
        public String passwordHash;
        public String identityId;
        public long createdAt;
    }

    /** 账号业务错误，携带给前端的机器可读 code */
    public static class AccountException extends RuntimeException {
        private final String code;
        public AccountException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }
}
