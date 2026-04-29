package com.game.client.world;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.terrain.geomipmap.TerrainQuad;

/**
 * Kinematic first-person character controller for the open world.
 *
 * Movement model:
 *   WASD / arrow keys — walk in horizontal plane relative to camera yaw
 *   Mouse drag         — yaw (left/right) and pitch (up/down)
 *   Space              — jump (only while on ground)
 *   Escape             — toggle cursor (game vs UI mode)
 *
 * Terrain following: each frame the player's Y is clamped to
 * terrain.getHeight(xz) + EYE_HEIGHT. Simple gravity pulls the player
 * down when airborne; jump impulse throws them up.
 *
 * No Bullet physics needed — terrain height sampling is exact and fast.
 */
public class WorldMovementController extends BaseAppState
        implements ActionListener, AnalogListener {

    // ── Input mapping names ───────────────────────────────────────────────────

    private static final String FWD        = "wmc_fwd";
    private static final String BACK       = "wmc_back";
    private static final String STRAFE_L   = "wmc_sl";
    private static final String STRAFE_R   = "wmc_sr";
    private static final String JUMP       = "wmc_jump";
    private static final String CURSOR     = "wmc_cursor";

    private static final String LOOK_RIGHT = "wmc_look_r";
    private static final String LOOK_LEFT  = "wmc_look_l";
    private static final String LOOK_UP    = "wmc_look_u";
    private static final String LOOK_DOWN  = "wmc_look_d";

    // ── Tuning ────────────────────────────────────────────────────────────────

    public static final float EYE_HEIGHT  = 1.8f;
    private static final float MOVE_SPEED  = 20f;   // world units / sec
    private static final float LOOK_SPEED  = 2.5f;  // radians per normalised mouse delta
    private static final float GRAVITY     = 28f;   // world units / sec²
    private static final float JUMP_SPEED  = 12f;   // initial upward velocity on jump
    private static final float PITCH_LIMIT = FastMath.HALF_PI - 0.05f;  // ≈ 85°

    // ── State ─────────────────────────────────────────────────────────────────

    private final TerrainQuad terrain;

    private SimpleApplication app;
    private Camera            cam;

    private float    yaw              = 0f;   // radians; increases when turning left (CCW from above)
    private float    pitch            = 0f;   // radians; positive = looking up
    private Vector3f position;                // current camera world position
    private float    verticalVelocity = 0f;
    private boolean  onGround         = false;

    private boolean fwd, back, strafeL, strafeR;

    // ── Construction ──────────────────────────────────────────────────────────

    public WorldMovementController(TerrainQuad terrain, Vector3f startPos) {
        this.terrain  = terrain;
        this.position = startPos.clone();
    }

    /** Current world position of the camera (read by WorldAppState for network updates). */
    public Vector3f getPosition() { return position; }

    /** Current yaw in radians. */
    public float getYaw() { return yaw; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        this.cam = app.getCamera();

        InputManager im = app.getInputManager();
        im.setCursorVisible(false);

        // WASD / arrows — ActionListener (track pressed / released)
        im.addMapping(FWD,      new KeyTrigger(KeyInput.KEY_W),    new KeyTrigger(KeyInput.KEY_UP));
        im.addMapping(BACK,     new KeyTrigger(KeyInput.KEY_S),    new KeyTrigger(KeyInput.KEY_DOWN));
        im.addMapping(STRAFE_L, new KeyTrigger(KeyInput.KEY_A),    new KeyTrigger(KeyInput.KEY_LEFT));
        im.addMapping(STRAFE_R, new KeyTrigger(KeyInput.KEY_D),    new KeyTrigger(KeyInput.KEY_RIGHT));
        im.addMapping(JUMP,     new KeyTrigger(KeyInput.KEY_SPACE));
        im.addMapping(CURSOR,   new KeyTrigger(KeyInput.KEY_ESCAPE));
        im.addListener((ActionListener) this, FWD, BACK, STRAFE_L, STRAFE_R, JUMP, CURSOR);

        // Mouse look — AnalogListener (mirrors FlyByCamera axis conventions)
        im.addMapping(LOOK_RIGHT, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        im.addMapping(LOOK_LEFT,  new MouseAxisTrigger(MouseInput.AXIS_X, true));
        im.addMapping(LOOK_UP,    new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        im.addMapping(LOOK_DOWN,  new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        im.addListener((AnalogListener) this, LOOK_RIGHT, LOOK_LEFT, LOOK_UP, LOOK_DOWN);

        // Snap camera to start position immediately
        applyCamera();
    }

    @Override
    protected void cleanup(Application app) {
        InputManager im = app.getInputManager();
        im.setCursorVisible(true);
        im.removeListener((ActionListener) this);
        im.removeListener((AnalogListener) this);
        for (String m : new String[]{FWD, BACK, STRAFE_L, STRAFE_R, JUMP, CURSOR,
                                     LOOK_RIGHT, LOOK_LEFT, LOOK_UP, LOOK_DOWN}) {
            if (im.hasMapping(m)) im.deleteMapping(m);
        }
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        switch (name) {
            case FWD      -> fwd     = isPressed;
            case BACK     -> back    = isPressed;
            case STRAFE_L -> strafeL = isPressed;
            case STRAFE_R -> strafeR = isPressed;
            case JUMP -> {
                if (isPressed && onGround) {
                    verticalVelocity = JUMP_SPEED;
                    onGround = false;
                }
            }
            case CURSOR -> {
                if (isPressed) {
                    boolean v = app.getInputManager().isCursorVisible();
                    app.getInputManager().setCursorVisible(!v);
                }
            }
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        // Ignore mouse look while cursor is visible (UI mode)
        if (app.getInputManager().isCursorVisible()) return;
        switch (name) {
            // Mouse right → turn right → yaw decreases (CCW convention: +yaw = left)
            case LOOK_RIGHT -> yaw   -= value * LOOK_SPEED;
            case LOOK_LEFT  -> yaw   += value * LOOK_SPEED;
            case LOOK_UP    -> pitch  = FastMath.clamp(pitch - value * LOOK_SPEED, -PITCH_LIMIT, PITCH_LIMIT);
            case LOOK_DOWN  -> pitch  = FastMath.clamp(pitch + value * LOOK_SPEED, -PITCH_LIMIT, PITCH_LIMIT);
        }
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    @Override
    public void update(float tpf) {
        if (!app.getInputManager().isCursorVisible()) {
            applyMovement(tpf);
        }
        applyGravity(tpf);
        applyCamera();
    }

    private void applyMovement(float tpf) {
        if (!fwd && !back && !strafeL && !strafeR) return;

        float sinYaw = FastMath.sin(yaw);
        float cosYaw = FastMath.cos(yaw);

        // Forward direction — horizontal only, ignores pitch so the player
        // walks flat across the terrain rather than diving into the ground
        // when looking down.
        Vector3f forward = new Vector3f(sinYaw,  0f, -cosYaw);
        Vector3f strafe  = new Vector3f(cosYaw,  0f,  sinYaw);

        Vector3f move = new Vector3f();
        if (fwd)     move.addLocal(forward);
        if (back)    move.subtractLocal(forward);
        if (strafeR) move.addLocal(strafe);
        if (strafeL) move.subtractLocal(strafe);

        if (move.lengthSquared() > 0) {
            move.normalizeLocal().multLocal(MOVE_SPEED * tpf);
            position.addLocal(move);
        }
    }

    private void applyGravity(float tpf) {
        float groundY = sampleHeight(position.x, position.z) + EYE_HEIGHT;

        verticalVelocity -= GRAVITY * tpf;
        position.y       += verticalVelocity * tpf;

        if (position.y <= groundY) {
            position.y       = groundY;
            verticalVelocity = 0f;
            onGround         = true;
        } else {
            onGround = false;
        }
    }

    private void applyCamera() {
        cam.setLocation(position);
        float cosPitch = FastMath.cos(pitch);
        float sinPitch = FastMath.sin(pitch);
        cam.lookAtDirection(
                new Vector3f(FastMath.sin(yaw) * cosPitch, sinPitch, -FastMath.cos(yaw) * cosPitch),
                Vector3f.UNIT_Y);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private float sampleHeight(float x, float z) {
        float h = terrain.getHeight(new Vector2f(x, z));
        return Float.isNaN(h) ? 0f : h;
    }
}
