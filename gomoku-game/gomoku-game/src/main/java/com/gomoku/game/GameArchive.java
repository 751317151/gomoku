package com.gomoku.game;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可重建的全量历史索引。index.jsonl 仍是摘要日志，详情文件仍用于复盘。
 * SQLite 只提供唯一 ID 与身份/昵称/时间索引，不再把玩家资产限制在大厅的最近对局窗口。
 * 每次操作独立关闭连接，避免测试目录/备份被长期持有的文件句柄锁住。
 */
final class GameArchive {
    private static final Logger logger = LoggerFactory.getLogger(GameArchive.class);
    private static final Gson GSON = new Gson();
    /** 用于识别 index.jsonl 是否被替换/轮转的前缀长度；只影响增量重放的起点。 */
    private static final int PREFIX_BYTES = 4096;
    private final String url;
    private final Path indexPath;

    GameArchive(Path baseDir) {
        try {
            Files.createDirectories(baseDir);
            indexPath = baseDir.resolve("index.jsonl");
            url = "jdbc:sqlite:" + baseDir.resolve("archive.sqlite").toAbsolutePath();
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                    if (version.next() && version.getInt(1) > 1) {
                        throw new IllegalStateException("对局历史索引版本高于当前程序，请使用匹配版本启动");
                    }
                }
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("CREATE TABLE IF NOT EXISTS games ("
                        + "seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, "
                        + "game_type TEXT, ended_at INTEGER NOT NULL, summary TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS participants ("
                        + "game_id TEXT NOT NULL REFERENCES games(id), position INTEGER NOT NULL, "
                        + "identity_id TEXT, name TEXT, ai INTEGER NOT NULL, "
                        + "PRIMARY KEY(game_id, position))");
                statement.execute("CREATE INDEX IF NOT EXISTS participants_identity ON participants(identity_id, ai, game_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS participants_name ON participants(name, ai, game_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS games_ended ON games(ended_at)");
                statement.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("PRAGMA user_version=1");
            }
            importLegacy(indexPath);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法初始化对局历史索引", e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=FULL");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** 启动时流式重放旧摘要；主键去重使重复启动/中途断电后的重试不会重复发经验。 */
    private void importLegacy(Path indexPath) throws IOException, SQLException {
        if (!Files.exists(indexPath)) return;
        long size = Files.size(indexPath);
        long offset = importedOffset();
        // 文件被截断、替换或轮转后，旧偏移不再指向上一批记录；前缀校验防止从半个新文件中间开始，
        // 悄悄跳过替换后的开头记录。主键去重保证从 0 重放不会重复发奖。
        if (offset > size || (offset > 0 && !journalPrefix(indexPath).equals(importedPrefix()))) offset = 0;
        if (offset == size) return;
        int imported = 0, invalid = 0, lineNumber = 0;
        try (Connection connection = open();
             FileChannel channel = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            channel.position(offset);
            BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            connection.setAutoCommit(false);
            try (PreparedStatement game = gameInsert(connection); PreparedStatement participant = participantInsert(connection)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) continue;
                    GameRecord record;
                    try {
                        record = GSON.fromJson(line, GameRecord.class);
                        validate(record);
                        record.setMoveHistory(null);
                        record.setMoveEffects(null);
                    } catch (RuntimeException e) {
                        invalid++;
                        logger.warn("跳过损坏对局摘要，行号={}", lineNumber);
                        continue;
                    }
                    if (insert(game, participant, record)) imported++;
                    if (lineNumber % 500 == 0) connection.commit();
                }
                connection.commit();
                setImportedOffset(connection, size, journalPrefix(indexPath));
            } catch (IOException | SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
            reader.close();
        }
        logger.info("对局日志增量重放完成：新增={}，损坏行={}，字节={}-{}，来源={}", imported, invalid, offset, size, indexPath);
    }

    static void validate(GameRecord record) {
        if (record == null || record.getId() == null || !record.getId().matches("[A-Za-z0-9-]+")
                || record.getParticipants() == null || record.getParticipants().stream().anyMatch(p -> p == null)) {
            throw new IllegalArgumentException("对局记录缺少有效 ID 或参与者");
        }
    }

    boolean contains(String id) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM games WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        } catch (SQLException e) { throw failure(e); }
    }

    void insert(GameRecord record) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement game = gameInsert(connection); PreparedStatement participant = participantInsert(connection)) {
                insert(game, participant, record);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure(e); }
    }

    /** 摘要日志的安全偏移：索引事务提交后才推进，崩溃时最多重放已提交的行（主键幂等）。 */
    void markJournaled(long bytes) throws IOException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                setImportedOffset(connection, bytes, journalPrefix(indexPath));
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure(e); }
    }

    private long importedOffset() throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM meta WHERE key='legacy_import_bytes'"); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return 0;
            try { return Long.parseLong(result.getString(1)); } catch (NumberFormatException e) { return 0; }
        }
    }

    /** 已导入日志前缀的哈希；旧数据库没有该键时返回空串，使下一次启动安全回退到全量重放。 */
    private String importedPrefix() throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM meta WHERE key='legacy_import_prefix'"); ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private static void setImportedOffset(Connection connection, long bytes, String prefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, "legacy_import_bytes");
            statement.setString(2, Long.toString(bytes));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, "legacy_import_prefix");
            statement.setString(2, prefix);
            statement.executeUpdate();
        }
    }

    /** 读取日志开头至多 {@link #PREFIX_BYTES} 字节并计算 SHA-256；空文件返回空串的哈希。 */
    private static String journalPrefix(Path path) throws IOException {
        byte[] buffer = new byte[PREFIX_BYTES];
        int read = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(buffer);
            while (bytes.hasRemaining()) {
                int count = channel.read(bytes);
                if (count < 0) break;
                read += count;
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(buffer, read));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    long importedOffsetForTest() {
        try {
            return importedOffset();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static PreparedStatement gameInsert(Connection connection) throws SQLException {
        return connection.prepareStatement("INSERT INTO games(id,game_type,ended_at,summary) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING");
    }

    private static PreparedStatement participantInsert(Connection connection) throws SQLException {
        return connection.prepareStatement("INSERT INTO participants(game_id,position,identity_id,name,ai) VALUES(?,?,?,?,?)");
    }

    private static boolean insert(PreparedStatement game, PreparedStatement participant, GameRecord record) throws SQLException {
        game.setString(1, record.getId()); game.setString(2, record.getGameType());
        game.setLong(3, record.getEndedAt()); game.setString(4, GSON.toJson(record));
        if (game.executeUpdate() == 0) return false;
        for (int i = 0; i < record.getParticipants().size(); i++) {
            GameRecord.Participant player = record.getParticipants().get(i);
            participant.setString(1, record.getId()); participant.setInt(2, i);
            participant.setString(3, player.getIdentityId()); participant.setString(4, player.getName());
            participant.setInt(5, player.isAi() ? 1 : 0); participant.addBatch();
        }
        participant.executeBatch();
        participant.clearBatch();
        return true;
    }

    List<GameRecord> recent(int limit) {
        List<GameRecord> records = new ArrayList<>();
        read("SELECT summary FROM games ORDER BY seq DESC LIMIT ?", statement -> statement.setInt(1, limit), records::add);
        return records;
    }

    void visitIdentity(String identityId, Consumer<GameRecord> visitor) {
        if (identityId == null) return;
        read("SELECT summary FROM games WHERE id IN (SELECT game_id FROM participants WHERE identity_id=? AND ai=0) ORDER BY seq",
                statement -> statement.setString(1, identityId), visitor);
    }

    void visitName(String name, Consumer<GameRecord> visitor) {
        if (name == null) return;
        read("SELECT summary FROM games WHERE id IN (SELECT game_id FROM participants WHERE name=? AND ai=0) ORDER BY seq",
                statement -> statement.setString(1, name), visitor);
    }

    void visitWindow(long since, long until, Consumer<GameRecord> visitor) {
        read("SELECT summary FROM games WHERE ended_at>=? AND ended_at<? ORDER BY seq", statement -> {
            statement.setLong(1, since); statement.setLong(2, until);
        }, visitor);
    }

    GameRecord get(String id) {
        List<GameRecord> records = new ArrayList<>(1);
        read("SELECT summary FROM games WHERE id=?", statement -> statement.setString(1, id), records::add);
        return records.isEmpty() ? null : records.get(0);
    }

    String lastName(String identityId) {
        List<GameRecord> records = new ArrayList<>(1);
        read("SELECT summary FROM games WHERE id IN (SELECT game_id FROM participants WHERE identity_id=? AND ai=0 AND name IS NOT NULL) ORDER BY seq DESC LIMIT 1",
                statement -> statement.setString(1, identityId), records::add);
        if (!records.isEmpty()) {
            for (GameRecord.Participant participant : records.get(0).getParticipants()) {
                if (!participant.isAi() && identityId.equals(participant.getIdentityId()) && participant.getName() != null) {
                    return participant.getName();
                }
            }
        }
        return null;
    }

    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }

    private void read(String sql, Binder binder, Consumer<GameRecord> visitor) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) visitor.accept(GSON.fromJson(results.getString(1), GameRecord.class));
            }
        } catch (SQLException e) { throw failure(e); }
    }

    private static IllegalStateException failure(SQLException e) {
        return new IllegalStateException("无法访问对局历史索引", e);
    }
}
