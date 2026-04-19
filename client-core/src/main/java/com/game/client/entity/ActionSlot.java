package com.game.client.entity;

import java.util.Set;

/**
 * The fixed action button slots on the right HUD panel.
 * Each slot defines which weapon types enable it.
 */
public enum ActionSlot {

    ATTACK("Atk", Set.of(
            WeaponType.ONE_HAND_SWORD, WeaponType.ONE_HAND_BLUNT,
            WeaponType.TWO_HAND_SWORD, WeaponType.TWO_HAND_BLUNT,
            WeaponType.DAGGER)),

    HEAVY("Hvy", Set.of(
            WeaponType.TWO_HAND_SWORD, WeaponType.TWO_HAND_BLUNT,
            WeaponType.ONE_HAND_BLUNT)),

    RANGED("Rng", Set.of(
            WeaponType.BOW)),

    CAST("Cast", Set.of(
            WeaponType.STAFF)),

    SKILL("Skl", Set.of(
            WeaponType.ONE_HAND_SWORD, WeaponType.ONE_HAND_BLUNT,
            WeaponType.TWO_HAND_SWORD, WeaponType.TWO_HAND_BLUNT,
            WeaponType.DAGGER, WeaponType.BOW, WeaponType.STAFF)),

    DEFEND("Def", Set.of(
            WeaponType.ONE_HAND_SWORD, WeaponType.ONE_HAND_BLUNT, WeaponType.DAGGER));

    public final String label;
    private final Set<WeaponType> allowedWeapons;

    ActionSlot(String label, Set<WeaponType> allowedWeapons) {
        this.label          = label;
        this.allowedWeapons = allowedWeapons;
    }

    public boolean enabledFor(WeaponType weapon) {
        return allowedWeapons.contains(weapon);
    }
}
