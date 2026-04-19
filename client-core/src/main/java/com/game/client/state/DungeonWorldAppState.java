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
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
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

    // Camera sits this far behind (+Z in player-local space) and above
    private static final float CAM_BACK = 7f;
    private static final float CAM_UP   = 4f;

    private final NetworkAppState network;
    private SimpleApplication app;

    // Matches GridMovementController.STEP_COOLDOWN — keeps WALK anim alive for one step
    private static final float WALK_ANIM_DURATION = 0.18f;

    private DungeonMap             map;
    private CharacterEntity        localPlayer;
    private CharacterManager       chars;
    private GridMovementController controller;
    private float                  walkTimer = 0f;

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
        getStateManager().attach(new HUDAppState(network, map, localPlayer, controller));

        log.info("Dungeon world ready — player at ({}, {})", localPlayer.gridX, localPlayer.gridZ);
    }

    @Override
    protected void cleanup(Application app) {}

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
        syncCamera();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupLighting() {
        // Warm torch-light feel: bright ambient so dungeon walls are visible,
        // directional for shadow definition.
        AmbientLight ambient = new AmbientLight(new ColorRGBA(0.65f, 0.58f, 0.50f, 1f));
        app.getRootNode().addLight(ambient);

        DirectionalLight torch = new DirectionalLight();
        torch.setDirection(new Vector3f(-0.4f, -1f, -0.6f).normalizeLocal());
        torch.setColor(new ColorRGBA(1.0f, 0.85f, 0.60f, 1f));
        app.getRootNode().addLight(torch);
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
        syncCamera();
    }

    private void attachMovement() {
        controller = new GridMovementController(localPlayer, map, this::onPlayerMoved);
        getStateManager().attach(controller);
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void syncCamera() {
        float ts = DungeonMap.TILE_SIZE;

        // Player world position (standing on floor)
        Vector3f playerPos = new Vector3f(
                map.worldX(localPlayer.gridX),
                0f,
                map.worldZ(localPlayer.gridZ));

        // Rotate the "behind + above" offset by player's facing yaw
        Quaternion rot = new Quaternion();
        rot.fromAngleAxis(localPlayer.facing.toYaw(), Vector3f.UNIT_Y);

        // In player-local space: +Z is behind (player faces -Z), +Y is up
        Vector3f localOffset = new Vector3f(0, CAM_UP, CAM_BACK);
        Vector3f worldOffset = rot.mult(localOffset);

        Camera cam = app.getCamera();
        cam.setLocation(playerPos.add(worldOffset));

        // Look at a point slightly above the player's torso
        cam.lookAt(playerPos.add(0, 1.4f, 0), Vector3f.UNIT_Y);
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
