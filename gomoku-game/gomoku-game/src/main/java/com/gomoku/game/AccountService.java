package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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
 * - 新密码使用 PBKDF2-HMAC-SHA256 + 每账号随机盐；旧 SHA-256 迭代记录在用户
 *   首次成功登录后透明升级，不需要清空账号或让用户手工迁移。
 * - 会话由 GuestIdentityService 持久化。改密会一次撤销该身份的旧会话并签发
 *   当前设备的新会话；「退出其他设备」只保留当前凭证。
 */
@Service
public class AccountService {
    static final String PASSWORD_SCHEME = "pbkdf2-sha256";
    static final int HASH_ITERATIONS = 310_000;
    static final int LEGACY_HASH_ITERATIONS = 12_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    static final int MAX_LOGIN_FAILURES = 5;
    static final long LOGIN_FAILURE_WINDOW_MS = 10 * 60 * 1000L;
    /** 恢复码长度：去混淆字符集，避免用户抄错 0/O、1/I。 */
    private static final int RECOVERY_CODE_LENGTH = 20;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_RECOVERY_FAILURES = 5;
    static final long RECOVERY_FAILURE_WINDOW_MS = 10 * 60 * 1000L;

    private final Path path;
    private final GuestIdentityService guests;
    private final Gson gson = new Gson();
    /** 归一化用户名 -> 账号 */
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    /** identityId -> 归一化用户名，供「当前账号」反查 */
    private final Map<String, String> byIdentity = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> loginFailures = new HashMap<>();
    /** 规范化用户名 -> 恢复码失败时间，防止恢复码被在线暴力枚举。 */
    private final Map<String, ArrayDeque<Long>> recoveryFailures = new HashMap<>();

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
                Account account = e.getValue();
                if (account == null || account.identityId == null || account.identityId.isEmpty()) {
                    throw new IllegalStateException("账号记录缺少身份");
                }
                accounts.put(normalize(e.getKey()), account);
                byIdentity.put(account.identityId, normalize(e.getKey()));
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

        // 优先复用游客身份；若该身份已属于别的账号，则另起新身份避免资产混用。
        String identityId = guests.resolve(guestToken);
        if (identityId == null || byIdentity.containsKey(identityId)) {
            identityId = "acct-" + UUID.randomUUID();
        }

        Account account = new Account();
        account.username = key;
        account.nickname = nickname.trim();
        account.identityId = identityId;
        account.createdAt = System.currentTimeMillis();
        account.passwordScheme = PASSWORD_SCHEME;
        account.passwordIterations = HASH_ITERATIONS;
        resetCredential(account, password);
        String recoveryCode = issueRecoveryCode(account);

        accounts.put(key, account);
        byIdentity.put(identityId, key);
        try {
            persist();
        } catch (RuntimeException e) {
            accounts.remove(key);
            byIdentity.remove(identityId);
            throw e;
        }

        String token = guests.issueForId(identityId);
        Map<String, String> result = session(account, token, "createdAt");
        result.put("recoveryCode", recoveryCode);
        return result;
    }

    /** 登录：校验密码后签发指向同一身份的新 token；旧散列成功验证后自动升级。 */
    public synchronized Map<String, String> login(String username, String password) {
        String key = username == null ? "" : normalize(username);
        long now = clock();
        pruneLoginFailures(key, now);
        if (failureCount(key, now) >= MAX_LOGIN_FAILURES) {
            throw new AccountException("TOO_MANY_ATTEMPTS", "登录尝试过于频繁，请 10 分钟后再试");
        }
        Account account = accounts.get(key);
        if (account == null || password == null || !matches(password, account)) {
            recordLoginFailure(key, now);
            throw new AccountException("UNAUTHORIZED", "用户名或密码错误");
        }
        loginFailures.remove(key);
        if (!isCurrentScheme(account)) {
            resetCredential(account, password);
            persist();
        }
        String token = guests.issueForId(account.identityId);
        return session(account, token, "createdAt");
    }

    /** 由凭证反查当前账号；凭证无效或不属于任何账号时返回 null（仍可按游客游玩）。 */
    public synchronized Map<String, String> current(String token) {
        String identityId = guests.resolve(token);
        if (identityId == null) return null;
        String key = byIdentity.get(identityId);
        if (key == null) return null;
        Account account = accounts.get(key);
        return session(account, token, "createdAt");
    }

    /**
     * 由持久身份反查账号信息（好友系统展示昵称、按用户名加好友用）。
     * 该身份不属于任何账号（纯游客）时返回 null。
     */
    public synchronized Map<String, String> findByIdentity(String identityId) {
        String key = identityId == null ? null : byIdentity.get(identityId);
        Account account = key == null ? null : accounts.get(key);
        if (account == null) return null;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", identityId);
        result.put("username", account.username);
        result.put("nickname", account.nickname);
        result.put("createdAt", String.valueOf(account.createdAt));
        return result;
    }

    /** 用户名 → 持久身份；账号不存在返回 null（好友系统按用户名申请时解析目标）。 */
    public synchronized String identityOfUsername(String username) {
        String key = normalize(username);
        if (key.isEmpty()) return null;
        Account account = accounts.get(key);
        return account == null ? null : account.identityId;
    }

    /**
     * 修改密码：撤销该身份下除当前请求外的所有旧凭证，并给当前设备换发新 token。
     * 返回值中的 token 必须由客户端保存；其余设备上的旧 token 已立即失效。
     */
    public synchronized Map<String, String> changePassword(String token, String oldPassword, String newPassword) {
        Account account = accountForToken(token);
        if (oldPassword == null || !matches(oldPassword, account)) {
            throw new AccountException("UNAUTHORIZED", "旧密码错误");
        }
        requirePassword(newPassword);
        resetCredential(account, newPassword);
        String recoveryCode = issueRecoveryCode(account);
        persist();
        String nextToken = guests.replaceAllForIdentity(account.identityId);
        Map<String, String> result = session(account, nextToken, "createdAt");
        result.put("recoveryCode", recoveryCode);
        return result;
    }

    /** 窗口内的恢复码失败次数（package-private，供测试断言） */
    int recoveryFailureCount(String username, long now) {
        String key = username == null ? "" : normalize(username);
        pruneRecoveryFailures(key, now);
        ArrayDeque<Long> queue = recoveryFailures.get(key);
        return queue == null ? 0 : queue.size();
    }

    /** 记录一次恢复码失败尝试（package-private，供测试注入时间） */
    void recordRecoveryFailure(String username, long now) {
        String key = username == null ? "" : normalize(username);
        ArrayDeque<Long> queue = recoveryFailures.computeIfAbsent(key, k -> new ArrayDeque<>());
        queue.addLast(now);
        pruneRecoveryFailures(key, now);
    }

    private void pruneRecoveryFailures(String key, long now) {
        ArrayDeque<Long> queue = recoveryFailures.get(key);
        if (queue == null) return;
        long cutoff = now - RECOVERY_FAILURE_WINDOW_MS;
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.removeFirst();
        if (queue.isEmpty()) recoveryFailures.remove(key);
    }

    private static boolean matchesRecoveryCode(String recoveryCode, Account account) {
        if (account.recoveryCodeHash == null || account.recoveryCodeHash.isEmpty()
                || account.recoveryCodeSalt == null || account.recoveryCodeSalt.isEmpty()) {
            return false;
        }
        if (account.recoveryCodeIterations <= 0) return false;
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(account.recoveryCodeSalt);
            expected = Base64.getDecoder().decode(account.recoveryCodeHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = derivePassword(recoveryCode.trim().toUpperCase(Locale.ROOT),
                salt, account.recoveryCodeIterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static String generateRecoveryCode() {
        StringBuilder sb = new StringBuilder(RECOVERY_CODE_LENGTH);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            sb.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String issueRecoveryCode(Account account) {
        String code = generateRecoveryCode();
        byte[] salt = randomBytes(SALT_BYTES);
        account.recoveryCodeHash = Base64.getEncoder().encodeToString(
                derivePassword(code, salt, HASH_ITERATIONS));
        account.recoveryCodeSalt = Base64.getEncoder().encodeToString(salt);
        account.recoveryCodeIterations = HASH_ITERATIONS;
        account.recoveryCodeUpdatedAt = System.currentTimeMillis();
        return code;
    }

    /**
     * 用一次性恢复码重置密码（忘记密码的唯一自救路径）。
     *
     * 恢复码在注册与每次改密时轮换，只以 PBKDF2 哈希落盘；使用成功后立即失效，
     * 同时撤销该身份全部旧会话并签发新码。这样即使恢复码曾被旁观，也无法二次使用。
     */
    public synchronized Map<String, String> recoverPassword(String username, String recoveryCode,
                                                             String newPassword) {
        String key = normalize(username);
        long now = clock();
        pruneRecoveryFailures(key, now);
        if (recoveryFailureCount(key, now) >= MAX_RECOVERY_FAILURES) {
            throw new AccountException("TOO_MANY_ATTEMPTS", "恢复码尝试过于频繁，请 10 分钟后再试");
        }
        Account account = key.isEmpty() ? null : accounts.get(key);
        if (account == null || recoveryCode == null
                || !matchesRecoveryCode(recoveryCode, account)) {
            recordRecoveryFailure(key, now);
            // 不区分「账号不存在」与「恢复码错误」，避免枚举用户名。
            throw new AccountException("UNAUTHORIZED", "用户名或恢复码错误");
        }
        requirePassword(newPassword);
        recoveryFailures.remove(key);
        resetCredential(account, newPassword);
        String nextRecoveryCode = issueRecoveryCode(account);
        persist();
        // 忘记密码时无法确认哪台设备可信，因此全部旧会话一并撤销。
        String token = guests.replaceAllForIdentity(account.identityId);
        Map<String, String> result = session(account, token, "createdAt");
        result.put("recoveryCode", nextRecoveryCode);
        return result;
    }

    /** 为已登录账号重新生成恢复码（旧码立即失效）。 */
    public synchronized Map<String, String> rotateRecoveryCode(String token, String password) {
        Account account = accountForToken(token);
        if (password == null || !matches(password, account)) {
            throw new AccountException("UNAUTHORIZED", "密码错误");
        }
        String recoveryCode = issueRecoveryCode(account);
        persist();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("recoveryCode", recoveryCode);
        return result;
    }

    /** 退出其他设备：保留当前 token，撤销同身份的其他所有会话。 */
    public synchronized int logoutOtherSessions(String token) {
        accountForToken(token);
        return guests.revokeOtherSessions(token);
    }

    /**
     * 显式撤销当前账号凭证。该操作只对账号生效；纯游客调用不会误删游客会话。
     */
    public synchronized boolean logout(String token) {
        String identityId = guests.resolve(token);
        if (identityId == null || !byIdentity.containsKey(identityId)) return false;
        return guests.revoke(token);
    }

    /** 账号总数，供健康检查与运营观察 */
    public synchronized int size() { return accounts.size(); }

    /** 当前时间（package-private，测试可覆盖以模拟时间流逝） */
    long clock() { return System.currentTimeMillis(); }

    /** 记录一次失败尝试（package-private，供测试注入时间） */
    void recordLoginFailure(String username, long now) {
        String key = username == null ? "" : normalize(username);
        ArrayDeque<Long> queue = loginFailures.computeIfAbsent(key, k -> new ArrayDeque<>());
        queue.addLast(now);
        pruneLoginFailures(key, now);
    }

    /** 窗口内的失败次数（package-private，供测试断言） */
    int failureCount(String username, long now) {
        String key = username == null ? "" : normalize(username);
        pruneLoginFailures(key, now);
        ArrayDeque<Long> queue = loginFailures.get(key);
        return queue == null ? 0 : queue.size();
    }

    private void pruneLoginFailures(String key, long now) {
        ArrayDeque<Long> queue = loginFailures.get(key);
        if (queue == null) return;
        long cutoff = now - LOGIN_FAILURE_WINDOW_MS;
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.removeFirst();
        if (queue.isEmpty()) loginFailures.remove(key);
    }

    private Account accountForToken(String token) {
        String identityId = guests.resolve(token);
        String key = identityId == null ? null : byIdentity.get(identityId);
        Account account = key == null ? null : accounts.get(key);
        if (account == null) throw new AccountException("UNAUTHORIZED", "请先登录");
        return account;
    }

    private Map<String, String> session(Account account, String token, String extraKey) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("id", account.identityId);
        result.put("username", account.username);
        result.put("nickname", account.nickname);
        result.put(extraKey, String.valueOf(account.createdAt));
        result.put("passwordScheme", account.passwordScheme == null ? "legacy-sha256" : account.passwordScheme);
        return result;
    }

    private static boolean isCurrentScheme(Account account) {
        return PASSWORD_SCHEME.equals(account.passwordScheme)
                && account.passwordIterations == HASH_ITERATIONS;
    }

    private static void resetCredential(Account account, String password) {
        byte[] salt = randomBytes(SALT_BYTES);
        account.salt = Base64.getEncoder().encodeToString(salt);
        account.passwordHash = Base64.getEncoder().encodeToString(
                derivePassword(password, salt, HASH_ITERATIONS));
        account.passwordScheme = PASSWORD_SCHEME;
        account.passwordIterations = HASH_ITERATIONS;
        account.passwordUpdatedAt = System.currentTimeMillis();
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(accounts), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存账号", e);
        }
    }

    private static byte[] derivePassword(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码派生失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] deriveLegacy(String password, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            for (int i = 1; i < LEGACY_HASH_ITERATIONS; i++) hash = digest.digest(hash);
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("旧密码派生失败", e);
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
        byte[] actual = isCurrentScheme(account)
                ? derivePassword(password, salt, account.passwordIterations)
                : deriveLegacy(password, salt);
        return MessageDigest.isEqual(actual, expected);
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

    /** 账号持久化模型；新字段为空时仍兼容旧 accounts.json。 */
    public static class Account {
        public String username;
        public String nickname;
        public String salt;
        @SerializedName("passwordHash")
        public String passwordHash;
        public String identityId;
        public long createdAt;
        public String passwordScheme;
        public int passwordIterations;
        public long passwordUpdatedAt;
        /** 恢复码 PBKDF2 哈希与盐；恢复码本身只在生成/轮换时明文返回一次。 */
        public String recoveryCodeHash;
        public String recoveryCodeSalt;
        public int recoveryCodeIterations;
        public long recoveryCodeUpdatedAt;

        /** 测试和审计用：旧记录没有该字段。 */
        public boolean usesLegacyPasswordHash() {
            return passwordScheme == null || passwordScheme.isEmpty();
        }
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
