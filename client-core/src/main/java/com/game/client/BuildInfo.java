package com.game.client;

import java.io.InputStream;
import java.util.Properties;

/** Build-time metadata baked in via processResources. */
public final class BuildInfo {

    public static final String COMMIT;
    public static final String BUILD_TIME;

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        COMMIT     = p.getProperty("git.commit", "unknown");
        BUILD_TIME = p.getProperty("build.time",  "unknown");
    }

    private BuildInfo() {}
}
