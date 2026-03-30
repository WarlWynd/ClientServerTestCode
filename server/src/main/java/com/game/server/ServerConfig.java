package com.game.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads {@code server.properties} from the classpath.
 * All values have sensible defaults so the server starts out-of-the-box
 * for local development.
 */
public class ServerConfig {

    private final Properties props = new Properties();

    public ServerConfig() {
        try (InputStream in = getClass().getResourceAsStream("/server.properties")) {
            if (in != null) {
                props.load(in);
            } else {
                System.err.println("[WARN] server.properties not found — using defaults.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load server.properties", e);
        }
    }

    public String getVersion()           { return prop("app.version",        "0.0.0"); }
    public int    getPort()              { return Integer.parseInt(prop("server.port",      "9876")); }
    public int    getHttpPort()          { return Integer.parseInt(prop("server.http.port", "9877")); }
    public String getAssetsDir()         { return prop("server.assets.dir",  "assets"); }
    public String getMinClientVersion()  { return prop("app.min.version",    "1.0.0"); }
    public String getDbHost()            { return prop("db.host",             "localhost"); }
    public int    getDbPort()        { return Integer.parseInt(prop("db.port",     "3306")); }
    public String getDbName()        { return prop("db.name",       "game_db"); }
    public String getDbUser()        { return prop("db.user",       "root"); }
    public String getDbPassword()    { return prop("db.password",   ""); }
    public String getAdminUsername() { return prop("admin.username", "admin"); }
    public String getAdminPassword() { return prop("admin.password", "admin1234"); }

    private String prop(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
}
