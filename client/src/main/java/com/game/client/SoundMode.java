package com.game.client;

/** Controls how loudly (or whether) sound effects are played. */
public enum SoundMode {
    HIGH   ("High",   1.0),
    MEDIUM ("Medium", 0.5),
    LOW    ("Low",    0.2),
    OFF    ("Off",    0.0);

    public final String label;
    public final double volume; // 0.0 – 1.0 multiplier applied to Short.MAX_VALUE

    SoundMode(String label, double volume) {
        this.label  = label;
        this.volume = volume;
    }

    public boolean isAudible() { return volume > 0.0; }

    /** Parse from persisted string; falls back to HIGH on unknown values. */
    public static SoundMode fromString(String s) {
        for (SoundMode m : values()) {
            if (m.name().equalsIgnoreCase(s)) return m;
        }
        // backwards-compat: old boolean "true"/"false"
        if ("false".equalsIgnoreCase(s)) return OFF;
        return HIGH;
    }
}
