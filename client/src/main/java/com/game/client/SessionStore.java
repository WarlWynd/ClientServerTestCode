package com.game.client;

/**
 * Singleton that holds the current authenticated session for the duration
 * of the process. Cleared on logout.
 */
public final class SessionStore {

    private static volatile String  token;
    private static volatile String  username;
    private static volatile boolean audioAdmin;

    private SessionStore() {}

    public static void set(String sessionToken, String user, boolean isAudioAdmin) {
        token      = sessionToken;
        username   = user;
        audioAdmin = isAudioAdmin;
    }

    public static void clear() {
        token      = null;
        username   = null;
        audioAdmin = false;
    }

    public static String  getToken()      { return token; }
    public static String  getUsername()   { return username; }
    public static boolean isAudioAdmin()  { return audioAdmin; }
    public static boolean isLoggedIn()    { return token != null && !token.isBlank(); }
}
