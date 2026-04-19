package com.game.client.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.terrain.heightmap.AbstractHeightMap;
import com.jme3.terrain.heightmap.ImageBasedHeightMap;
import com.jme3.texture.Texture;
import com.jme3.util.SkyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 3D open world — terrain, lighting, sky dome, and camera.
 *
 * Attaches HUDAppState once the world is ready.
 */
public class WorldAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(WorldAppState.class);

    private final NetworkAppState network;
    private SimpleApplication app;
    private Node worldNode;

    public WorldAppState(NetworkAppState network) {
        this.network = network;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        worldNode = new Node("world");

        setupLighting();
        setupTerrain();
        setupSky();
        setupCamera();

        app.getRootNode().attachChild(worldNode);

        // Attach HUD
        getStateManager().attach(new HUDAppState(network));

        log.info("World loaded.");
    }

    @Override
    protected void cleanup(Application app) {
        if (worldNode != null) worldNode.removeFromParent();
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    // ── Scene setup ───────────────────────────────────────────────────────────

    private void setupLighting() {
        AmbientLight ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.4f));
        app.getRootNode().addLight(ambient);

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.2f));
        app.getRootNode().addLight(sun);
    }

    private void setupTerrain() {
        // Flat placeholder terrain — replace with HeightMap once assets are ready
        com.jme3.scene.shape.Box ground = new com.jme3.scene.shape.Box(512, 0.5f, 512);
        com.jme3.scene.Geometry groundGeo = new com.jme3.scene.Geometry("ground", ground);

        com.jme3.material.Material mat = new com.jme3.material.Material(
                app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", new ColorRGBA(0.2f, 0.5f, 0.2f, 1f));
        groundGeo.setMaterial(mat);
        groundGeo.setLocalTranslation(0, -0.5f, 0);

        worldNode.attachChild(groundGeo);
    }

    private void setupSky() {
        try {
            app.getRootNode().attachChild(
                    SkyFactory.createSky(app.getAssetManager(),
                            "Textures/Sky/Bright/BrightSky.dds",
                            SkyFactory.EnvMapType.CubeMap));
        } catch (Exception e) {
            // Sky texture not yet present — silently skip
            log.debug("Sky texture not found, skipping sky dome: {}", e.getMessage());
        }
    }

    private void setupCamera() {
        app.getCamera().setLocation(new Vector3f(0, 10, 30));
        app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

        app.getFlyByCamera().setEnabled(true);
        app.getFlyByCamera().setMoveSpeed(20f);
        app.getFlyByCamera().setRotationSpeed(2f);
    }
}
