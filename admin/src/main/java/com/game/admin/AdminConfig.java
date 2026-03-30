package com.game.admin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads admin.properties from the classpath. */
public class AdminConfig {

    private final Properties props = new Properties();

    public AdminConfig() {
        try (InputStream in = getClass().getResourceAsStream("/admin.properties")) {
            if (in != null) props.load(in);
            else System.err.println("[WARN] admin.properties not found — using defaults.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load admin.properties", e);
        }
    }

    public String getServerHost() { return prop("server.host", "localhost"); }
    public int    getServerPort() { return Integer.parseInt(prop("server.port", "9876")); }
    public int    getClientPort() { return Integer.parseInt(prop("client.port", "0")); }

    private String prop(String key, String def) { return props.getProperty(key, def); }
}
