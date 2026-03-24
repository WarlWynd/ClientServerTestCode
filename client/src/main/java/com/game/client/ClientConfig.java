package com.game.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads {@code client.properties} from the classpath.
 */
public class ClientConfig {

    private final Properties props = new Properties();

    public ClientConfig() {
        try (InputStream in = getClass().getResourceAsStream("/client.properties")) {
            if (in != null) {
                props.load(in);
            } else {
                System.err.println("[WARN] client.properties not found — using defaults.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load client.properties", e);
        }
    }

    public String getServerHost() { return prop("server.host", "localhost"); }
    public int    getServerPort() { return Integer.parseInt(prop("server.port", "9876")); }
    public int    getClientPort() { return Integer.parseInt(prop("client.port", "0")); }

    private String prop(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
}
