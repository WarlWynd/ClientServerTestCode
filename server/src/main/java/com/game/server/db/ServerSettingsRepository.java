package com.game.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Reads and writes the single-row ServerSettings table.
 */
public class ServerSettingsRepository {

    private static final Logger log = LoggerFactory.getLogger(ServerSettingsRepository.class);

    /** Loaded game settings. */
    public record Settings(float gravity, float jumpStrength, float runSpeed) {
        public static final Settings DEFAULTS = new Settings(0.5f, 8.0f, 6.0f);
    }

    /**
     * Loads settings from DB, inserting defaults if the row doesn't exist yet.
     */
    public Settings load() {
        String upsert = """
                INSERT INTO ServerSettings (id, gravity, jump_strength, run_speed)
                VALUES (1, 0.5, 8.0, 6.0)
                ON DUPLICATE KEY UPDATE id = id
                """;
        String select = "SELECT gravity, jump_strength, run_speed FROM ServerSettings WHERE id = 1";
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute(upsert);
            try (ResultSet rs = s.executeQuery(select)) {
                if (rs.next()) {
                    return new Settings(rs.getFloat("gravity"),
                                        rs.getFloat("jump_strength"),
                                        rs.getFloat("run_speed"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load ServerSettings: {}", e.getMessage(), e);
        }
        return Settings.DEFAULTS;
    }

    /**
     * Saves (upserts) settings to the DB.
     */
    public boolean save(float gravity, float jumpStrength, float runSpeed, String updatedBy) {
        String sql = """
                INSERT INTO ServerSettings (id, gravity, jump_strength, run_speed, updated_by)
                VALUES (1, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    gravity = VALUES(gravity),
                    jump_strength = VALUES(jump_strength),
                    run_speed = VALUES(run_speed),
                    updated_by = VALUES(updated_by)
                """;
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFloat(1, gravity);
            ps.setFloat(2, jumpStrength);
            ps.setFloat(3, runSpeed);
            ps.setString(4, updatedBy);
            ps.executeUpdate();
            log.info("ServerSettings saved by '{}': gravity={}, jump={}, runSpeed={}",
                    updatedBy, gravity, jumpStrength, runSpeed);
            return true;
        } catch (SQLException e) {
            log.error("Failed to save ServerSettings: {}", e.getMessage(), e);
            return false;
        }
    }

    private static Connection db() throws SQLException {
        return DatabaseManager.getInstance().getConnection();
    }
}
