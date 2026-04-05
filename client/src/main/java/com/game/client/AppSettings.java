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
    private static volatile String         programName     = "Adventure Friends";
    private static volatile String         corpName        = "Wynd Farm";
    private static volatile GameResolution resolution      = GameResolution.STANDARD;
    private static volatile String         serverHost      = "localhost";
    private static volatile int       serverPort      = 9876;
    private static volatile SoundMode soundMode       = SoundMode.HIGH;
    private static volatile boolean   keepScreenAwake = true;
    private static volatile double    hudOpacity      = 1.0;
    private static volatile String    clientVersion    = GameVersion.VERSION;
    private static volatile String    lastUsername     = "";
    private static volatile boolean   rememberUsername = false;
    private static volatile String    assetUrl         = "http://localhost:9877";
    private static volatile String    uploadKey        = "";
    private static volatile String    tabSide          = "LEFT";
    private static volatile String    theme            = "DARK";
    private static volatile float     gravity          = 0.5f;
    private static volatile float     jumpStrength     = 8.0f;
    private static volatile float     runSpeed         = 6.0f;
    private static volatile String    keyJump          = "W";
    private static volatile String    keySprint        = "SHIFT";
    private static volatile String    keyFire          = "F";

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

        programName     = merged.getProperty("ProgramName", programName);
        corpName        = merged.getProperty("CorpName",    corpName);
        resolution      = GameResolution.fromString(merged.getProperty("display.resolution", resolution.name()));
        serverHost      = merged.getProperty("server.host", serverHost);
        serverPort      = intOf(merged, "server.port", serverPort);
        soundMode       = SoundMode.fromString(merged.getProperty("sound.mode",
                          merged.getProperty("sound.enabled", soundMode.name())));
        keepScreenAwake = boolOf(merged, "display.keepScreenAwake", keepScreenAwake);
        hudOpacity      = doubleOf(merged, "display.hudOpacity", hudOpacity);
        lastUsername      = merged.getProperty("client.lastUsername", lastUsername);
        rememberUsername  = boolOf(merged, "client.rememberUsername", rememberUsername);
        assetUrl          = merged.getProperty("asset.url",       assetUrl);
        uploadKey         = merged.getProperty("upload.key",     uploadKey);
        tabSide           = merged.getProperty("display.tabSide", tabSide);
        theme             = merged.getProperty("display.theme",   theme);
        gravity           = floatOf(merged, "game.gravity",       gravity);
        jumpStrength      = floatOf(merged, "game.jumpStrength",  jumpStrength);
        runSpeed          = floatOf(merged, "game.runSpeed",      runSpeed);
        keyJump           = merged.getProperty("key.jump",        keyJump);
        keySprint         = merged.getProperty("key.sprint",      keySprint);
        keyFire           = merged.getProperty("key.fire",        keyFire);
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
        p.setProperty("display.resolution",        resolution.name());
        p.setProperty("display.keepScreenAwake",   String.valueOf(keepScreenAwake));
        p.setProperty("display.hudOpacity",        String.valueOf(hudOpacity));
        p.setProperty("client.version",            GameVersion.VERSION);
        p.setProperty("client.lastUsername",       lastUsername);
        p.setProperty("client.rememberUsername",   String.valueOf(rememberUsername));
        p.setProperty("display.tabSide",           tabSide);
        p.setProperty("display.theme",             theme);
        p.setProperty("game.gravity",              String.valueOf(gravity));
        p.setProperty("game.jumpStrength",         String.valueOf(jumpStrength));
        p.setProperty("game.runSpeed",             String.valueOf(runSpeed));
        p.setProperty("key.jump",                  keyJump);
        p.setProperty("key.sprint",               keySprint);
        p.setProperty("key.fire",                  keyFire);
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

    public static String         getProgramName()  { return programName; }
    public static String         getCorpName()     { return corpName; }
    public static GameResolution getResolution()   { return resolution; }
    public static String         getServerHost()   { return serverHost; }
    public static int       getServerPort()              { return serverPort; }
    public static SoundMode getSoundMode()               { return soundMode; }
    public static boolean   isKeepScreenAwake()          { return keepScreenAwake; }
    public static double    getHudOpacity()              { return hudOpacity; }
    public static String    getClientVersion()           { return clientVersion; }
    public static String    getLastUsername()            { return lastUsername; }
    public static boolean   isRememberUsername()         { return rememberUsername; }
    public static String    getAssetUrl()               { return assetUrl; }
    public static String    getUploadKey()              { return uploadKey; }
    public static String    getTabSide()               { return tabSide; }
    public static void      setTabSide(String v)       { tabSide = v; }
    public static String    getTheme()                 { return theme; }
    public static void      setTheme(String v)         { theme = v; }
    public static float     getGravity()               { return gravity; }
    public static void      setGravity(float v)        { gravity = v; }
    public static float     getJumpStrength()          { return jumpStrength; }
    public static void      setJumpStrength(float v)   { jumpStrength = v; }
    public static float     getRunSpeed()              { return runSpeed; }
    public static void      setRunSpeed(float v)       { runSpeed = v; }
    public static String    getKeyJump()               { return keyJump; }
    public static void      setKeyJump(String v)       { keyJump = v; }
    public static String    getKeySprint()             { return keySprint; }
    public static void      setKeySprint(String v)     { keySprint = v; }
    public static String    getKeyFire()               { return keyFire; }
    public static void      setKeyFire(String v)       { keyFire = v; }

    public static void setResolution(GameResolution v)    { resolution      = v; }
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

    private static float floatOf(Properties p, String key, float def) {
        try { return Float.parseFloat(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}
