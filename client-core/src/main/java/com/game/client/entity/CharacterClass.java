package com.game.client.entity;

import com.jme3.math.ColorRGBA;

public enum CharacterClass {
    WARRIOR, RANGER, MAGE;

    public ColorRGBA bodyColor() {
        return switch (this) {
            case WARRIOR -> new ColorRGBA(0.7f, 0.3f, 0.2f, 1f);
            case RANGER  -> new ColorRGBA(0.3f, 0.6f, 0.2f, 1f);
            case MAGE    -> new ColorRGBA(0.2f, 0.3f, 0.8f, 1f);
        };
    }
}
