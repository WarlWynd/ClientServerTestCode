package com.game.client.ui;

/**
 * Equipment slots available to a player character.
 *
 * Rules enforced by WeaponRenderer:
 *   - Equipping TWO_HANDED clears MAIN_HAND and OFF_HAND.
 *   - Equipping MAIN_HAND or OFF_HAND clears TWO_HANDED.
 */
public enum EquipSlot {
    MAIN_HAND,   // right hand — 1H weapon
    OFF_HAND,    // left hand  — 1H weapon or shield
    TWO_HANDED   // both hands — staff, greatsword, bow, etc.
}
