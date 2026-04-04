package com.game.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Data-access object for the characters table.
 * Loads name_blacklist.csv from the classpath at construction.
 */
public class CharacterRepository {

    private static final Logger log = LoggerFactory.getLogger(CharacterRepository.class);

    private final DatabaseManager db        = DatabaseManager.getInstance();
    private final Set<String>     blacklist = new HashSet<>();

    public CharacterRepository() {
        loadBlacklist();
    }

    // ── Blacklist ─────────────────────────────────────────────────────────────

    private void loadBlacklist() {
        try (InputStream in = getClass().getResourceAsStream("/name_blacklist.csv")) {
            if (in == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    blacklist.add(line.toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("Could not load name_blacklist.csv: {}", e.getMessage());
        }
        if (!blacklist.isEmpty()) log.info("Loaded {} blacklisted character names.", blacklist.size());
    }

    public boolean isBlacklisted(String name) {
        return blacklist.contains(name.trim().toLowerCase());
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Returns the character name for the given user, or null if none. */
    public String getCharacterName(long userId) {
        String sql = "SELECT character_name FROM characters WHERE user_id = ? LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("character_name") : null;
            }
        } catch (SQLException e) {
            log.error("getCharacterName() failed: {}", e.getMessage());
            return null;
        }
    }

    /** Returns true if the user already has at least one character. */
    public boolean hasCharacter(long userId) {
        String sql = "SELECT COUNT(*) FROM characters WHERE user_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("hasCharacter() failed: {}", e.getMessage());
            return false;
        }
    }

    /** Returns true if the name is not taken in the DB and not blacklisted. */
    public boolean isNameAvailable(String name) {
        if (isBlacklisted(name)) return false;
        String sql = "SELECT COUNT(*) FROM characters WHERE LOWER(character_name) = LOWER(?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 0;
            }
        } catch (SQLException e) {
            log.error("isNameAvailable() failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Creates a character for the given user.
     * @return true on success; false if name is taken, blacklisted, or user already has a character.
     */
    public boolean createCharacter(long userId, String name) {
        String sql = "INSERT INTO characters (user_id, character_name) VALUES (?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, name.trim());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) return false; // ER_DUP_ENTRY
            throw new RuntimeException("createCharacter() failed", e);
        }
    }
}
