package com.game.client.ui;

import javafx.scene.paint.Color;

public enum BoardTile {
    AIR      ("Air",      false, false, Color.TRANSPARENT,   Color.web("#1a1a2e")),
    PLATFORM ("Platform", true,  false, Color.web("#888888"), Color.web("#555555")),
    WALL     ("Wall",     true,  false, Color.web("#336633"), Color.web("#224422")),
    LADDER   ("Ladder",   false, false, Color.web("#8B5E3C"), Color.web("#5a3a1a")),
    SPIKE    ("Spike",    true,  false, Color.web("#cc2222"), Color.web("#881111")),
    SPAWN    ("Spawn",    false, false, Color.web("#22cc88"), Color.web("#117744")),
    WATER    ("Water",    false, true,  Color.web("#2266cc"), Color.web("#113388"));

    public final String  label;
    public final boolean solid;
    public final boolean liquid;
    public final Color   fill;
    public final Color   border;

    BoardTile(String label, boolean solid, boolean liquid, Color fill, Color border) {
        this.label  = label;
        this.solid  = solid;
        this.liquid = liquid;
        this.fill   = fill;
        this.border = border;
    }
}
