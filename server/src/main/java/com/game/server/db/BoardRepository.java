package com.game.server.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD operations for the {@code boards} table.
 *
 * Each board row is owned by a user (via user_id) and stores the full CSV
 * layout so boards are portable and not tied to the local filesystem.
 */
public class BoardRepository {

    public record BoardRecord(long id, long userId, String name,
                              int rows, int cols, String csvData,
                              Timestamp createdAt, Timestamp updatedAt) {}

    private final DatabaseManager db;

    public BoardRepository() {
        this.db = DatabaseManager.getInstance();
    }

    /** Save a new board for the given user. Returns the generated id. */
    public long save(long userId, String name, int rows, int cols, String csvData)
            throws SQLException {
        String sql = "INSERT INTO boards (user_id, name, `rows`, `cols`, csv_data) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setInt(3, rows);
            ps.setInt(4, cols);
            ps.setString(5, csvData);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("Board insert returned no generated key.");
    }

    /** Update the CSV data and dimensions of an existing board owned by the given user. */
    public boolean update(long boardId, long userId, String name, int rows, int cols, String csvData)
            throws SQLException {
        String sql = "UPDATE boards SET name=?, `rows`=?, `cols`=?, csv_data=? " +
                     "WHERE id=? AND user_id=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, rows);
            ps.setInt(3, cols);
            ps.setString(4, csvData);
            ps.setLong(5, boardId);
            ps.setLong(6, userId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Returns all boards owned by the given user, ordered by name. */
    public List<BoardRecord> findByUser(long userId) throws SQLException {
        String sql = "SELECT id, user_id, name, `rows`, `cols`, csv_data, created_at, updated_at " +
                     "FROM boards WHERE user_id=? ORDER BY name";
        List<BoardRecord> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(fromRow(rs));
            }
        }
        return result;
    }

    /** Returns all boards across all users, ordered by name. */
    public List<BoardRecord> findAll() throws SQLException {
        String sql = "SELECT id, user_id, name, `rows`, `cols`, csv_data, created_at, updated_at " +
                     "FROM boards ORDER BY name";
        List<BoardRecord> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             java.sql.Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) result.add(fromRow(rs));
        }
        return result;
    }

    /** Returns a single board by id, or null if not found. */
    public BoardRecord findById(long boardId) throws SQLException {
        String sql = "SELECT id, user_id, name, `rows`, `cols`, csv_data, created_at, updated_at " +
                     "FROM boards WHERE id=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return fromRow(rs);
            }
        }
        return null;
    }

    /** Deletes a board. Only succeeds if the board belongs to userId. */
    public boolean delete(long boardId, long userId) throws SQLException {
        String sql = "DELETE FROM boards WHERE id=? AND user_id=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, boardId);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    private static BoardRecord fromRow(ResultSet rs) throws SQLException {
        return new BoardRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("name"),
                rs.getInt("rows"),
                rs.getInt("cols"),
                rs.getString("csv_data"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at"));
    }

    // ── Progress tracking ─────────────────────────────────────────────────────

    public record ProgressRecord(long id, long userId, long boardId, String status,
                                 int score, int attempts,
                                 Timestamp assignedAt, Timestamp completedAt) {}

    /** Assign a board to a user (no-op if already assigned). */
    public void assign(long userId, long boardId) throws SQLException {
        String sql = "INSERT IGNORE INTO user_board_progress (user_id, board_id) VALUES (?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, boardId);
            ps.executeUpdate();
        }
    }

    /** Record an attempt; update status and score. */
    public void recordAttempt(long userId, long boardId, boolean completed, int score)
            throws SQLException {
        String sql = """
                INSERT INTO user_board_progress (user_id, board_id, status, score, attempts, completed_at)
                VALUES (?, ?, ?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE
                    attempts    = attempts + 1,
                    score       = GREATEST(score, VALUES(score)),
                    status      = IF(VALUES(status)='completed', 'completed', status),
                    completed_at = IF(VALUES(status)='completed' AND completed_at IS NULL,
                                     NOW(), completed_at)
                """;
        String status = completed ? "completed" : "in_progress";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, boardId);
            ps.setString(3, status);
            ps.setInt(4, score);
            ps.setTimestamp(5, completed ? new Timestamp(System.currentTimeMillis()) : null);
            ps.executeUpdate();
        }
    }

    /** Returns all progress rows for a user, joined with board name. */
    public List<ProgressRecord> getProgressForUser(long userId) throws SQLException {
        String sql = """
                SELECT p.id, p.user_id, p.board_id, p.status, p.score,
                       p.attempts, p.assigned_at, p.completed_at
                FROM user_board_progress p
                WHERE p.user_id = ?
                ORDER BY p.assigned_at
                """;
        List<ProgressRecord> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(progressFromRow(rs));
            }
        }
        return result;
    }

    /** Returns the progress row for a specific user+board, or null if not assigned. */
    public ProgressRecord getProgress(long userId, long boardId) throws SQLException {
        String sql = "SELECT id, user_id, board_id, status, score, attempts, assigned_at, completed_at " +
                     "FROM user_board_progress WHERE user_id=? AND board_id=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return progressFromRow(rs);
            }
        }
        return null;
    }

    private static ProgressRecord progressFromRow(ResultSet rs) throws SQLException {
        return new ProgressRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("board_id"),
                rs.getString("status"),
                rs.getInt("score"),
                rs.getInt("attempts"),
                rs.getTimestamp("assigned_at"),
                rs.getTimestamp("completed_at"));
    }
}
