package com.game.client;

/**
 * Singleton that holds the current authenticated session for the duration
 * of the process. Cleared on logout.
 */
public final class SessionStore {

    private static volatile String  token;
    private static volatile String  username;
    private static volatile String  characterName;
    private static volatile boolean admin;

    private SessionStore() {}

    public static void set(String sessionToken, String user, boolean isAdmin) {
        token    = sessionToken;
        username = user;
        admin    = isAdmin;
    }

    public static void clear() {
        token         = null;
        username      = null;
        characterName = null;
        admin         = false;
    }

    public static String  getToken()         { return token; }
    public static String  getUsername()      { return username; }
    public static String  getCharacterName() { return characterName; }
    public static boolean isAdmin()          { return admin; }
    public static boolean isLoggedIn()       { return token != null && !token.isBlank(); }
    public static String  getAssetUrl()      { return "http://" + AppSettings.getServerHost() + ":9877"; }

    public static void setCharacterName(String name) { characterName = name; }
}
