package com.game.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Defines a full open 3D world: terrain heightmap, placed objects, and metadata.
 *
 * Heightmap conventions:
 *   - Size must be (2^n + 1), e.g. 129, 257, 513 — required by jME3 TerrainQuad.
 *   - Values are normalized [0.0, 1.0]. Multiply by yScale for world-space height.
 *   - Indexed row-major: index = z * heightmapSize + x.
 *
 * World dimensions:
 *   - Width / Depth = (heightmapSize - 1) * xzScale  world units.
 *   - Max height    = yScale                          world units.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldDef {

    public String id;
    public String name;

    /** Heightmap edge length — must be 2^n + 1 (default 257). */
    public int heightmapSize;

    /** World units per heightmap cell on X and Z axes. */
    public float xzScale;

    /** Maximum terrain height in world units. */
    public float yScale;

    /**
     * Flattened heightmap. Length == heightmapSize * heightmapSize.
     * All values in [0.0, 1.0].
     */
    public float[] heightmap;

    /**
     * Up to 4 texture layer keys (e.g. "grass", "rock", "dirt", "snow").
     * Layers blend from index 0 (low/flat) to index 3 (high/steep).
     */
    public String[] textureLayers;

    /** All placed object instances in this world. */
    public List<WorldObject> objects;

    /** Player spawn position in world space. */
    public float spawnX, spawnY, spawnZ;

    /** Required by Jackson */
    public WorldDef() {}

    /**
     * Creates a new world with a flat heightmap (all values 0.0) and default scale.
     */
    public static WorldDef createFlat(String name, int heightmapSize) {
        WorldDef w = new WorldDef();
        w.id             = UUID.randomUUID().toString();
        w.name           = name;
        w.heightmapSize  = heightmapSize;
        w.xzScale        = WorldConstants.TERRAIN_XZ_SCALE;
        w.yScale         = WorldConstants.TERRAIN_Y_SCALE;
        w.heightmap      = new float[heightmapSize * heightmapSize];
        w.textureLayers  = new String[]{"grass", "dirt", "rock", "snow"};
        w.objects        = new ArrayList<>();
        // jME3 TerrainQuad is centered at world origin, so (0,0) is the terrain centre
        w.spawnX = 0f;
        w.spawnY = 0f;
        w.spawnZ = 0f;
        return w;
    }

    /** Returns the heightmap index for grid coordinates (x, z). */
    public int heightIndex(int x, int z) {
        return z * heightmapSize + x;
    }

    /** Returns the normalized height [0,1] at grid coordinates (x, z). */
    public float heightAt(int x, int z) {
        return heightmap[heightIndex(x, z)];
    }

    /** World-space width and depth (both equal for a square terrain). */
    public float worldSize() {
        return (heightmapSize - 1) * xzScale;
    }
}
