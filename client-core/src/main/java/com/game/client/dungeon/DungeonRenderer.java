package com.game.client.dungeon;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;

/**
 * Converts a DungeonMap into a jME3 scene-graph Node.
 *
 * Each WALL tile becomes a solid stone block spanning floor to ceiling.
 * Each FLOOR tile gets a floor slab and a ceiling slab.
 * Walls are slightly darker than floors for contrast.
 */
public class DungeonRenderer {

    private DungeonRenderer() {}

    public static Node buildScene(DungeonMap map, AssetManager assets) {
        Node root = new Node("dungeon");

        Material floorMat = unshaded(assets, new ColorRGBA(0.40f, 0.35f, 0.30f, 1f));
        Material wallMat  = unshaded(assets, new ColorRGBA(0.22f, 0.20f, 0.18f, 1f));
        Material ceilMat  = unshaded(assets, new ColorRGBA(0.18f, 0.16f, 0.15f, 1f));

        float ts = DungeonMap.TILE_SIZE;
        float wh = DungeonMap.WALL_HEIGHT;
        float hw = ts  / 2f;   // half tile width
        float hh = wh / 2f;    // half wall height

        for (int z = 0; z < map.depth; z++) {
            for (int x = 0; x < map.width; x++) {
                float wx = x * ts;
                float wz = z * ts;

                switch (map.get(x, z)) {
                    case FLOOR, DOOR_OPEN -> {
                        // Floor slab — top face sits at y = 0
                        Geometry floor = geo("floor_" + x + "_" + z, hw, 0.05f, hw, floorMat);
                        floor.setLocalTranslation(wx, -0.05f, wz);
                        root.attachChild(floor);

                        // Ceiling slab — bottom face sits at y = wh
                        Geometry ceil = geo("ceil_" + x + "_" + z, hw, 0.05f, hw, ceilMat);
                        ceil.setLocalTranslation(wx, wh + 0.05f, wz);
                        root.attachChild(ceil);
                    }
                    case WALL -> {
                        // Solid block, vertically centred between y=0 and y=wh
                        Geometry wall = geo("wall_" + x + "_" + z, hw, hh, hw, wallMat);
                        wall.setLocalTranslation(wx, hh, wz);
                        root.attachChild(wall);
                    }
                    default -> { /* VOID — render nothing */ }
                }
            }
        }

        root.attachChild(buildPerimeterWalls(map, assets));
        return root;
    }

    private static Node buildPerimeterWalls(DungeonMap map, AssetManager assets) {
        Node walls = new Node("perimeterWalls");
        Material mat = unshaded(assets, new ColorRGBA(0.35f, 0.32f, 0.28f, 1f));

        float ts   = DungeonMap.TILE_SIZE;
        float totalW = map.width * ts;   // world width  (X)
        float totalD = map.depth * ts;   // world depth  (Z)
        float cx     = (map.width  - 1) * ts / 2f;
        float cz     = (map.depth  - 1) * ts / 2f;

        float wallH   = 15f;   // tall so it's visible above interior walls
        float halfH   = wallH / 2f;
        float thick   = 1f;
        float halfT   = thick / 2f;
        float halfLen = totalW / 2f + thick;   // a bit wider than the dungeon

        // North
        Geometry north = geo("pw_north", halfLen, halfH, halfT, mat);
        north.setLocalTranslation(cx, halfH, -ts / 2f - halfT);
        walls.attachChild(north);

        // South
        Geometry south = geo("pw_south", halfLen, halfH, halfT, mat);
        south.setLocalTranslation(cx, halfH, cz + ts / 2f + halfT);
        walls.attachChild(south);

        // West
        Geometry west = geo("pw_west", halfT, halfH, halfLen, mat);
        west.setLocalTranslation(-ts / 2f - halfT, halfH, cz);
        walls.attachChild(west);

        // East
        Geometry east = geo("pw_east", halfT, halfH, halfLen, mat);
        east.setLocalTranslation(cx + ts / 2f + halfT, halfH, cz);
        walls.attachChild(east);

        return walls;
    }

    private static Geometry geo(String name, float hx, float hy, float hz, Material mat) {
        Geometry g = new Geometry(name, new Box(hx, hy, hz));
        g.setMaterial(mat);
        return g;
    }

    private static Material unshaded(AssetManager assets, ColorRGBA color) {
        Material m = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color);
        return m;
    }
}
