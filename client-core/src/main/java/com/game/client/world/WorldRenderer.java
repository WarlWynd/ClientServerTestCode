package com.game.client.world;

import com.game.shared.WorldDef;
import com.game.shared.WorldObject;
import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.terrain.geomipmap.TerrainLodControl;
import com.jme3.terrain.geomipmap.TerrainQuad;

import java.util.List;

/**
 * Converts a WorldDef into a jME3 scene-graph Node.
 *
 * The returned node contains:
 *   "terrain" — a TerrainQuad driven by WorldDef.heightmap
 *   "objects" — placeholder box geometries for each WorldObject instance
 *
 * TerrainQuad is centered at local origin, so world (0,0) is the terrain centre.
 * Scale: each heightmap cell = xzScale world units; max height = yScale world units.
 */
public class WorldRenderer {

    // patchSize must be 2^n+1 and divide evenly into (totalSize-1).
    // 65 (2^6+1) works for any standard size: 129, 257, 513.
    private static final int PATCH_SIZE = 65;

    private WorldRenderer() {}

    public static Node buildScene(WorldDef def, AssetManager assets, Camera camera) {
        Node root = new Node("worldScene");
        root.attachChild(buildTerrain(def, assets, camera));
        if (def.objects != null && !def.objects.isEmpty()) {
            root.attachChild(buildObjects(def.objects, assets));
        }
        return root;
    }

    public static TerrainQuad buildTerrain(WorldDef def, AssetManager assets, Camera camera) {
        TerrainQuad terrain = new TerrainQuad("terrain", PATCH_SIZE, def.heightmapSize, def.heightmap);
        terrain.setLocalScale(def.xzScale, def.yScale, def.xzScale);
        terrain.setMaterial(buildTerrainMaterial(assets));
        terrain.addControl(new TerrainLodControl(terrain, camera));
        return terrain;
    }

    private static Material buildTerrainMaterial(AssetManager assets) {
        Material mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Diffuse",  new ColorRGBA(0.30f, 0.58f, 0.20f, 1f));
        mat.setColor("Ambient",  new ColorRGBA(0.18f, 0.35f, 0.12f, 1f));
        mat.setColor("Specular", ColorRGBA.Black);
        mat.setFloat("Shininess", 0f);
        return mat;
    }

    private static Node buildObjects(List<WorldObject> objects, AssetManager assets) {
        Node node = new Node("objects");

        Material mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Diffuse",  new ColorRGBA(0.55f, 0.38f, 0.18f, 1f));
        mat.setColor("Ambient",  new ColorRGBA(0.28f, 0.19f, 0.09f, 1f));
        mat.setColor("Specular", ColorRGBA.Black);
        mat.setFloat("Shininess", 0f);

        for (WorldObject obj : objects) {
            Geometry g = new Geometry(obj.instanceId, new Box(0.5f, 0.5f, 0.5f));
            g.setMaterial(mat);
            g.setLocalTranslation(obj.x, obj.y + 0.5f, obj.z);
            g.setLocalRotation(new Quaternion()
                    .fromAngleAxis(obj.yaw * FastMath.DEG_TO_RAD, Vector3f.UNIT_Y));
            g.setLocalScale(obj.scale);
            node.attachChild(g);
        }
        return node;
    }
}
