package com.game.client.state;

import com.game.client.SessionStore;
import com.game.client.dungeon.DungeonMap;
import com.game.client.dungeon.DungeonRenderer;
import com.game.client.dungeon.Facing;
import com.game.client.entity.CharacterClass;
import com.game.client.entity.CharacterEntity;
import com.game.client.entity.CharacterManager;
import com.game.client.entity.CharacterState;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 3D dungeon-crawler world state.
 *
 * Replaces the open-world WorldAppState. The world is a grid of tiles;
 * movement is discrete (one tile per key press, 90° snap turns).
 *
 * Current player spawns at the first walkable tile and is rendered as
 * a placeholder box-man. Real GLTF models slot in via CharacterManager
 * once assets exist.
 *
 * Camera: third-person chase, always behind the player, snaps on turns.
 */
public class DungeonWorldAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(DungeonWorldAppState.class);

    private static final float EYE_H = 1.6f;

    private final NetworkAppState network;
    private SimpleApplication app;

    // Matches GridMovementController.STEP_COOLDOWN — keeps WALK anim alive for one step
    private static final float WALK_ANIM_DURATION = 0.18f;

    private DungeonMap             map;
    private CharacterEntity        localPlayer;
    private CharacterManager       chars;
    private GridMovementController controller;
    private float                  walkTimer = 0f;
    private PointLight             torchLight;

    // Smooth camera interpolation
    private static final float     CAM_SMOOTH = 15f;   // exponential decay factor
    private final Vector3f         camPos     = new Vector3f();
    private final Vector3f         camTarget  = new Vector3f();
    private float                  camYaw     = 0f;
    private float                  camYawTarget = 0f;
    private float                  camPitch   = 0f;   // degrees; W=up(+), S=down(-)
    private static final float     PITCH_STEP = 5f;
    private static final float     PITCH_MAX  = 30f;
    private boolean                firstSync  = true;

    private static final String PITCH_UP   = "dw_pitch_up";
    private static final String PITCH_DOWN = "dw_pitch_down";
    private ActionListener      pitchListener;

    public DungeonWorldAppState(NetworkAppState network) {
        this.network = network;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        this.map = DungeonMap.testDungeon();

        app.getFlyByCamera().setEnabled(false);

        setupLighting();
        buildDungeon();
        spawnPlayer();
        attachMovement();
        attachPitchInput();
        getStateManager().attach(new HUDAppState(network, map, localPlayer, controller));

        log.info("Dungeon world ready — player at ({}, {})", localPlayer.gridX, localPlayer.gridZ);
    }

    @Override
    protected void cleanup(Application app) {
        var im = app.getInputManager();
        if (pitchListener != null) im.removeListener(pitchListener);
        for (String m : new String[]{PITCH_UP, PITCH_DOWN})
            if (im.hasMapping(m)) im.deleteMapping(m);
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    @Override
    public void update(float tpf) {
        if (localPlayer == null) return;
        if (walkTimer > 0) {
            walkTimer -= tpf;
            if (walkTimer <= 0 && localPlayer.state == CharacterState.WALK) {
                localPlayer.state = CharacterState.IDLE;
                chars.updateEntity(localPlayer);
            }
        }
        smoothCamera(tpf);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupLighting() {
        // Dark ambient matching admin's 20% grey — dungeon is lit by torch only.
        AmbientLight ambient = new AmbientLight(new ColorRGBA(0.20f, 0.18f, 0.14f, 1f));
        app.getRootNode().addLight(ambient);

        // Warm point light that moves with the camera, matching admin's ffe8a8 torch.
        torchLight = new PointLight();
        torchLight.setColor(new ColorRGBA(1.10f, 0.91f, 0.58f, 1f));
        torchLight.setRadius(DungeonMap.TILE_SIZE * 8);
        app.getRootNode().addLight(torchLight);
    }

    private void buildDungeon() {
        app.getRootNode().attachChild(DungeonRenderer.buildScene(map, app.getAssetManager()));
    }

    private void spawnPlayer() {
        int sx = 1, sz = 1;
        outer:
        for (int z = 0; z < map.depth; z++) {
            for (int x = 0; x < map.width; x++) {
                if (map.isPassable(x, z)) { sx = x; sz = z; break outer; }
            }
        }

        localPlayer = new CharacterEntity(
                SessionStore.getUsername(),
                CharacterClass.WARRIOR,
                sx, sz,
                Facing.NORTH);

        chars = new CharacterManager(app);
        chars.addEntity(localPlayer);
        chars.setVisible(localPlayer.id, false);
        syncCamera();
    }

    private void attachMovement() {
        controller = new GridMovementController(localPlayer, map, this::onPlayerMoved);
        getStateManager().attach(controller);
    }

    private void attachPitchInput() {
        var im = app.getInputManager();
        im.addMapping(PITCH_UP,   new KeyTrigger(KeyInput.KEY_PGUP));
        im.addMapping(PITCH_DOWN, new KeyTrigger(KeyInput.KEY_PGDN));
        pitchListener = this::onPitchAction;
        im.addListener(pitchListener, PITCH_UP, PITCH_DOWN);
    }

    private void onPitchAction(String name, boolean isPressed, float tpf) {
        if (!isPressed) return;
        if (PITCH_UP.equals(name))   camPitch = Math.min( PITCH_MAX, camPitch + PITCH_STEP);
        if (PITCH_DOWN.equals(name)) camPitch = Math.max(-PITCH_MAX, camPitch - PITCH_STEP);
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    /** Sets the target the camera smoothly moves toward. First call snaps instantly. */
    private void syncCamera() {
        camTarget.set(map.worldX(localPlayer.gridX), EYE_H, map.worldZ(localPlayer.gridZ));

        // Shortest-path yaw so we never spin 270° when 90° is shorter.
        float newYaw = localPlayer.facing.toYaw();
        float delta  = newYaw - camYawTarget;
        while (delta >  FastMath.PI) delta -= FastMath.TWO_PI;
        while (delta < -FastMath.PI) delta += FastMath.TWO_PI;
        camYawTarget += delta;

        if (firstSync) {
            firstSync = false;
            camPos.set(camTarget);
            camYaw = camYawTarget;
            applyCamera();
        }
    }

    private void smoothCamera(float tpf) {
        float alpha = 1f - (float) Math.exp(-CAM_SMOOTH * tpf);
        camPos.interpolateLocal(camTarget, alpha);
        camYaw += (camYawTarget - camYaw) * alpha;
        applyCamera();
    }

    private void applyCamera() {
        Camera cam = app.getCamera();
        cam.setLocation(camPos);
        float pitchRad = camPitch * FastMath.DEG_TO_RAD;
        float cosPitch = FastMath.cos(pitchRad);
        float sinPitch = FastMath.sin(pitchRad);
        cam.lookAtDirection(
            new Vector3f(FastMath.sin(camYaw) * cosPitch, sinPitch, -FastMath.cos(camYaw) * cosPitch),
            Vector3f.UNIT_Y);
        if (torchLight != null) torchLight.setPosition(camPos);
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private void onPlayerMoved() {
        localPlayer.state = CharacterState.WALK;
        walkTimer = WALK_ANIM_DURATION;
        chars.updateEntity(localPlayer);
        syncCamera();
        // TODO: send PLAYER_UPDATE to server with new grid position + facing
    }
}
