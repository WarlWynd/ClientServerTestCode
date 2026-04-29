package com.game.client.state;

import com.game.client.world.WorldMovementController;
import com.game.client.world.WorldRenderer;
import com.game.shared.WorldConstants;
import com.game.shared.WorldDef;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.util.SkyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open 3D world state — terrain, lighting, sky, and character movement.
 *
 * Owns the scene graph for the world and delegates all player input and
 * kinematic movement to WorldMovementController.
 *
 * WorldAppState() — flat default world for testing.
 * WorldAppState(def) — load a WorldDef received from the server.
 */
public class WorldAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(WorldAppState.class);

    private final WorldDef def;

    private SimpleApplication    app;
    private Node                 worldNode;
    private TerrainQuad          terrain;
    private WorldMovementController controller;

    public WorldAppState() {
        this(WorldDef.createFlat("Default World", WorldConstants.TERRAIN_SIZE));
    }

    public WorldAppState(WorldDef def) {
        this.def = def;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        app.getFlyByCamera().setEnabled(false); // WorldMovementController owns all input

        setupLighting();
        setupSky();

        worldNode = WorldRenderer.buildScene(def, app.getAssetManager(), app.getCamera());
        terrain   = (TerrainQuad) worldNode.getChild("terrain");
        app.getRootNode().attachChild(worldNode);

        float spawnY = sampleHeight(def.spawnX, def.spawnZ) + WorldMovementController.EYE_HEIGHT;
        controller = new WorldMovementController(terrain, new Vector3f(def.spawnX, spawnY, def.spawnZ));
        getStateManager().attach(controller);

        log.info("World '{}' ready — {}×{} heightmap, xzScale={} yScale={}",
                def.name, def.heightmapSize, def.heightmapSize, def.xzScale, def.yScale);
    }

    @Override
    protected void cleanup(Application app) {
        if (controller != null) getStateManager().detach(controller);
        if (worldNode   != null) worldNode.removeFromParent();
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    // ── Scene setup ───────────────────────────────────────────────────────────

    private void setupLighting() {
        AmbientLight ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.45f));
        app.getRootNode().addLight(ambient);

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.6f, -1f, -0.4f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.2f));
        app.getRootNode().addLight(sun);
    }

    private void setupSky() {
        try {
            app.getRootNode().attachChild(
                    SkyFactory.createSky(app.getAssetManager(),
                            "Textures/Sky/Bright/BrightSky.dds",
                            SkyFactory.EnvMapType.CubeMap));
        } catch (Exception e) {
            log.debug("Sky texture not found, skipping sky dome: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private float sampleHeight(float x, float z) {
        if (terrain == null) return 0f;
        float h = terrain.getHeight(new Vector2f(x, z));
        return Float.isNaN(h) ? 0f : h;
    }
}
