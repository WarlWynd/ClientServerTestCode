package com.game.bots;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads {@code bots.properties} from the classpath. */
public class BotConfig {

    private final Properties props = new Properties();

    public BotConfig() {
        try (InputStream in = getClass().getResourceAsStream("/bots.properties")) {
            if (in != null) props.load(in);
            else System.err.println("[WARN] bots.properties not found — using defaults.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load bots.properties", e);
        }
    }

    public String getServerHost()  { return prop("server.host",    "localhost"); }
    public int    getServerPort()  { return Integer.parseInt(prop("server.port",   "9876")); }
    public int    getBotCount()    { return Integer.parseInt(prop("bot.count",     "8")); }
    public String getBotPrefix()   { return prop("bot.prefix",     "bot"); }
    public String getBotPassword() { return prop("bot.password",   "bottest123"); }

    private String prop(String key, String def) { return props.getProperty(key, def); }
}
