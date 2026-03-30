package com.game.admin;

/** Process-scoped singleton holding the admin session after login. */
public final class AdminSession {

    private static volatile String token;
    private static volatile String username;

    private AdminSession() {}

    public static void   set(String t, String u) { token = t; username = u; }
    public static void   clear()                 { token = null; username = null; }
    public static String getToken()              { return token; }
    public static String getUsername()           { return username; }
    public static boolean isLoggedIn()           { return token != null && !token.isBlank(); }
}
