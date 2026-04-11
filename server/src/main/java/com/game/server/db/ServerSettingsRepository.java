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
    public record Settings(float gravity, float jumpStrength, float runSpeed,
                           boolean allowRememberPassword, boolean showTestNpc,
                           float testNpcX, float testNpcY,
                           String localServerHost, int localServerPort,
                           String externalServerHost, int externalServerPort,
                           boolean allowExternalAdmin, boolean allowExternalDev,
                           int rebootDelaySecs, String rebootMessage) {
        public static final Settings DEFAULTS = new Settings(
                0.5f, 8.0f, 6.0f,
                false, true, 1200f, 0f,
                "localhost", 9876, "", 9876,
                false, false,
                60, "");
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
        String select = """
                SELECT gravity, jump_strength, run_speed,
                       allow_remember_password, show_test_npc, test_npc_x, test_npc_y,
                       local_server_host, local_server_port,
                       external_server_host, external_server_port,
                       allow_external_admin, allow_external_dev,
                       reboot_delay_secs, reboot_message
                FROM ServerSettings WHERE id = 1
                """;
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute(upsert);
            try (ResultSet rs = s.executeQuery(select)) {
                if (rs.next()) {
                    return new Settings(
                            rs.getFloat("gravity"),
                            rs.getFloat("jump_strength"),
                            rs.getFloat("run_speed"),
                            rs.getBoolean("allow_remember_password"),
                            rs.getBoolean("show_test_npc"),
                            rs.getFloat("test_npc_x"),
                            rs.getFloat("test_npc_y"),
                            rs.getString("local_server_host"),
                            rs.getInt("local_server_port"),
                            rs.getString("external_server_host"),
                            rs.getInt("external_server_port"),
                            rs.getBoolean("allow_external_admin"),
                            rs.getBoolean("allow_external_dev"),
                            rs.getInt("reboot_delay_secs"),
                            rs.getString("reboot_message"));
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
    public boolean save(float gravity, float jumpStrength, float runSpeed,
                        boolean allowRememberPassword, boolean showTestNpc,
                        float testNpcX, float testNpcY,
                        String localServerHost, int localServerPort,
                        String externalServerHost, int externalServerPort,
                        boolean allowExternalAdmin, boolean allowExternalDev,
                        int rebootDelaySecs, String rebootMessage,
                        String updatedBy) {
        String sql = """
                INSERT INTO ServerSettings
                    (id, gravity, jump_strength, run_speed,
                     allow_remember_password, show_test_npc, test_npc_x, test_npc_y,
                     local_server_host, local_server_port,
                     external_server_host, external_server_port,
                     allow_external_admin, allow_external_dev,
                     reboot_delay_secs, reboot_message,
                     updated_by)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    gravity                 = VALUES(gravity),
                    jump_strength           = VALUES(jump_strength),
                    run_speed               = VALUES(run_speed),
                    allow_remember_password = VALUES(allow_remember_password),
                    show_test_npc           = VALUES(show_test_npc),
                    test_npc_x              = VALUES(test_npc_x),
                    test_npc_y              = VALUES(test_npc_y),
                    local_server_host       = VALUES(local_server_host),
                    local_server_port       = VALUES(local_server_port),
                    external_server_host    = VALUES(external_server_host),
                    external_server_port    = VALUES(external_server_port),
                    allow_external_admin    = VALUES(allow_external_admin),
                    allow_external_dev      = VALUES(allow_external_dev),
                    reboot_delay_secs       = VALUES(reboot_delay_secs),
                    reboot_message          = VALUES(reboot_message),
                    updated_by              = VALUES(updated_by)
                """;
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFloat(1, gravity);
            ps.setFloat(2, jumpStrength);
            ps.setFloat(3, runSpeed);
            ps.setBoolean(4, allowRememberPassword);
            ps.setBoolean(5, showTestNpc);
            ps.setFloat(6, testNpcX);
            ps.setFloat(7, testNpcY);
            ps.setString(8, localServerHost);
            ps.setInt(9, localServerPort);
            ps.setString(10, externalServerHost);
            ps.setInt(11, externalServerPort);
            ps.setBoolean(12, allowExternalAdmin);
            ps.setBoolean(13, allowExternalDev);
            ps.setInt(14, rebootDelaySecs);
            ps.setString(15, rebootMessage);
            ps.setString(16, updatedBy);
            ps.executeUpdate();
            log.info("ServerSettings saved by '{}': gravity={}, jump={}, runSpeed={}, rememberPwd={}, testNpc={}, local={}:{}, ext={}:{}, extAdmin={}, extDev={}, reboot={}s",
                    updatedBy, gravity, jumpStrength, runSpeed,
                    allowRememberPassword, showTestNpc,
                    localServerHost, localServerPort,
                    externalServerHost, externalServerPort,
                    allowExternalAdmin, allowExternalDev, rebootDelaySecs);
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
