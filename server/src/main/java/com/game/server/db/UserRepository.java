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
     * @return true on success; false if the username or email is already taken.
     */
    public boolean register(String username, String plainPassword, String email) {
        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
        String sql  = "INSERT INTO users (username, password_hash, emailaddress) VALUES (?, ?, ?)";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, email);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) return false; // ER_DUP_ENTRY
            throw new RuntimeException("register() failed", e);
        }
    }

    /**
     * Validates credentials by email address.
     * @return the User (including isAudioDev flag) if credentials are correct.
     */
    public Optional<User> authenticate(String email, String plainPassword) {
        String sql = "SELECT id, username, password_hash, is_admin, is_audio_dev " +
                     "FROM users WHERE emailaddress = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("password_hash");
                    if (BCrypt.checkpw(plainPassword, hash)) {
                        touchLastLogin(rs.getLong("id"), conn);
                        return Optional.of(new User(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getBoolean("is_admin"),
                                rs.getBoolean("is_audio_dev")
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
    public boolean setAudioDev(String username, boolean isAdmin) {
        String sql = "UPDATE users SET is_audio_dev = ? WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("setAudioDev() failed", e);
        }
    }

    public String getEmail(String username) {
        String sql = "SELECT emailaddress FROM users WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("emailaddress") : "";
            }
        } catch (SQLException e) {
            return "";
        }
    }

    public boolean isAdmin(String username) {
        String sql = "SELECT is_admin FROM users WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_admin");
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean setAdmin(String username, boolean isAdmin) {
        String sql = "UPDATE users SET is_admin = ? WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("setAdmin() failed", e);
        }
    }

    public boolean setBanned(String username, boolean ban) {
        String sql = "UPDATE users SET is_banned = ? WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, ban);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
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
