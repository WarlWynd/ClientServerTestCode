package com.game.devconsole;

/** Process-scoped singleton holding the developer session after login. */
public final class DevConsoleSession {

    private static volatile String token;
    private static volatile String username;

    private DevConsoleSession() {}

    public static void   set(String t, String u) { token = t; username = u; }
    public static void   clear()                 { token = null; username = null; }
    public static String getToken()              { return token; }
    public static String getUsername()           { return username; }
    public static boolean isLoggedIn()           { return token != null && !token.isBlank(); }
}
