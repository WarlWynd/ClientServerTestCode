package com.game.server.db;

import com.game.server.model.User;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;

/**
 * Data-access object for the {@code users} table.
 *
 * Passwords are stored as BCrypt hashes (cost factor 12).
 */
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final DatabaseManager db = DatabaseManager.getInstance();

    /**
     * Creates a new account.
     *
     * @return {@code true} on success; {@code false} if the username is already taken.
     * @throws RuntimeException on any unexpected SQL error.
     */
    public boolean register(String username, String plainPassword) {
        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
        String sql  = "INSERT INTO users (username, password_hash) VALUES (?, ?)";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) return false; // ER_DUP_ENTRY -- duplicate username
            throw new RuntimeException("register() failed", e);
        }
    }

    /**
     * Validates credentials.
     *
     * @return the {@link User} if credentials are correct, otherwise empty.
     */
    public Optional<User> authenticate(String username, String plainPassword) {
        String sql = "SELECT id, username, password_hash, is_admin FROM users WHERE username = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("password_hash");
                    if (BCrypt.checkpw(plainPassword, hash)) {
                        touchLastLogin(rs.getLong("id"), conn);
                        return Optional.of(new User(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getBoolean("is_admin")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("authenticate() failed", e);
        }
        return Optional.empty();
    }

    /**
     * Ensures an admin account exists. Called at server startup.
     * If the username already exists as an admin, this is a no-op.
     * If the username exists as a regular user, it is promoted to admin.
     * If the username does not exist, it is created as an admin.
     */
    public void createAdminIfNotExists(String username, String plainPassword) {
        String checkSql = "SELECT id FROM users WHERE username = ? AND is_admin = TRUE";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return; // already exists as admin
            }
        } catch (SQLException e) {
            throw new RuntimeException("createAdminIfNotExists check failed", e);
        }

        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
        String upsertSql = """
                INSERT INTO users (username, password_hash, is_admin)
                VALUES (?, ?, TRUE)
                ON DUPLICATE KEY UPDATE is_admin = TRUE, password_hash = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, hash);
            ps.executeUpdate();
            log.info("Admin account '{}' ensured.", username);
        } catch (SQLException e) {
            throw new RuntimeException("createAdminIfNotExists failed", e);
        }
    }

    // -- Private helpers --

    private void touchLastLogin(long userId, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET last_login = NOW() WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }
}
