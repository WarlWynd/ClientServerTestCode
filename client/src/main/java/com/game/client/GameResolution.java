package com.game.client;

/**
 * Preset canvas resolutions available in Settings.
 * Width × Height refer to the game world canvas (not including the sidebar).
 */
public enum GameResolution {

    LOW    ("Low",      480, 320),
    MEDIUM ("Medium",   640, 480),
    STANDARD("Standard", 800, 600),
    HIGH   ("High",    1024, 768),
    HD     ("HD",      1280, 720);

    public final String label;
    public final int    width;
    public final int    height;

    GameResolution(String label, int width, int height) {
        this.label  = label;
        this.width  = width;
        this.height = height;
    }

    public String displayLabel() {
        return label + "  (" + width + "×" + height + ")";
    }

    public static GameResolution fromString(String s) {
        for (GameResolution r : values()) {
            if (r.name().equalsIgnoreCase(s)) return r;
        }
        return STANDARD;
    }
}
