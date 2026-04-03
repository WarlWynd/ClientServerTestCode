package com.game.server.db;

import com.game.server.model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.*;
import java.util.Optional;

/**
 * Data-access object for the users table.
 * Passwords are stored as BCrypt hashes (cost factor 12).
 */
public class UserRepository {

    private final DatabaseManager db = DatabaseManager.getInstance();

    /**
     * Creates a new account.
     * @return true on success; false if the username is already taken.
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
            if (e.getErrorCode() == 1062) return false; // ER_DUP_ENTRY
            throw new RuntimeException("register() failed", e);
        }
    }

    /**
     * Validates credentials.
     * @return the User (including isAudioAdmin flag) if credentials are correct.
     */
    public Optional<User> authenticate(String username, String plainPassword) {
        String sql = "SELECT id, username, password_hash, is_audio_admin " +
                     "FROM users WHERE username = ?";

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
                                rs.getBoolean("is_audio_admin")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("authenticate() failed", e);
        }
        return Optional.empty();
    }

    /**
     * Grants or revokes the audio admin role for a given username.
     * @return true if the user was found and updated.
     */
    public boolean setAudioAdmin(String username, boolean isAdmin) {
        String sql = "UPDATE users SET is_audio_admin = ? WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("setAudioAdmin() failed", e);
        }
    }

    private void touchLastLogin(long userId, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET last_login = NOW() WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }
}
