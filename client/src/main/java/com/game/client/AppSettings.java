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
    private static volatile String         serverHost              = "localhost";
    private static volatile int            serverPort              = 9876;
    private static volatile String         externalServerHost      = "localhost";
    private static volatile int            externalServerPort      = 9876;
    private static volatile boolean        allowExternalAdmin      = false;
    private static volatile boolean        allowExternalDev        = false;
    private static volatile int        masterVolume    = 100;  // 0–100
    private static volatile int        soundVolume     = 100;  // 0–100
    private static volatile int        musicVolume     = 100;  // 0–100
    private static volatile boolean   keepScreenAwake = true;
    private static volatile double    hudOpacity      = 1.0;
    private static volatile String    clientVersion    = GameVersion.VERSION;
    private static volatile String    lastUsername          = "";
    private static volatile boolean   rememberUsername      = false;
    private static volatile boolean   allowRememberPassword = false;
    private static volatile boolean   rememberPassword      = false;
    private static volatile String    lastPassword          = "";
    private static volatile String    assetUrl         = "http://localhost:9877";
    private static volatile String    uploadKey        = "";
    private static volatile String    tabSide          = "LEFT";
    private static volatile boolean   tabIconOnly      = false;
    private static volatile String    theme            = "DARK";
    private static volatile float     gravity          = 0.5f;
    private static volatile float     jumpStrength     = 8.0f;
    private static volatile float     runSpeed         = 6.0f;
    private static volatile String    keyJump          = "W";
    private static volatile String    keySprint        = "SHIFT";
    private static volatile String    keyFire          = "F";
    private static volatile String    keyClimbUp       = "W";
    private static volatile String    keyClimbDown     = "S";
    private static volatile String    keyKick          = "K";
    private static volatile String    keyPunch         = "J";
    private static volatile String    keyAttack        = "E";
    private static volatile boolean   showTestNpc      = true;
    private static volatile float     testNpcX         = 500f;
    private static volatile float     testNpcY         = 14f;
    private static volatile int       rebootDelaySecs  = 60;
    private static volatile String    rebootMessage    = "";
    private static volatile boolean   combatShowHealthBar  = true;
    private static volatile boolean   combatShowHits       = true;
    private static volatile boolean   combatShowDamage     = true;
    private static volatile boolean   combatShowStance     = true;
    private static volatile boolean   combatShowVerboseHits = true;

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
        serverHost          = merged.getProperty("server.host",          serverHost);
        serverPort          = intOf(merged,  "server.port",          serverPort);
        externalServerHost  = merged.getProperty("server.external.host", externalServerHost);
        externalServerPort  = intOf(merged,  "server.external.port", externalServerPort);
        allowExternalAdmin  = boolOf(merged, "admin.allowExternalAdmin", allowExternalAdmin);
        allowExternalDev    = boolOf(merged, "admin.allowExternalDev",   allowExternalDev);
        masterVolume    = intOf(merged,  "master.volume", masterVolume);
        soundVolume     = intOf(merged,  "sound.volume",  soundVolume);
        musicVolume     = intOf(merged,  "music.volume",  musicVolume);
        keepScreenAwake = boolOf(merged, "display.keepScreenAwake", keepScreenAwake);
        hudOpacity      = doubleOf(merged, "display.hudOpacity", hudOpacity);
        lastUsername          = merged.getProperty("client.lastUsername",          lastUsername);
        rememberUsername      = boolOf(merged, "client.rememberUsername",          rememberUsername);
        allowRememberPassword = boolOf(merged, "client.allowRememberPassword",     allowRememberPassword);
        rememberPassword      = boolOf(merged, "client.rememberPassword",          rememberPassword);
        lastPassword          = merged.getProperty("client.lastPassword",          lastPassword);
        assetUrl          = merged.getProperty("asset.url",       assetUrl);
        uploadKey         = merged.getProperty("upload.key",     uploadKey);
        tabSide           = merged.getProperty("display.tabSide",     tabSide);
        tabIconOnly       = boolOf(merged, "display.tabIconOnly",   tabIconOnly);
        theme             = merged.getProperty("display.theme",      theme);
        gravity           = floatOf(merged, "game.gravity",       gravity);
        jumpStrength      = floatOf(merged, "game.jumpStrength",  jumpStrength);
        runSpeed          = floatOf(merged, "game.runSpeed",      runSpeed);
        keyJump           = merged.getProperty("key.jump",        keyJump);
        keySprint         = merged.getProperty("key.sprint",      keySprint);
        keyFire           = merged.getProperty("key.fire",        keyFire);
        keyClimbUp        = merged.getProperty("key.climbUp",     keyClimbUp);
        keyClimbDown      = merged.getProperty("key.climbDown",   keyClimbDown);
        keyKick           = merged.getProperty("key.kick",        keyKick);
        keyPunch          = merged.getProperty("key.punch",       keyPunch);
        keyAttack         = merged.getProperty("key.attack",      keyAttack);
        showTestNpc       = boolOf(merged, "gameplay.showTestNpc", showTestNpc);
        testNpcX          = floatOf(merged, "gameplay.testNpcX",   testNpcX);
        testNpcY          = floatOf(merged, "gameplay.testNpcY",   testNpcY);
        rebootDelaySecs   = intOf(merged,   "server.rebootDelaySecs", rebootDelaySecs);
        rebootMessage     = merged.getProperty("server.rebootMessage", rebootMessage);
        combatShowHealthBar  = boolOf(merged, "combat.showHealthBar",  combatShowHealthBar);
        combatShowHits       = boolOf(merged, "combat.showHits",       combatShowHits);
        combatShowDamage     = boolOf(merged, "combat.showDamage",     combatShowDamage);
        combatShowStance     = boolOf(merged, "combat.showStance",     combatShowStance);
        combatShowVerboseHits = boolOf(merged, "combat.showVerboseHits", combatShowVerboseHits);
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
        p.setProperty("server.external.host",      externalServerHost);
        p.setProperty("server.external.port",      String.valueOf(externalServerPort));
        p.setProperty("admin.allowExternalAdmin",  String.valueOf(allowExternalAdmin));
        p.setProperty("admin.allowExternalDev",    String.valueOf(allowExternalDev));
        p.setProperty("master.volume",             String.valueOf(masterVolume));
        p.setProperty("sound.volume",              String.valueOf(soundVolume));
        p.setProperty("music.volume",              String.valueOf(musicVolume));
        p.setProperty("display.resolution",        resolution.name());
        p.setProperty("display.keepScreenAwake",   String.valueOf(keepScreenAwake));
        p.setProperty("display.hudOpacity",        String.valueOf(hudOpacity));
        p.setProperty("client.version",            GameVersion.VERSION);
        p.setProperty("client.lastUsername",          lastUsername);
        p.setProperty("client.rememberUsername",      String.valueOf(rememberUsername));
        p.setProperty("client.allowRememberPassword", String.valueOf(allowRememberPassword));
        p.setProperty("client.rememberPassword",      String.valueOf(rememberPassword));
        p.setProperty("client.lastPassword",          lastPassword);
        p.setProperty("display.tabSide",           tabSide);
        p.setProperty("display.tabIconOnly",       String.valueOf(tabIconOnly));
        p.setProperty("display.theme",             theme);
        p.setProperty("game.gravity",              String.valueOf(gravity));
        p.setProperty("game.jumpStrength",         String.valueOf(jumpStrength));
        p.setProperty("game.runSpeed",             String.valueOf(runSpeed));
        p.setProperty("key.jump",                  keyJump);
        p.setProperty("key.sprint",               keySprint);
        p.setProperty("key.fire",                  keyFire);
        p.setProperty("key.climbUp",               keyClimbUp);
        p.setProperty("key.climbDown",             keyClimbDown);
        p.setProperty("key.kick",                  keyKick);
        p.setProperty("key.punch",                 keyPunch);
        p.setProperty("key.attack",                keyAttack);
        p.setProperty("gameplay.showTestNpc",      String.valueOf(showTestNpc));
        p.setProperty("gameplay.testNpcX",         String.valueOf(testNpcX));
        p.setProperty("gameplay.testNpcY",         String.valueOf(testNpcY));
        p.setProperty("server.rebootDelaySecs",    String.valueOf(rebootDelaySecs));
        p.setProperty("server.rebootMessage",      rebootMessage);
        p.setProperty("combat.showHealthBar",   String.valueOf(combatShowHealthBar));
        p.setProperty("combat.showHits",        String.valueOf(combatShowHits));
        p.setProperty("combat.showDamage",      String.valueOf(combatShowDamage));
        p.setProperty("combat.showStance",      String.valueOf(combatShowStance));
        p.setProperty("combat.showVerboseHits", String.valueOf(combatShowVerboseHits));
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
    public static boolean   isKeepScreenAwake()          { return keepScreenAwake; }
    public static double    getHudOpacity()              { return hudOpacity; }
    public static String    getClientVersion()           { return clientVersion; }
    public static String    getLastUsername()                  { return lastUsername; }
    public static boolean   isRememberUsername()               { return rememberUsername; }
    public static boolean   isAllowRememberPassword()          { return allowRememberPassword; }
    public static void      setAllowRememberPassword(boolean v){ allowRememberPassword = v; }
    public static boolean   isRememberPassword()               { return rememberPassword; }
    public static void      setRememberPassword(boolean v)     { rememberPassword = v; }
    public static String    getLastPassword()                  { return lastPassword; }
    public static void      setLastPassword(String v)          { lastPassword = v; }
    public static String    getAssetUrl()               { return assetUrl; }
    public static String    getUploadKey()              { return uploadKey; }
    public static String    getTabSide()               { return tabSide; }
    public static void      setTabSide(String v)       { tabSide = v; }
    public static boolean   isTabIconOnly()            { return tabIconOnly; }
    public static void      setTabIconOnly(boolean v)  { tabIconOnly = v; }
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
    public static String    getKeyClimbUp()            { return keyClimbUp; }
    public static void      setKeyClimbUp(String v)    { keyClimbUp = v; }
    public static String    getKeyClimbDown()          { return keyClimbDown; }
    public static void      setKeyClimbDown(String v)  { keyClimbDown = v; }
    public static String    getKeyKick()               { return keyKick; }
    public static void      setKeyKick(String v)       { keyKick = v; }
    public static String    getKeyPunch()              { return keyPunch; }
    public static void      setKeyPunch(String v)      { keyPunch = v; }
    public static String    getKeyAttack()             { return keyAttack; }
    public static void      setKeyAttack(String v)     { keyAttack = v; }
    public static boolean   isShowTestNpc()            { return showTestNpc; }
    public static void      setShowTestNpc(boolean v)  { showTestNpc = v; }
    public static float     getTestNpcX()              { return testNpcX; }
    public static void      setTestNpcX(float v)       { testNpcX = v; }
    public static float     getTestNpcY()              { return testNpcY; }
    public static void      setTestNpcY(float v)       { testNpcY = v; }
    public static boolean   isCombatShowHealthBar()             { return combatShowHealthBar; }
    public static void      setCombatShowHealthBar(boolean v)   { combatShowHealthBar = v; }
    public static boolean   isCombatShowHits()                  { return combatShowHits; }
    public static void      setCombatShowHits(boolean v)        { combatShowHits = v; }
    public static boolean   isCombatShowDamage()                { return combatShowDamage; }
    public static void      setCombatShowDamage(boolean v)      { combatShowDamage = v; }
    public static boolean   isCombatShowStance()                { return combatShowStance; }
    public static void      setCombatShowStance(boolean v)      { combatShowStance = v; }
    public static boolean   isCombatShowVerboseHits()               { return combatShowVerboseHits; }
    public static void      setCombatShowVerboseHits(boolean v)     { combatShowVerboseHits = v; }
    public static int       getRebootDelaySecs()       { return rebootDelaySecs; }
    public static void      setRebootDelaySecs(int v)  { rebootDelaySecs = v; }
    public static String    getRebootMessage()         { return rebootMessage; }
    public static void      setRebootMessage(String v) { rebootMessage = v == null ? "" : v; }

    public static void setResolution(GameResolution v)    { resolution      = v; }
    public static void      setServerHost(String v)            { serverHost           = v; }
    public static void      setServerPort(int v)               { serverPort           = v; }
    public static String    getExternalServerHost()            { return externalServerHost; }
    public static void      setExternalServerHost(String v)    { externalServerHost   = v; }
    public static int       getExternalServerPort()            { return externalServerPort; }
    public static void      setExternalServerPort(int v)       { externalServerPort   = v; }
    public static boolean   isAllowExternalAdmin()             { return allowExternalAdmin; }
    public static void      setAllowExternalAdmin(boolean v)   { allowExternalAdmin   = v; }
    public static boolean   isAllowExternalDev()               { return allowExternalDev; }
    public static void      setAllowExternalDev(boolean v)     { allowExternalDev     = v; }
    public static int  getMasterVolume()                 { return masterVolume; }
    public static void setMasterVolume(int v)            { masterVolume = Math.max(0, Math.min(100, v)); }
    public static int  getSoundVolume()                  { return soundVolume; }
    public static void setSoundVolume(int v)             { soundVolume = Math.max(0, Math.min(100, v)); }
    public static int  getMusicVolume()                  { return musicVolume; }
    public static void setMusicVolume(int v)             { musicVolume = Math.max(0, Math.min(100, v)); }
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
