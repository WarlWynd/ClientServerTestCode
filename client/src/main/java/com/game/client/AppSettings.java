package com.game.client;

import com.game.shared.GameVersion;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Runtime application settings — loaded once at startup, saveable at any time.
 *
 * Defaults come from the bundled {@code client.properties}.
 * User overrides are persisted to {@code ~/.game/settings.properties} and
 * automatically picked up on the next launch.
 *
 * Connection changes (host / port) require a restart to take effect.
 */
public final class AppSettings {

    public static final Path USER_FILE = Paths.get(
            System.getProperty("user.home"), ".game", "settings.properties");

    // ── Fields ────────────────────────────────────────────────────────────────
    private static volatile String    serverHost      = "localhost";
    private static volatile int       serverPort      = 9876;
    private static volatile SoundMode soundMode       = SoundMode.HIGH;
    private static volatile boolean   keepScreenAwake = true;
    private static volatile double    hudOpacity      = 1.0;
    private static volatile String    clientVersion    = GameVersion.VERSION;
    private static volatile String    lastUsername     = "";
    private static volatile boolean   rememberUsername = false;

    static { load(); }

    private AppSettings() {}

    // ── Load ──────────────────────────────────────────────────────────────────

    private static void load() {
        Properties merged = new Properties();

        // 1. Bundled defaults
        try (InputStream in = AppSettings.class.getResourceAsStream("/client.properties")) {
            if (in != null) merged.load(in);
        } catch (Exception ignored) {}

        // 2. User overrides (if present)
        if (Files.exists(USER_FILE)) {
            try (InputStream in = Files.newInputStream(USER_FILE)) {
                merged.load(in);
            } catch (Exception ignored) {}
        }

        serverHost      = merged.getProperty("server.host", serverHost);
        serverPort      = intOf(merged, "server.port", serverPort);
        soundMode       = SoundMode.fromString(merged.getProperty("sound.mode",
                          merged.getProperty("sound.enabled", soundMode.name())));
        keepScreenAwake = boolOf(merged, "display.keepScreenAwake", keepScreenAwake);
        hudOpacity      = doubleOf(merged, "display.hudOpacity", hudOpacity);
        lastUsername      = merged.getProperty("client.lastUsername", lastUsername);
        rememberUsername  = boolOf(merged, "client.rememberUsername", rememberUsername);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    /**
     * Persists current settings to {@code ~/.game/settings.properties}.
     * @return true on success
     */
    public static boolean save() {
        Properties p = new Properties();
        p.setProperty("server.host",               serverHost);
        p.setProperty("server.port",               String.valueOf(serverPort));
        p.setProperty("sound.mode",                soundMode.name());
        p.setProperty("display.keepScreenAwake",   String.valueOf(keepScreenAwake));
        p.setProperty("display.hudOpacity",        String.valueOf(hudOpacity));
        p.setProperty("client.version",            GameVersion.VERSION);
        p.setProperty("client.lastUsername",       lastUsername);
        p.setProperty("client.rememberUsername",   String.valueOf(rememberUsername));
        try {
            Files.createDirectories(USER_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(USER_FILE)) {
                p.store(out, "Game Client — User Settings");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public static String    getServerHost()              { return serverHost; }
    public static int       getServerPort()              { return serverPort; }
    public static SoundMode getSoundMode()               { return soundMode; }
    public static boolean   isKeepScreenAwake()          { return keepScreenAwake; }
    public static double    getHudOpacity()              { return hudOpacity; }
    public static String    getClientVersion()           { return clientVersion; }
    public static String    getLastUsername()            { return lastUsername; }
    public static boolean   isRememberUsername()         { return rememberUsername; }

    public static void setServerHost(String v)           { serverHost      = v; }
    public static void setServerPort(int v)              { serverPort      = v; }
    public static void setSoundMode(SoundMode v)         { soundMode       = v; }
    public static void setKeepScreenAwake(boolean v)     { keepScreenAwake = v; }
    public static void setHudOpacity(double v)           { hudOpacity      = v; }
    public static void setLastUsername(String v)         { lastUsername    = v; }
    public static void setRememberUsername(boolean v)    { rememberUsername = v; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int intOf(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean boolOf(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static double doubleOf(Properties p, String key, double def) {
        try { return Double.parseDouble(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}
