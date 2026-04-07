package com.game.client.ui;

import javafx.scene.paint.Color;

public enum BoardTile {
    AIR      ("Air",      Color.TRANSPARENT,   Color.web("#1a1a2e")),
    PLATFORM ("Platform", Color.web("#4466aa"), Color.web("#2244aa")),
    WALL     ("Wall",     Color.web("#336633"), Color.web("#224422")),
    LADDER   ("Ladder",   Color.web("#8B5E3C"), Color.web("#5a3a1a")),
    SPIKE    ("Spike",    Color.web("#cc2222"), Color.web("#881111")),
    SPAWN    ("Spawn",    Color.web("#22cc88"), Color.web("#117744")),
    WATER    ("Water",    Color.web("#2266cc"), Color.web("#113388"));

    public final String label;
    public final Color  fill;
    public final Color  border;

    BoardTile(String label, Color fill, Color border) {
        this.label  = label;
        this.fill   = fill;
        this.border = border;
    }
}
