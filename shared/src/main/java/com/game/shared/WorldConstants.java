package com.game.shared;

public final class WorldConstants {
    private WorldConstants() {}

    // ── Dungeon (legacy tile-based) ──────────────────────────────────────────
    public static final float TILE_SIZE   = 12f;
    public static final float WALL_HEIGHT = 10f;

    // ── Open World (terrain-based) ───────────────────────────────────────────

    /** Default heightmap edge length. Must be 2^n + 1 for jME3 TerrainQuad. */
    public static final int   TERRAIN_SIZE     = 257;

    /** World units per heightmap cell on X and Z. 257 cells → 1024 unit world. */
    public static final float TERRAIN_XZ_SCALE = 4f;

    /** Maximum terrain height in world units. */
    public static final float TERRAIN_Y_SCALE  = 200f;
}
