package com.chat.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager quản lý cơ sở dữ liệu SQLite nhúng (Embedded SQLite) cho ChatServer.
 *
 * TÍNH NĂNG CHÍNH:
 * 1. Không cần cài đặt phần mềm Database Server riêng biệt (nhẹ, tự động tạo file database .db).
 * 2. Lưu trữ bền vững (Persistence):
 *    - Danh sách người dùng đã từng đăng nhập và thời gian online gần nhất.
 *    - Danh sách phòng chat (Room/Group) được khởi tạo sẵn hoặc do Client tạo động.
 *    - Toàn bộ lịch sử tin nhắn (Broadcast, Room Multicast, Chat 1-1 riêng tư).
 * 3. Chống nghẽn luồng và khóa database (Concurrency Safe):
 *    - Kích hoạt chế độ WAL (Write-Ahead Logging) bằng PRAGMA journal_mode = WAL.
 *      Cho phép nhiều luồng đọc đồng thời mà không bị khóa khi có một luồng đang ghi dữ liệu.
 *    - Toàn bộ phương thức ghi dữ liệu đều được bảo vệ bằng khối đồng bộ synchronized.
 */
public class DatabaseManager {

    private static final String DEFAULT_DB_FILE = "chat_database.db";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String dbFilePath;
    private final String dbUrl;
    private Connection connection;

    public DatabaseManager() {
        this(DEFAULT_DB_FILE);
    }

    public DatabaseManager(String dbFilePath) {
        this.dbFilePath = dbFilePath;
        this.dbUrl = "jdbc:sqlite:" + dbFilePath;
    }

    /**
     * Dọn dẹp an toàn toàn bộ các tệp liên quan đến SQLite (.db, .db-wal, .db-shm).
     */
    public static void deleteDatabaseFiles(String dbFilePath) {
        if (dbFilePath == null || dbFilePath.isEmpty()) return;
        File dbFile = new File(dbFilePath);
        if (dbFile.exists()) dbFile.delete();
        File walFile = new File(dbFilePath + "-wal");
        if (walFile.exists()) walFile.delete();
        File shmFile = new File(dbFilePath + "-shm");
        if (shmFile.exists()) shmFile.delete();
    }

    /**
     * Khởi tạo kết nối SQLite và tạo cấu trúc các bảng nếu chưa tồn tại.
     * Tự động phục hồi nếu gặp file -wal / -shm mồ côi (SQLITE_IOERR).
     */
    public synchronized void init() throws SQLException {
        try {
            // Nạp driver SQLite JDBC
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Không tìm thấy driver org.sqlite.JDBC trong classpath!", e);
        }

        try {
            openAndConfigure();
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("SQLITE_IOERR")) {
                close();
                deleteDatabaseFiles(dbFilePath);
                openAndConfigure();
            } else {
                throw e;
            }
        }
    }

    private void openAndConfigure() throws SQLException {
        connection = DriverManager.getConnection(dbUrl);

        // Kích hoạt chế độ WAL (Write-Ahead Logging) cho SQLite để tối ưu đọc/ghi đa luồng
        try (Statement pragmaStmt = connection.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode = WAL;");
            pragmaStmt.execute("PRAGMA synchronous = NORMAL;");
        }

        createTables();
        seedDefaultRooms();
    }

    /**
     * Tạo các bảng: users, rooms, messages.
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 1. Bảng lưu trữ người dùng
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    username TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL
                );
            """);

            // 2. Bảng lưu trữ các phòng chat (Multicast Rooms)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rooms (
                    room_name TEXT PRIMARY KEY,
                    created_by TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
            """);

            // 3. Bảng lưu trữ lịch sử tin nhắn
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sender TEXT NOT NULL,
                    recipient TEXT NOT NULL,
                    content TEXT NOT NULL,
                    message_type TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                );
            """);

            // Tạo index trên timestamp và recipient để truy vấn lịch sử nhanh
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient, timestamp);");
        }
    }

    /**
     * Khởi tạo một số phòng mặc định khi database mới được tạo.
     */
    private void seedDefaultRooms() {
        String[] defaultRooms = {"Phòng Chung", "Nhóm Lập Trình", "Học Tập", "Giải Trí"};
        long now = System.currentTimeMillis();
        for (String room : defaultRooms) {
            try {
                createRoom(room, "SYSTEM");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Lưu hoặc cập nhật người dùng khi đăng nhập.
     */
    public synchronized void saveOrUpdateUser(String username) {
        if (username == null || username.trim().isEmpty() || connection == null) return;
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO users(username, created_at, last_seen)
            VALUES(?, ?, ?)
            ON CONFLICT(username) DO UPDATE SET last_seen = excluded.last_seen;
        """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.trim());
            pstmt.setLong(2, now);
            pstmt.setLong(3, now);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi lưu người dùng: " + e.getMessage());
        }
    }

    /**
     * Tạo một phòng chat mới. Trả về true nếu tạo thành công, false nếu phòng đã tồn tại.
     */
    public synchronized boolean createRoom(String roomName, String createdBy) {
        if (roomName == null || roomName.trim().isEmpty() || connection == null) return false;
        String cleanName = roomName.trim();
        String sql = "INSERT INTO rooms(room_name, created_by, created_at) VALUES(?, ?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cleanName);
            pstmt.setString(2, (createdBy != null && !createdBy.isEmpty()) ? createdBy : "SYSTEM");
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            // Phòng đã tồn tại (PRIMARY KEY violation)
            return false;
        }
    }

    /**
     * Lấy toàn bộ danh sách phòng hiện có từ SQLite.
     */
    public synchronized List<String> getAllRooms() {
        List<String> rooms = new ArrayList<>();
        if (connection == null) return rooms;
        String sql = "SELECT room_name FROM rooms ORDER BY created_at ASC;";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rooms.add(rs.getString("room_name"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi lấy danh sách phòng: " + e.getMessage());
        }
        return rooms;
    }

    /**
     * Lưu tin nhắn vào lịch sử database.
     *
     * @param sender Người gửi
     * @param recipient Người nhận ("ALL", tên phòng "ROOM:<tên_phòng>", hoặc username nhận tin riêng)
     * @param content Nội dung tin nhắn
     * @param messageType Loại tin ("BROADCAST", "ROOM", "PRIVATE", "SYSTEM")
     * @param timestamp Thời gian gửi
     */
    public synchronized void saveMessage(String sender, String recipient, String content, String messageType, long timestamp) {
        if (connection == null) return;
        String sql = "INSERT INTO messages(sender, recipient, content, message_type, timestamp) VALUES(?, ?, ?, ?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, sender != null ? sender : "UNKNOWN");
            pstmt.setString(2, recipient != null ? recipient : "ALL");
            pstmt.setString(3, content != null ? content : "");
            pstmt.setString(4, messageType != null ? messageType : "TEXT");
            pstmt.setLong(5, timestamp > 0 ? timestamp : System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi lưu tin nhắn: " + e.getMessage());
        }
    }

    /**
     * Lấy các tin nhắn gần nhất cho một phòng, toàn server (ALL), hoặc giữa 2 người dùng.
     */
    public synchronized List<String> getRecentMessages(String target, int limit) {
        List<String> messages = new ArrayList<>();
        if (connection == null || target == null) return messages;

        String sql;
        boolean isPrivate = !target.equalsIgnoreCase("ALL") && !target.startsWith("ROOM:");

        if (isPrivate) {
            // Tin nhắn riêng tư: Lấy tin giữa user1 và user2
            sql = "SELECT sender, recipient, content, timestamp FROM messages "
                + "WHERE message_type = 'PRIVATE' AND (recipient = ? OR sender = ?) "
                + "ORDER BY timestamp DESC LIMIT ?;";
        } else {
            // Tin broadcast hoặc room multicast
            sql = "SELECT sender, recipient, content, timestamp FROM messages "
                + "WHERE recipient = ? "
                + "ORDER BY timestamp DESC LIMIT ?;";
        }

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            if (isPrivate) {
                pstmt.setString(1, target);
                pstmt.setString(2, target);
                pstmt.setInt(3, limit);
            } else {
                pstmt.setString(1, target);
                pstmt.setInt(2, limit);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                List<String> reverseOrder = new ArrayList<>();
                while (rs.next()) {
                    String sender = rs.getString("sender");
                    String content = rs.getString("content");
                    long ts = rs.getLong("timestamp");
                    String time = LocalTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(TIME_FORMATTER);
                    reverseOrder.add("[" + time + "] " + sender + ": " + content);
                }
                // Đảo ngược lại theo thứ tự thời gian tăng dần
                for (int i = reverseOrder.size() - 1; i >= 0; i--) {
                    messages.add(reverseOrder.get(i));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi lấy lịch sử tin nhắn: " + e.getMessage());
        }
        return messages;
    }

    /**
     * Đóng an toàn kết nối cơ sở dữ liệu khi Server tắt.
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
                connection = null;
            } catch (SQLException ignored) {
            }
        }
    }
}
