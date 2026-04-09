package com.game.server.db;

import com.game.server.model.Session;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access object for the {@code sessions} table.
 *
 * Session tokens are random UUIDs.  Expiry is checked server-side (DB) so
 * a stolen token auto-expires even if the client never sends LOGOUT.
 */
public class SessionRepository {

    private static final int SESSION_TTL_HOURS = 24;

    private final DatabaseManager db = DatabaseManager.getInstance();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Persists a new session for the given user and returns it.
     */
    public Session create(long userId, String username) {
        String        token     = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(SESSION_TTL_HOURS);

        String sql = "INSERT INTO sessions (token, user_id, username, expires_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.setString(3, username);
            ps.setTimestamp(4, Timestamp.valueOf(expiresAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("create() failed", e);
        }
        return new Session(token, userId, username, expiresAt);
    }

    /**
     * Looks up a session by token and returns it only if it has not expired.
     */
    public Optional<Session> validate(String token) {
        String sql = """
                SELECT token, user_id, username, expires_at
                FROM   sessions
                WHERE  token = ? AND expires_at > NOW()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Session(
                            rs.getString("token"),
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getTimestamp("expires_at").toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("validate() failed", e);
        }
        return Optional.empty();
    }

    /**
     * Deletes all sessions for a given user and returns the evicted tokens.
     * Used to enforce single-session login.
     */
    public List<String> invalidateByUserId(long userId) {
        List<String> tokens = new ArrayList<>();
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT token FROM sessions WHERE user_id = ?")) {
                sel.setLong(1, userId);
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) tokens.add(rs.getString("token"));
                }
            }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM sessions WHERE user_id = ?")) {
                del.setLong(1, userId);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("invalidateByUserId() failed", e);
        }
        return tokens;
    }

    /**
     * Deletes a session (logout).
     */
    public void invalidate(String token) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("invalidate() failed", e);
        }
    }

    /**
     * Removes all expired sessions.  Called periodically by the server scheduler.
     */
    public int purgeExpired() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sessions WHERE expires_at <= NOW()")) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("purgeExpired() failed", e);
        }
    }
}
