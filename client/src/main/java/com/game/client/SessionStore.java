package com.game.client;

import com.game.client.AppSettings;

/**
 * Singleton that holds the current authenticated session for the duration
 * of the process. Cleared on logout.
 */
public final class SessionStore {

    private static volatile String  token;
    private static volatile String  username;
    private static volatile boolean admin;
    private static volatile boolean audioDev;

    private SessionStore() {}

    public static void set(String sessionToken, String user, boolean isAdmin, boolean isAudioDev) {
        token      = sessionToken;
        username   = user;
        admin      = isAdmin;
        audioDev   = isAudioDev;
    }

    public static void clear() {
        token      = null;
        username   = null;
        admin      = false;
        audioDev = false;
    }

    public static String  getToken()      { return token; }
    public static String  getUsername()   { return username; }
    public static boolean isAudioDev()    { return audioDev; }
    public static boolean isAdmin()       { return admin; }
    public static boolean isLoggedIn()    { return token != null && !token.isBlank(); }
    public static String  getAssetUrl()   { return "http://" + AppSettings.getServerHost() + ":9877"; }
}
