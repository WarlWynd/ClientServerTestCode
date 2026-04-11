package com.game.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Singleton that owns the MySQL connection details and bootstraps the schema.
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static DatabaseManager instance;

    private final String url;
    private final String user;
    private final String password;

    private static final String DDL_USERS = """
            CREATE TABLE IF NOT EXISTS users (
                id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
                username        VARCHAR(50)  NOT NULL UNIQUE,
                password_hash   VARCHAR(255) NOT NULL,
                emailaddress    VARCHAR(255) NOT NULL UNIQUE,
                created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                last_login      TIMESTAMP    NULL,
                is_admin        TINYINT(1)   NOT NULL DEFAULT 0,
                is_graphics_dev TINYINT(1)   NOT NULL DEFAULT 0,
                is_board_dev    TINYINT(1)   NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_SESSIONS = """
            CREATE TABLE IF NOT EXISTS sessions (
                token      VARCHAR(36)  NOT NULL PRIMARY KEY,
                user_id    BIGINT       NOT NULL,
                username   VARCHAR(50)  NOT NULL,
                created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP    NOT NULL,
                CONSTRAINT fk_session_user
                    FOREIGN KEY (user_id) REFERENCES users(id)
                    ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_GAME_VERSIONS = """
            CREATE TABLE IF NOT EXISTS game_versions (
                id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
                VersionMajor    INT           NOT NULL,
                VersionMinor    INT           NOT NULL,
                blurb           TEXT          NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_CHARACTERS = """
            CREATE TABLE IF NOT EXISTS characters (
                id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
                user_id         BIGINT      NOT NULL,
                character_name  VARCHAR(50) NOT NULL UNIQUE,
                created_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
                stat_hp         INT         NOT NULL DEFAULT 100,
                stat_mana       INT         NOT NULL DEFAULT 50,
                stat_int        INT         NOT NULL DEFAULT 1,
                stat_str        INT         NOT NULL DEFAULT 1,
                stat_wis        INT         NOT NULL DEFAULT 1,
                stat_cha        INT         NOT NULL DEFAULT 1,
                stat_sta        INT         NOT NULL DEFAULT 1,
                stat_agi        INT         NOT NULL DEFAULT 1,
                stat_dex        INT         NOT NULL DEFAULT 1,
                stat_luk        INT         NOT NULL DEFAULT 1,
                CONSTRAINT fk_char_user
                    FOREIGN KEY (user_id) REFERENCES users(id)
                    ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_BOARDS = """
            CREATE TABLE IF NOT EXISTS boards (
                id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
                user_id     BIGINT        NOT NULL,
                name        VARCHAR(100)  NOT NULL,
                rows        INT           NOT NULL,
                cols        INT           NOT NULL,
                csv_data    MEDIUMTEXT    NOT NULL,
                created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP,
                CONSTRAINT fk_board_user
                    FOREIGN KEY (user_id) REFERENCES users(id)
                    ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_BOARD_PROGRESS = """
            CREATE TABLE IF NOT EXISTS user_board_progress (
                id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
                user_id     BIGINT       NOT NULL,
                board_id    BIGINT       NOT NULL,
                status      ENUM('assigned','in_progress','completed')
                                         NOT NULL DEFAULT 'assigned',
                score       INT          NOT NULL DEFAULT 0,
                attempts    INT          NOT NULL DEFAULT 0,
                assigned_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP   NULL,
                UNIQUE KEY uq_user_board (user_id, board_id),
                CONSTRAINT fk_prog_user
                    FOREIGN KEY (user_id)  REFERENCES users(id)  ON DELETE CASCADE,
                CONSTRAINT fk_prog_board
                    FOREIGN KEY (board_id) REFERENCES boards(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_SERVER_SETTINGS = """
            CREATE TABLE IF NOT EXISTS ServerSettings (
                id            INT          NOT NULL DEFAULT 1,
                gravity       FLOAT        NOT NULL DEFAULT 0.5,
                jump_strength FLOAT        NOT NULL DEFAULT 8.0,
                run_speed     FLOAT        NOT NULL DEFAULT 6.0,
                updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP,
                updated_by    VARCHAR(50)  NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_SERVER_CHANGES = """
            CREATE TABLE IF NOT EXISTS MultiplayerServerChanges (
                id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
                SrvrMacroVrsn   INT           NOT NULL,
                SrvrMicroVrsn   INT           NOT NULL,
                changed_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                blurb           TEXT          NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String CHECK_COLUMN =
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";

    private DatabaseManager(String host, int port, String dbName,
                            String user, String password) {
        this.url = "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
                .formatted(host, port, dbName);
        this.user     = user;
        this.password = password;
    }

    public static void initialize(String host, int port, String dbName,
                                  String user, String password) {
        instance = new DatabaseManager(host, port, dbName, user, password);
        instance.createSchema();
        log.info("Database schema verified/created.");
    }

    public static DatabaseManager getInstance() {
        if (instance == null)
            throw new IllegalStateException("DatabaseManager.initialize() has not been called.");
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private void createSchema() {
        try (Connection conn = getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute(DDL_USERS);
            stmt.execute(DDL_SESSIONS);
            addColumnIfMissing(conn, "users", "is_admin",        "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "users", "is_graphics_dev", "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "users", "is_board_dev",    "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "users", "is_audio_dev",    "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "users", "emailaddress",    "VARCHAR(255) NOT NULL DEFAULT ''");
            stmt.execute(DDL_SERVER_CHANGES);
            stmt.execute(DDL_GAME_VERSIONS);
            stmt.execute(DDL_CHARACTERS);
            addColumnIfMissing(conn, "characters", "stat_hp",   "INT NOT NULL DEFAULT 100");
            addColumnIfMissing(conn, "characters", "stat_mana", "INT NOT NULL DEFAULT 50");
            addColumnIfMissing(conn, "characters", "stat_int",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_str",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_wis",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_cha",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_sta",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_agi",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_dex",  "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "characters", "stat_luk",  "INT NOT NULL DEFAULT 1");
            stmt.execute(DDL_SERVER_SETTINGS);
            addColumnIfMissing(conn, "ServerSettings", "allow_remember_password", "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "ServerSettings", "show_test_npc",           "TINYINT(1)   NOT NULL DEFAULT 1");
            addColumnIfMissing(conn, "ServerSettings", "test_npc_x",              "FLOAT        NOT NULL DEFAULT 1200");
            addColumnIfMissing(conn, "ServerSettings", "test_npc_y",              "FLOAT        NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "ServerSettings", "local_server_host",       "VARCHAR(255) NOT NULL DEFAULT 'localhost'");
            addColumnIfMissing(conn, "ServerSettings", "local_server_port",       "INT          NOT NULL DEFAULT 9876");
            addColumnIfMissing(conn, "ServerSettings", "external_server_host",    "VARCHAR(255) NOT NULL DEFAULT ''");
            addColumnIfMissing(conn, "ServerSettings", "external_server_port",    "INT          NOT NULL DEFAULT 9876");
            addColumnIfMissing(conn, "ServerSettings", "allow_external_admin",    "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "ServerSettings", "allow_external_dev",      "TINYINT(1)   NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "ServerSettings", "reboot_delay_secs",       "INT          NOT NULL DEFAULT 60");
            addColumnIfMissing(conn, "ServerSettings", "reboot_message",          "VARCHAR(500) NOT NULL DEFAULT ''");
            stmt.execute(DDL_BOARDS);
            stmt.execute(DDL_BOARD_PROGRESS);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise database schema.", e);
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String definition)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CHECK_COLUMN)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                    }
                }
            }
        }
    }
}
