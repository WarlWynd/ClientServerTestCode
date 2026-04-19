package com.game.client.dungeon;

import static com.game.client.dungeon.DungeonTile.*;

/**
 * 2-D grid of tiles representing one dungeon level.
 * Row 0 is the "north" edge; column 0 is the "west" edge.
 * Tile coordinates map to world space via TILE_SIZE.
 */
public class DungeonMap {

    public static final float TILE_SIZE   = 3f;
    public static final float WALL_HEIGHT = 3f;

    private final DungeonTile[][] tiles; // [z][x]
    public  final int width;
    public  final int depth;

    public DungeonMap(DungeonTile[][] tiles) {
        this.tiles = tiles;
        this.depth = tiles.length;
        this.width = tiles[0].length;
    }

    public DungeonTile get(int x, int z) {
        if (x < 0 || z < 0 || x >= width || z >= depth) return WALL;
        return tiles[z][x];
    }

    public boolean isPassable(int x, int z) {
        DungeonTile t = get(x, z);
        return t == FLOOR || t == DOOR_OPEN;
    }

    /** Returns world-space X centre of grid column x. */
    public float worldX(int x) { return x * TILE_SIZE; }

    /** Returns world-space Z centre of grid row z. */
    public float worldZ(int z) { return z * TILE_SIZE; }

    // ── Test dungeon ──────────────────────────────────────────────────────────

    /** Hand-authored 10×10 starter dungeon. Replace with server-sent data. */
    public static DungeonMap testDungeon() {
        DungeonTile W = WALL, F = FLOOR;
        DungeonTile[][] grid = {
            {W, W, W, W, W, W, W, W, W, W},
            {W, F, F, F, W, F, F, F, F, W},
            {W, F, W, F, W, F, W, W, F, W},
            {W, F, W, F, F, F, W, W, F, W},
            {W, F, W, W, W, F, W, W, F, W},
            {W, F, F, F, F, F, F, F, F, W},
            {W, W, F, W, W, W, F, W, W, W},
            {W, F, F, F, F, F, F, F, F, W},
            {W, F, W, W, F, W, W, W, F, W},
            {W, W, W, W, W, W, W, W, W, W},
        };
        return new DungeonMap(grid);
    }
}
