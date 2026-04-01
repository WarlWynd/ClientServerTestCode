package com.game.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton that owns the MySQL connection details and bootstraps the schema.
 *
 * NOTE: For a production service replace this with HikariCP or a similar
 * connection pool. Each call to getConnection() currently opens a new
 * physical connection, which is fine for a prototype.
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static DatabaseManager instance;

    private final String url;
    private final String user;
    private final String password;

    // ── Schema DDL ───────────────────────────────────────────────────────────

    private static final String DDL_USERS = """
            CREATE TABLE IF NOT EXISTS users (
                id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
                username      VARCHAR(50)  NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                is_admin      BOOLEAN      NOT NULL DEFAULT FALSE,
                created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                last_login    TIMESTAMP    NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_USERS_ADD_ADMIN_COL =
            "ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;";

    private static final String DDL_USERS_ADD_BANNED_COL =
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE;";

    private static final String DDL_USERS_ADD_DEVELOPER_COL =
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS is_developer BOOLEAN NOT NULL DEFAULT FALSE;";

    private static final String DDL_SESSIONS = """
            CREATE TABLE IF NOT EXISTS sessions (
                token      VARCHAR(36)  NOT NULL PRIMARY KEY,
                user_id    BIGINT       NOT NULL,
                username   VARCHAR(50)  NOT NULL,
                ip_address VARCHAR(45)  NULL,
                created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP    NOT NULL,
                CONSTRAINT fk_session_user
                    FOREIGN KEY (user_id) REFERENCES users(id)
                    ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String DDL_SESSIONS_ADD_IP_COL =
            "ALTER TABLE sessions ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45) NULL;";

    // ── Initialisation ───────────────────────────────────────────────────────

    private DatabaseManager(String host, int port, String dbName,
                            String user, String password) {
        this.url = "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
                .formatted(host, port, dbName);
        this.user     = user;
        this.password = password;
    }

    /**
     * Must be called once at startup before any repository is used.
     */
    public static void initialize(String host, int port, String dbName,
                                  String user, String password) {
        instance = new DatabaseManager(host, port, dbName, user, password);
        instance.createSchema();
        log.info("Database schema verified/created.");
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DatabaseManager.initialize() has not been called.");
        }
        return instance;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void createSchema() {
        try (Connection conn = getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute(DDL_USERS);
            if (!columnExists(conn, "users", "is_admin")) {
                stmt.execute(DDL_USERS_ADD_ADMIN_COL);
            }
            stmt.execute(DDL_USERS_ADD_BANNED_COL);
            stmt.execute(DDL_USERS_ADD_DEVELOPER_COL);
            stmt.execute(DDL_SESSIONS);
            stmt.execute(DDL_SESSIONS_ADD_IP_COL);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise database schema.", e);
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
