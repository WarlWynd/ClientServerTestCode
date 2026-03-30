package com.game.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Loads {@code client.properties} from the classpath.
 */
public class ClientConfig {

    private final Properties props = new Properties();

    public ClientConfig() {
        // 1. Bundled defaults
        try (InputStream in = getClass().getResourceAsStream("/client.properties")) {
            if (in != null) props.load(in);
            else System.err.println("[WARN] client.properties not found — using defaults.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load client.properties", e);
        }
        // 2. User-saved overrides (written by SettingsPanel)
        if (Files.exists(AppSettings.USER_FILE)) {
            try (InputStream in = Files.newInputStream(AppSettings.USER_FILE)) {
                props.load(in);
            } catch (IOException ignored) {}
        }
    }

    public String getServerHost() { return prop("server.host", "localhost"); }
    public int    getServerPort() { return Integer.parseInt(prop("server.port", "9876")); }
    public int    getClientPort() { return Integer.parseInt(prop("client.port", "0")); }
    public String getVersion()    { return prop("app.version", "0.0.0"); }

    private String prop(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
}
