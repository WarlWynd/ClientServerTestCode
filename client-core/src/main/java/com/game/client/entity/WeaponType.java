package com.game.client.entity;

public enum WeaponType {
    UNARMED,
    ONE_HAND_SWORD,
    ONE_HAND_BLUNT,
    TWO_HAND_SWORD,
    TWO_HAND_BLUNT,
    BOW,
    STAFF,
    DAGGER;

    public boolean isOneHanded() {
        return this == ONE_HAND_SWORD || this == ONE_HAND_BLUNT || this == DAGGER;
    }

    public boolean isTwoHanded() {
        return this == TWO_HAND_SWORD || this == TWO_HAND_BLUNT || this == BOW || this == STAFF;
    }

    public boolean isMelee() {
        return this != BOW && this != STAFF && this != UNARMED;
    }

    public boolean isRanged() {
        return this == BOW;
    }
}
