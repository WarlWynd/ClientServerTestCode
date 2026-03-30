package com.game.devconsole;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads devconsole.properties from the classpath. */
public class DevConsoleConfig {

    private final Properties props = new Properties();

    public DevConsoleConfig() {
        try (InputStream in = getClass().getResourceAsStream("/devconsole.properties")) {
            if (in != null) props.load(in);
            else System.err.println("[WARN] devconsole.properties not found — using defaults.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load devconsole.properties", e);
        }
    }

    public String getServerHost()    { return prop("server.host",      "localhost"); }
    public int    getUdpPort()       { return Integer.parseInt(prop("server.udp.port",  "9876")); }
    public int    getHttpPort()      { return Integer.parseInt(prop("server.http.port", "9877")); }
    public int    getClientPort()    { return Integer.parseInt(prop("client.port",       "0")); }

    private String prop(String key, String def) { return props.getProperty(key, def); }
}
