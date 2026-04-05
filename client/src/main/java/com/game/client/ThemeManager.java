package com.game.client;

import javafx.scene.Scene;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the application theme (Dark / Light).
 *
 * Call {@link #apply(Scene)} whenever a new Scene is created.
 * Call {@link #setTheme(Theme)} to switch themes live — all tracked scenes update instantly.
 *
 * The current theme is persisted through {@link AppSettings} (display.theme).
 */
public final class ThemeManager {

    public enum Theme { DARK, LIGHT }

    private static final String BASE_CSS  = "/styles/base.css";
    private static final String DARK_CSS  = "/styles/dark.css";
    private static final String LIGHT_CSS = "/styles/light.css";

    private static final List<Scene> managedScenes = new ArrayList<>();

    private ThemeManager() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static Theme getTheme() {
        return "LIGHT".equalsIgnoreCase(AppSettings.getTheme()) ? Theme.LIGHT : Theme.DARK;
    }

    /**
     * Apply the current theme to a scene and track it so future theme changes
     * are applied automatically.
     */
    public static void apply(Scene scene) {
        // Prune scenes whose windows have been closed
        managedScenes.removeIf(s -> s.getWindow() == null || !s.getWindow().isShowing());
        managedScenes.add(scene);
        applyTo(scene);
    }

    /** Switch theme, update all tracked scenes, and persist to AppSettings. */
    public static void setTheme(Theme theme) {
        AppSettings.setTheme(theme.name());
        managedScenes.removeIf(s -> s.getWindow() == null || !s.getWindow().isShowing());
        managedScenes.forEach(ThemeManager::applyTo);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void applyTo(Scene scene) {
        scene.getStylesheets().clear();
        addStylesheet(scene, BASE_CSS);
        addStylesheet(scene, getTheme() == Theme.LIGHT ? LIGHT_CSS : DARK_CSS);
    }

    private static void addStylesheet(Scene scene, String resource) {
        var url = ThemeManager.class.getResource(resource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
