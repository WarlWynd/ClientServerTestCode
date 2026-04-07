package com.game.client.ui;

import javafx.scene.paint.Color;

/**
 * A specific weapon instance — type + visual tint + display name.
 *
 * Use the predefined constants for common archetypes, or construct
 * a custom instance for server-defined or procedurally generated items:
 *
 *   weaponRenderer.equip(EquipSlot.TWO_HANDED, Weapon.WOODEN_STAFF);
 *   weaponRenderer.equip(EquipSlot.MAIN_HAND,  new Weapon("Flame Sword", WeaponType.SWORD_1H, Color.ORANGERED));
 */
public final class Weapon {

    public final String     name;
    public final WeaponType type;
    public final Color      tint;

    public Weapon(String name, WeaponType type, Color tint) {
        this.name = name;
        this.type = type;
        this.tint = tint;
    }

    // ── Staves ────────────────────────────────────────────────────────────────
    public static final Weapon WOODEN_STAFF   = new Weapon("Wooden Staff",   WeaponType.STAFF,    Color.web("#8B4513"));
    public static final Weapon IRON_STAFF     = new Weapon("Iron Staff",     WeaponType.STAFF,    Color.web("#8899aa"));
    public static final Weapon FIRE_STAFF     = new Weapon("Fire Staff",     WeaponType.STAFF,    Color.web("#cc3300"));
    public static final Weapon FROST_STAFF    = new Weapon("Frost Staff",    WeaponType.STAFF,    Color.web("#66ccff"));
    public static final Weapon SHADOW_STAFF   = new Weapon("Shadow Staff",   WeaponType.STAFF,    Color.web("#6633aa"));

    // ── One-handed swords ─────────────────────────────────────────────────────
    public static final Weapon IRON_SWORD     = new Weapon("Iron Sword",     WeaponType.SWORD_1H, Color.web("#b0b8c8"));
    public static final Weapon STEEL_SWORD    = new Weapon("Steel Sword",    WeaponType.SWORD_1H, Color.web("#d0d8e8"));
    public static final Weapon FLAME_SWORD    = new Weapon("Flame Sword",    WeaponType.SWORD_1H, Color.web("#ff6622"));
    public static final Weapon FROST_SWORD    = new Weapon("Frost Sword",    WeaponType.SWORD_1H, Color.web("#66ccff"));
    public static final Weapon GOLDEN_SWORD   = new Weapon("Golden Sword",   WeaponType.SWORD_1H, Color.web("#ffd700"));
    public static final Weapon SHADOW_BLADE   = new Weapon("Shadow Blade",   WeaponType.SWORD_1H, Color.web("#442266"));

    // ── Two-handed swords ─────────────────────────────────────────────────────
    public static final Weapon GREAT_SWORD    = new Weapon("Greatsword",     WeaponType.SWORD_2H, Color.web("#c0c8d8"));
    public static final Weapon CLAYMORE       = new Weapon("Claymore",       WeaponType.SWORD_2H, Color.web("#a8b0c0"));
    public static final Weapon FLAME_GREAT    = new Weapon("Flame Greatsword", WeaponType.SWORD_2H, Color.web("#ff4400"));
    public static final Weapon RUNE_GREAT     = new Weapon("Runic Greatsword", WeaponType.SWORD_2H, Color.web("#8866ff"));

    // ── One-handed axes ───────────────────────────────────────────────────────
    public static final Weapon HAND_AXE       = new Weapon("Hand Axe",       WeaponType.AXE_1H,   Color.web("#8B4513"));
    public static final Weapon IRON_AXE       = new Weapon("Iron Axe",       WeaponType.AXE_1H,   Color.web("#909090"));
    public static final Weapon HATCHET        = new Weapon("Hatchet",        WeaponType.AXE_1H,   Color.web("#7a6040"));
    public static final Weapon GOLDEN_AXE     = new Weapon("Golden Axe",     WeaponType.AXE_1H,   Color.web("#ffd700"));

    // ── Two-handed axes ───────────────────────────────────────────────────────
    public static final Weapon GREAT_AXE      = new Weapon("Greataxe",       WeaponType.AXE_2H,   Color.web("#808080"));
    public static final Weapon BATTLE_AXE     = new Weapon("Battleaxe",      WeaponType.AXE_2H,   Color.web("#a0a0b0"));
    public static final Weapon CLEAVER        = new Weapon("Cleaver",        WeaponType.AXE_2H,   Color.web("#cc2200"));

    // ── Spears ────────────────────────────────────────────────────────────────
    public static final Weapon IRON_SPEAR     = new Weapon("Iron Spear",     WeaponType.SPEAR,    Color.web("#9090a0"));
    public static final Weapon WAR_SPEAR      = new Weapon("War Spear",      WeaponType.SPEAR,    Color.web("#c0c0d0"));
    public static final Weapon TRIDENT        = new Weapon("Trident",        WeaponType.SPEAR,    Color.web("#4488cc"));
    public static final Weapon FLAME_SPEAR    = new Weapon("Flame Spear",    WeaponType.SPEAR,    Color.web("#ff5500"));

    // ── Daggers ───────────────────────────────────────────────────────────────
    public static final Weapon IRON_DAGGER    = new Weapon("Iron Dagger",    WeaponType.DAGGER,   Color.web("#b0b8c8"));
    public static final Weapon ROGUE_BLADE    = new Weapon("Rogue's Blade",  WeaponType.DAGGER,   Color.web("#336633"));
    public static final Weapon POISON_DAGGER  = new Weapon("Poison Dagger",  WeaponType.DAGGER,   Color.web("#44aa22"));
    public static final Weapon SHADOW_DAGGER  = new Weapon("Shadow Dagger",  WeaponType.DAGGER,   Color.web("#553388"));

    // ── Shields ───────────────────────────────────────────────────────────────
    public static final Weapon WOODEN_SHIELD  = new Weapon("Wooden Shield",  WeaponType.SHIELD,   Color.web("#6B4226"));
    public static final Weapon IRON_SHIELD    = new Weapon("Iron Shield",    WeaponType.SHIELD,   Color.web("#778899"));
    public static final Weapon TOWER_SHIELD   = new Weapon("Tower Shield",   WeaponType.SHIELD,   Color.web("#445566"));
    public static final Weapon GOLDEN_SHIELD  = new Weapon("Golden Shield",  WeaponType.SHIELD,   Color.web("#cc9900"));
    public static final Weapon KITE_SHIELD    = new Weapon("Kite Shield",    WeaponType.SHIELD,   Color.web("#884422"));

    // ── Bows ──────────────────────────────────────────────────────────────────
    public static final Weapon SHORT_BOW      = new Weapon("Short Bow",      WeaponType.BOW,      Color.web("#8B5E3C"));
    public static final Weapon LONG_BOW       = new Weapon("Long Bow",       WeaponType.BOW,      Color.web("#6B4226"));
    public static final Weapon ELVEN_BOW      = new Weapon("Elven Bow",      WeaponType.BOW,      Color.web("#88aa44"));
    public static final Weapon WAR_BOW        = new Weapon("War Bow",        WeaponType.BOW,      Color.web("#554422"));

    // ── Maces ─────────────────────────────────────────────────────────────────
    public static final Weapon WOODEN_CLUB    = new Weapon("Wooden Club",    WeaponType.MACE_1H,  Color.web("#8B5E3C"));
    public static final Weapon IRON_MACE      = new Weapon("Iron Mace",      WeaponType.MACE_1H,  Color.web("#909090"));
    public static final Weapon WAR_MACE       = new Weapon("War Mace",       WeaponType.MACE_1H,  Color.web("#b0b0c0"));
    public static final Weapon HOLY_MACE      = new Weapon("Holy Mace",      WeaponType.MACE_1H,  Color.web("#eecc44"));

    // ── Exotic ────────────────────────────────────────────────────────────────
    // ── Morning stars / flails ────────────────────────────────────────────────
    public static final Weapon MORNING_STAR       = new Weapon("Morning Star",        WeaponType.MORNING_STAR, Color.web("#888888"));
    public static final Weapon HEAVY_MORNING_STAR = new Weapon("Heavy Morning Star",  WeaponType.MORNING_STAR, Color.web("#606060"));
    public static final Weapon GOLDEN_FLAIL       = new Weapon("Golden Flail",        WeaponType.MORNING_STAR, Color.web("#cc9900"));
    public static final Weapon SHADOW_FLAIL       = new Weapon("Shadow Flail",        WeaponType.MORNING_STAR, Color.web("#442266"));

    public static final Weapon WOODEN_NUNCHUCKS = new Weapon("Wooden Nunchucks", WeaponType.NUNCHUCKS, Color.web("#8B5E3C"));
    public static final Weapon IRON_NUNCHUCKS   = new Weapon("Iron Nunchucks",   WeaponType.NUNCHUCKS, Color.web("#909090"));
    public static final Weapon SHADOW_NUNCHUCKS = new Weapon("Shadow Nunchucks", WeaponType.NUNCHUCKS, Color.web("#442266"));

    @Override
    public String toString() { return name; }
}
