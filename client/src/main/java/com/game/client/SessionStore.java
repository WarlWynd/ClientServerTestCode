package com.game.client;

/**
 * Singleton that holds the current authenticated session token for the duration
 * of the process.  Cleared on logout.
 *
 * All access is via static methods so any class can read the token without
 * passing a reference around.
 */
public final class SessionStore {

    private static volatile String  token;
    private static volatile String  username;
    private static volatile boolean admin;

    private SessionStore() {}

    public static void set(String sessionToken, String user, boolean isAdmin) {
        token    = sessionToken;
        username = user;
        admin    = isAdmin;
    }

    public static void clear() {
        token    = null;
        username = null;
        admin    = false;
    }

    public static String  getToken()    { return token; }
    public static String  getUsername() { return username; }
    public static boolean isAdmin()     { return admin; }
    public static boolean isLoggedIn()  { return token != null && !token.isBlank(); }
}
