package com.game.client;

/**
 * Runtime detection of whether the client is running on a mobile device
 * (Android or iOS via Gluon Mobile) or on the desktop.
 *
 * Gluon's substrate sets {@code os.name} to {@code "android"} / {@code "ios"}
 * and {@code javafx.platform} to {@code "android"} / {@code "ios"}.
 */
public final class MobilePlatform {

    private static final boolean MOBILE;

    static {
        String os       = System.getProperty("os.name",        "").toLowerCase();
        String platform = System.getProperty("javafx.platform","").toLowerCase();
        MOBILE = os.contains("android") || os.contains("ios")
              || platform.equals("android") || platform.equals("ios");
    }

    public static boolean isMobile()  { return MOBILE; }
    public static boolean isDesktop() { return !MOBILE; }

    private MobilePlatform() {}
}
