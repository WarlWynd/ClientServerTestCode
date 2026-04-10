package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Procedural stick-figure sprite animator.
 *
 * All artwork is generated programmatically using JavaFX canvas primitives —
 * no external image files required. The style matches the bold black-silhouette
 * pictogram reference: round head, thick limbs, expressive combat poses.
 *
 * Usage:
 *   animator.update(velX, velY, onGround);   // call each tick to drive state
 *   animator.draw(gc, worldX, canvasY, color); // call in render loop
 *
 * Coordinate system for pose data:
 *   - Origin (0,0) = feet centre
 *   - X: positive = right (mirrored automatically when facing left)
 *   - Y: positive = UP  (negated on draw to convert to canvas-down space)
 *
 * Pose array layout (30 values = 15 joints × 2 coords):
 *   [0,1]   head centre        [2,3]   neck
 *   [4,5]   hip (spine base)
 *   [6,7]   left shoulder      [8,9]   left elbow     [10,11] left hand
 *   [12,13] right shoulder     [14,15] right elbow    [16,17] right hand
 *   [18,19] left hip-joint     [20,21] left knee      [22,23] left foot
 *   [24,25] right hip-joint    [26,27] right knee     [28,29] right foot
 */
public class PlayerAnimator {

    public enum State { IDLE, RUN, JUMP, FALL, GOTHIT01, GOTHIT02, GOTHIT03, STAFF_IDLE, SWORD_1H_IDLE, SWORD_2H_IDLE, AXE_1H_IDLE, AXE_2H_IDLE, DAGGER_IDLE, MORNING_STAR_IDLE, BOW_IDLE, KNOCKED_DOWN, CROUCH, SNEAK, CLIMB, PRONE, ROLL, SWIM, PUNCH, CROSS, HOOK, UPPERCUT, HAYMAKER, HEAD_KICK, LOW_KICK, BODY_KICK, SPINNING_BACK_KICK, SIDE_KICK, SHOOT, KIP_UP, FRONT_FLIP, CRAWL }

    /**
     * Viewing direction for a pose.
     * RIGHT  — character faces right (default; base pose arrays).
     * LEFT   — character faces left  (mirror of RIGHT unless an override exists).
     * FRONT  — character faces the camera (used for idle stances).
     * BACK   — character's back to camera (used for CLIMB).
     */
    public enum Direction { FRONT, LEFT, RIGHT, BACK }

    // ── Directional pose overrides ────────────────────────────────────────────
    // Key format: "STATE_DIRECTION" e.g. "IDLE_FRONT", "RUN_LEFT".
    // Populated from dir_poses.json at startup and updated by SpriteEditorPanel.
    private static final String DIR_POSES_FILE =
            "client/src/main/resources/graphics/sprites/dir_poses.json";
    private static final ConcurrentHashMap<String, double[][]> DIR_POSES = loadDirPoses();

    private static ConcurrentHashMap<String, double[][]> loadDirPoses() {
        ConcurrentHashMap<String, double[][]> map = new ConcurrentHashMap<>();
        try {
            Path p = Paths.get(DIR_POSES_FILE);
            if (!Files.exists(p)) return map;
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(p.toFile());
            root.fields().forEachRemaining(entry -> {
                JsonNode frames = entry.getValue();
                double[][] arr = new double[frames.size()][];
                for (int f = 0; f < frames.size(); f++) {
                    JsonNode fr = frames.get(f);
                    double[] pose = new double[fr.size()];
                    for (int j = 0; j < fr.size(); j++) pose[j] = fr.get(j).asDouble();
                    arr[f] = pose;
                }
                map.put(entry.getKey(), arr);
            });
        } catch (Exception ignored) {}
        return map;
    }

    /** Persist current DIR_POSES to disk. Called by SpriteEditorPanel after editing. */
    public static void saveDirPoses() {
        try {
            Path p = Paths.get(DIR_POSES_FILE);
            Files.createDirectories(p.getParent());
            ObjectMapper om = new ObjectMapper();
            ObjectNode root = om.createObjectNode();
            DIR_POSES.forEach((key, frames) -> {
                ArrayNode framesNode = om.createArrayNode();
                for (double[] frame : frames) {
                    ArrayNode fn = om.createArrayNode();
                    for (double v : frame) fn.add(v);
                    framesNode.add(fn);
                }
                root.set(key, framesNode);
            });
            om.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), root);
        } catch (Exception ignored) {}
    }

    /** Store a directional pose override. */
    public static void setDirectionalPoses(State s, Direction d, double[][] frames) {
        if (frames == null || frames.length == 0)
            DIR_POSES.remove(s.name() + "_" + d.name());
        else
            DIR_POSES.put(s.name() + "_" + d.name(), frames);
    }

    /** Retrieve a directional pose override, or null if none set. */
    public static double[][] getDirectionalPoses(State s, Direction d) {
        return DIR_POSES.get(s.name() + "_" + d.name());
    }

    /** True if a directional override exists for this state+direction. */
    public static boolean hasDirectionalPoses(State s, Direction d) {
        return DIR_POSES.containsKey(s.name() + "_" + d.name());
    }

    // ── Timing (ms per frame) ─────────────────────────────────────────────────
    private static final long IDLE_MS      = 650;
    private static final long RUN_MS       = 105;
    private static final long GOTHIT01_MS  = 140;
    private static final long GOTHIT02_MS  = 140;
    private static final long GOTHIT03_MS  = 140;
    private static final long KNOCKED_MS   = 80;
    private static final long ATTACK_MS    = 70;
    private static final long OTHER_MS     = 180;

    // ── Drawing constants ─────────────────────────────────────────────────────
    private static final double LINE_W      = 5.0;
    private static final double HEAD_R      = 8.0;

    // ── Image sprite dirs ─────────────────────────────────────────────────────
    private static final String  SPRITES_DIR = "client/src/main/resources/graphics/sprites/";

    /** Returns {topRow, bottomRow} of first/last rows containing non-transparent pixels. */
    private static int[] contentBoundsOf(Image img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        int[] pixels = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        int top = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((pixels[y * w + x] >> 24) & 0xFF) > 12) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        return new int[]{top < h ? top : 0, bottom >= 0 ? bottom : h - 1};
    }

    private static final java.util.WeakHashMap<Image, java.util.concurrent.ConcurrentHashMap<Integer, Image>>
            TINT_CACHE = new java.util.WeakHashMap<>();

    private static Image tintSprite(Image src, Color tint) {
        int colorKey = ((int)(tint.getRed()   * 255) << 16)
                     | ((int)(tint.getGreen() * 255) <<  8)
                     |  (int)(tint.getBlue()  * 255);
        java.util.concurrent.ConcurrentHashMap<Integer, Image> map;
        synchronized (TINT_CACHE) {
            map = TINT_CACHE.computeIfAbsent(src, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        return map.computeIfAbsent(colorKey, k -> {
            int w = (int) src.getWidth(), h = (int) src.getHeight();
            WritableImage out = new WritableImage(w, h);
            int[] pixels = new int[w * h];
            src.getPixelReader().getPixels(0, 0, w, h,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
            int tr = (colorKey >> 16) & 0xFF, tg = (colorKey >> 8) & 0xFF, tb = colorKey & 0xFF;
            for (int i = 0; i < pixels.length; i++) {
                int alpha = (pixels[i] >> 24) & 0xFF;
                if (alpha > 12) pixels[i] = (alpha << 24) | (tr << 16) | (tg << 8) | tb;
            }
            out.getPixelWriter().setPixels(0, 0, w, h,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
            return out;
        });
    }

    /**
     * Strips white/near-white background and removes the floor-line bar
     * (the horizontal dark band that appears at the bottom of pose sprites).
     * Preserves original image dimensions so aspect-ratio scaling stays consistent
     * across all frames.
     */
    private static Image removeWhiteBg(Image src) {
        int w = (int) src.getWidth(), h = (int) src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixelReader().getPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);

        // 1. Strip white / near-white background
        for (int i = 0; i < pixels.length; i++) {
            int r = (pixels[i] >> 16) & 0xFF;
            int g = (pixels[i] >>  8) & 0xFF;
            int b =  pixels[i]        & 0xFF;
            if (r > 210 && g > 210 && b > 210) pixels[i] = 0;
        }

        // 2. Strip floor-line rows from the bottom.
        //    A floor-line row has >35% non-transparent pixels spanning a wide horizontal band.
        for (int y = h - 1; y >= Math.max(0, h - 20); y--) {
            int dark = 0;
            for (int x = 0; x < w; x++) {
                if (((pixels[y * w + x] >> 24) & 0xFF) > 12) dark++;
            }
            if ((double) dark / w > 0.80) {
                for (int x = 0; x < w; x++) pixels[y * w + x] = 0;
            } else {
                break; // stop as soon as a non-floor row is found
            }
        }

        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return out;
    }

    // ── KNOCKED_DOWN image sprites (optional — kd0001.png … kd0007.png) ──────
    private static final int     KD_FRAME_COUNT  = 7;
    private static final Image[] KD_SPRITES       = loadKdSprites();

    private static Image[] loadKdSprites() {
        Image[] imgs = new Image[KD_FRAME_COUNT];
        for (int i = 1; i <= KD_FRAME_COUNT; i++) {
            File f = new File(SPRITES_DIR + String.format("kd%04d.png", i));
            if (f.exists()) {
                try { imgs[i - 1] = removeWhiteBg(new Image(f.toURI().toString())); }
                catch (Exception ignored) {}
            }
        }
        return imgs;
    }

    public static boolean hasKdSprites() {
        for (Image img : KD_SPRITES) if (img != null) return true;
        return false;
    }

    private static Image kdSpriteForFrame(int frame, int totalFrames) {
        int idx = (int) Math.round(frame * (KD_FRAME_COUNT - 1.0) / Math.max(totalFrames - 1, 1));
        return KD_SPRITES[Math.min(idx, KD_FRAME_COUNT - 1)];
    }

    // ── One-shot state flags ──────────────────────────────────────────────────
    // States in this set play through once and hold on the last frame.
    private static final String STATE_FLAGS_FILE =
            "client/src/main/resources/graphics/sprites/state_flags.json";
    private static final java.util.Set<State> ONE_SHOT_STATES = loadStateFlags();

    private static java.util.Set<State> loadStateFlags() {
        java.util.Set<State> set = new java.util.concurrent.ConcurrentSkipListSet<>();
        // Defaults: these states are inherently one-shot
        set.add(State.GOTHIT01); set.add(State.GOTHIT02); set.add(State.GOTHIT03);
        set.add(State.KNOCKED_DOWN); set.add(State.ROLL); set.add(State.KIP_UP);
        set.add(State.FRONT_FLIP);
        set.add(State.PUNCH); set.add(State.CROSS); set.add(State.HOOK);
        set.add(State.UPPERCUT); set.add(State.HAYMAKER);
        set.add(State.HEAD_KICK); set.add(State.LOW_KICK); set.add(State.BODY_KICK);
        set.add(State.SPINNING_BACK_KICK); set.add(State.SIDE_KICK); set.add(State.SHOOT);
        try {
            Path p = Paths.get(STATE_FLAGS_FILE);
            if (Files.exists(p)) {
                ObjectMapper om = new ObjectMapper();
                JsonNode root = om.readTree(p.toFile());
                // "oneShot" array in JSON overrides defaults
                if (root.has("oneShot")) {
                    set.clear();
                    root.get("oneShot").forEach(n -> {
                        try { set.add(State.valueOf(n.asText())); }
                        catch (IllegalArgumentException ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
        return set;
    }

    public static boolean isOneShot(State s) { return ONE_SHOT_STATES.contains(s); }

    public static void setOneShot(State s, boolean v) {
        if (v) ONE_SHOT_STATES.add(s); else ONE_SHOT_STATES.remove(s);
    }

    public static void saveStateFlags() {
        try {
            Path p = Paths.get(STATE_FLAGS_FILE);
            Files.createDirectories(p.getParent());
            ObjectMapper om = new ObjectMapper();
            ObjectNode root = om.createObjectNode();
            ArrayNode arr = om.createArrayNode();
            ONE_SHOT_STATES.stream().map(State::name).sorted().forEach(arr::add);
            root.set("oneShot", arr);
            om.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), root);
        } catch (Exception ignored) {}
    }

    /** Canvas units above the feet position to draw the name label. */
    public  static final double LABEL_ABOVE = 60.0;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile State   state         = State.IDLE;
    private volatile int     frame         = 0;
    private          long    lastFrameMs   = 0;
    private volatile boolean facingRight   = true;
    /** When true, animation holds on its last frame instead of looping. */
    private volatile boolean holdLastFrame = false;
    /** If non-null, overrides currentDirection() for preview/editor use. */
    private volatile Direction forcedDirection = null;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Update animation state from physics.
     * Call once per game tick (processInput / packet arrival).
     */
    public void update(float velX, float velY, boolean onGround) {
        if (velX >  0.3f) facingRight = true;
        if (velX < -0.3f) facingRight = false;

        State next = resolve(velX, velY, onGround);
        if (next != state) {
            state       = next;
            frame       = 0;
            lastFrameMs = System.currentTimeMillis();
        }
    }

    /**
     * Derive the current viewing direction from state + facing.
     * CLIMB → BACK; idle stances → FRONT (unless a FRONT override exists, same logic);
     * moving right → RIGHT; moving left → LEFT.
     */
    public void setForcedDirection(Direction dir) { this.forcedDirection = dir; }

    public Direction currentDirection() {
        if (state == State.CLIMB) return Direction.BACK; // always back-facing, ignores forced direction
        if (forcedDirection != null) return forcedDirection;
        // Left-facing characters always go LEFT (including idle) so they mirror toward their opponent.
        // Only right-facing characters use FRONT for idle stances (faces camera in solo/editor view).
        if (!facingRight) return Direction.LEFT;
        return Direction.RIGHT;
    }

    /**
     * True when the figure should be drawn without X-axis mirror.
     * LEFT direction mirrors ONLY if no LEFT-specific override exists.
     */
    public boolean shouldMirror() {
        Direction dir = currentDirection();
        if (dir == Direction.LEFT) return !hasDirectionalPoses(state, Direction.LEFT);
        return false;  // FRONT / RIGHT / BACK: never auto-mirror
    }

    /**
     * Whether the figure is facing right for rendering purposes.
     * Returns true during IDLE so the character always faces the viewer (forward).
     * Used by both draw() and WeaponRenderer.
     */
    public boolean isFacingRight() { return !shouldMirror(); }
    public void setFacingRight(boolean v) { facingRight = v; }

    /** When set, animation holds on the last frame instead of looping. Clear to resume. */
    public void setHoldLastFrame(boolean hold) { holdLastFrame = hold; }

    /**
     * Returns the weapon attachment joints from the current pose frame.
     * 6 values in pose space (Y-up): [1H-tip x,y | shield x,y | 2H-tip x,y].
     * All zeros when the current pose has no weapon joints defined.
     */
    public double[] getWeaponJoints() {
        double[] p = currentPose();
        if (p.length < 36) return new double[6];
        return new double[]{ p[30], p[31], p[32], p[33], p[34], p[35] };
    }

    /**
     * Returns the current pose frame converted to canvas space (Y negated).
     * Used by WeaponRenderer so weapon drawing has the same coordinate frame
     * as drawPose() without duplicating the negation logic.
     */
    public double[] getCurrentCanvasPose() {
        double[] raw = currentPose();
        double[] p   = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            p[i] = (i % 2 == 1) ? -raw[i] : raw[i];  // negate Y (odd indices)
        }
        return p;
    }

    /** Force into hit-stagger animation (future combat hook). */
    public void triggerHit() {
        state       = State.GOTHIT01;
        frame       = 0;
        lastFrameMs = System.currentTimeMillis();
    }

    /** Trigger a one-shot front-flip aerial rotation. */
    public void triggerFrontFlip() {
        state       = State.FRONT_FLIP;
        frame       = 0;
        lastFrameMs = System.currentTimeMillis();
    }

    /**
     * Returns the raw pose-frame array for a given state.
     * Used by SpriteEditorPanel to initialise the editable pose copy.
     */
    public static double[][] getFrames(State s) {
        return switch (s) {
            case IDLE         -> IDLE;
            case RUN          -> RUN;
            case JUMP         -> JUMP;
            case FALL         -> FALL;
            case GOTHIT01     -> GOTHIT01;
            case GOTHIT02     -> GOTHIT02;
            case GOTHIT03     -> GOTHIT03;
            case STAFF_IDLE    -> STAFF_IDLE;
            case SWORD_1H_IDLE -> SWORD_2H_IDLE;
            case SWORD_2H_IDLE -> SWORD_2H_IDLE;
            case AXE_1H_IDLE   -> AXE_2H_IDLE;
            case AXE_2H_IDLE   -> AXE_2H_IDLE;
            case DAGGER_IDLE        -> DAGGER_IDLE;
            case MORNING_STAR_IDLE  -> MORNING_STAR_IDLE;
            case BOW_IDLE           -> BOW_IDLE;
            case KNOCKED_DOWN       -> KNOCKED;
            case KIP_UP            -> KIP_UP;
            case FRONT_FLIP        -> FRONT_FLIP;
            case CRAWL             -> CRAWL;
            case CROUCH -> CROUCH;
            case SNEAK  -> SNEAK;
            case CLIMB  -> CLIMB;
            case PRONE  -> PRONE;
            case ROLL   -> ROLL;
            case SWIM   -> SWIM;
            case PUNCH           -> PUNCH;
            case CROSS           -> CROSS;
            case HOOK            -> HOOK;
            case UPPERCUT        -> UPPERCUT;
            case HAYMAKER        -> HAYMAKER;
            case HEAD_KICK       -> HEAD_KICK;
            case LOW_KICK        -> LOW_KICK;
            case BODY_KICK       -> BODY_KICK;
            case SPINNING_BACK_KICK -> SPINNING_BACK_KICK;
            case SIDE_KICK       -> SIDE_KICK;
            case SHOOT           -> SHOOT;
        };
    }

    /**
     * Pin the animator to a specific state without driving it from physics.
     * Used by the Graphics Dev sprite preview.
     */
    public State getCurrentState() { return state; }

    public void forceState(State s) {
        state         = s;
        frame         = 0;
        holdLastFrame = false;
        lastFrameMs   = System.currentTimeMillis();
    }

    /** forceState with explicit time reference — use when the caller drives animation with a custom nowMs. */
    public void forceState(State s, long nowMs) {
        state         = s;
        frame         = 0;
        holdLastFrame = false;
        lastFrameMs   = nowMs;
    }

    /** Returns true if this is a one-shot animation that has played through to its last frame. */
    public boolean isOneShotDone() {
        return isOneShot(state) && frame >= poseCount(state) - 1;
    }

    /**
     * Advance frame counter and draw the current pose.
     *
     * @param gc       Graphics context (transform already set to world coords)
     * @param cx       World X of the player's centre
     * @param canvasY  Canvas Y of the player's feet  (= toCanvasY(gameY))
     * @param color    Fill / stroke colour
     */
    public void draw(GraphicsContext gc, double cx, double canvasY, Color color) {
        draw(gc, cx, canvasY, color, 1.0, System.currentTimeMillis());
    }

    public void draw(GraphicsContext gc, double cx, double canvasY, Color color, double scale) {
        draw(gc, cx, canvasY, color, scale, System.currentTimeMillis());
    }

    /** Draw with explicit scale and controlled sim time (for pause/step support). */
    public void draw(GraphicsContext gc, double cx, double canvasY, Color color, double scale, long nowMs) {
        advanceFrame(nowMs);

        // ── Procedural path ───────────────────────────────────────────────────
        double[] pose = currentPose();

        gc.save();
        gc.translate(cx, canvasY);
        gc.scale(scale, scale);
        if (shouldMirror()) gc.scale(-1, 1);   // mirror for left-facing when no LEFT override exists

        gc.setFill(color);
        gc.setStroke(color);
        gc.setLineWidth(LINE_W);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);

        drawPose(gc, pose);
        gc.restore();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private State resolve(float velX, float velY, boolean onGround) {
        // Let hit/knocked animations play out before transitioning
        if ((state == State.GOTHIT01 || state == State.GOTHIT02 || state == State.GOTHIT03 || state == State.KNOCKED_DOWN || state == State.ROLL
                || state == State.KIP_UP || state == State.FRONT_FLIP
                || state == State.PUNCH || state == State.CROSS || state == State.HOOK
                || state == State.UPPERCUT || state == State.HAYMAKER
                || state == State.HEAD_KICK || state == State.LOW_KICK || state == State.BODY_KICK
                || state == State.SPINNING_BACK_KICK || state == State.SIDE_KICK
                || state == State.SHOOT)
                && frame < poseCount(state) - 1) {
            return state;
        }
        if (!onGround) return velY > 0.5f ? State.JUMP : State.FALL;
        if (Math.abs(velX) > 0.3f) return State.RUN;
        return State.IDLE;
    }

    private void advanceFrame(long now) {
        long interval = switch (state) {
            case IDLE         -> IDLE_MS;
            case RUN          -> RUN_MS;
            case SNEAK        -> RUN_MS;
            case GOTHIT01     -> GOTHIT01_MS;
            case GOTHIT02     -> GOTHIT02_MS;
            case GOTHIT03     -> GOTHIT03_MS;
            case KNOCKED_DOWN -> KNOCKED_MS;
            case KIP_UP       -> 40;
            case FRONT_FLIP   -> 70;
            case CRAWL        -> 120;
            case ROLL         -> KNOCKED_MS;
            case PUNCH, CROSS, HOOK, UPPERCUT, HAYMAKER,
                 HEAD_KICK, LOW_KICK, BODY_KICK, SPINNING_BACK_KICK, SIDE_KICK,
                 SHOOT -> ATTACK_MS;
            default           -> OTHER_MS;
        };
        if (now - lastFrameMs >= interval) {
            int next = frame + 1;
            int count = poseCount(state);
            if ((holdLastFrame || isOneShot(state)) && next >= count) {
                frame = count - 1;  // clamp to last frame — one-shot or externally held
            } else {
                frame = next % count;
            }
            lastFrameMs = now;
        }
    }

    private int poseCount(State s) {
        // Check directional override first
        double[][] dirOverride = DIR_POSES.get(s.name() + "_" + currentDirection().name());
        if (dirOverride != null && dirOverride.length > 0) return dirOverride.length;
        return switch (s) {
            case IDLE         -> IDLE.length;
            case RUN          -> RUN.length;
            case JUMP         -> JUMP.length;
            case FALL         -> FALL.length;
            case GOTHIT01     -> GOTHIT01.length;
            case GOTHIT02     -> GOTHIT02.length;
            case GOTHIT03     -> GOTHIT03.length;
            case STAFF_IDLE    -> STAFF_IDLE.length;
            case SWORD_1H_IDLE -> SWORD_2H_IDLE.length;
            case SWORD_2H_IDLE -> SWORD_2H_IDLE.length;
            case AXE_1H_IDLE   -> AXE_2H_IDLE.length;
            case AXE_2H_IDLE   -> AXE_2H_IDLE.length;
            case DAGGER_IDLE        -> DAGGER_IDLE.length;
            case MORNING_STAR_IDLE  -> MORNING_STAR_IDLE.length;
            case BOW_IDLE           -> BOW_IDLE.length;
            case KNOCKED_DOWN       -> KNOCKED.length;
            case KIP_UP            -> KIP_UP.length;
            case FRONT_FLIP        -> FRONT_FLIP.length;
            case CRAWL             -> CRAWL.length;
            case CROUCH -> CROUCH.length;
            case SNEAK  -> SNEAK.length;
            case CLIMB  -> CLIMB.length;
            case PRONE  -> PRONE.length;
            case ROLL   -> ROLL.length;
            case SWIM   -> SWIM.length;
            case PUNCH           -> PUNCH.length;
            case CROSS           -> CROSS.length;
            case HOOK            -> HOOK.length;
            case UPPERCUT        -> UPPERCUT.length;
            case HAYMAKER        -> HAYMAKER.length;
            case HEAD_KICK       -> HEAD_KICK.length;
            case LOW_KICK        -> LOW_KICK.length;
            case BODY_KICK       -> BODY_KICK.length;
            case SPINNING_BACK_KICK -> SPINNING_BACK_KICK.length;
            case SIDE_KICK       -> SIDE_KICK.length;
            case SHOOT  -> SHOOT.length;
        };
    }

    private double[] currentPose() {
        // Check directional override first
        Direction dir = currentDirection();
        double[][] dirOverride = DIR_POSES.get(state.name() + "_" + dir.name());
        if (dirOverride != null && dirOverride.length > 0)
            return dirOverride[frame % dirOverride.length];
        int f = frame % poseCount(state);
        return switch (state) {
            case IDLE         -> IDLE[f];
            case RUN          -> RUN[f];
            case JUMP         -> JUMP[f];
            case FALL         -> FALL[f];
            case GOTHIT01     -> GOTHIT01[f];
            case GOTHIT02     -> GOTHIT02[f];
            case GOTHIT03     -> GOTHIT03[f];
            case STAFF_IDLE    -> STAFF_IDLE[f];
            case SWORD_1H_IDLE -> SWORD_2H_IDLE[f];
            case SWORD_2H_IDLE -> SWORD_2H_IDLE[f];
            case AXE_1H_IDLE   -> AXE_2H_IDLE[f];
            case AXE_2H_IDLE   -> AXE_2H_IDLE[f];
            case DAGGER_IDLE        -> DAGGER_IDLE[f];
            case MORNING_STAR_IDLE  -> MORNING_STAR_IDLE[f];
            case BOW_IDLE           -> BOW_IDLE[f];
            case KNOCKED_DOWN       -> KNOCKED[f];
            case KIP_UP            -> KIP_UP[frame % KIP_UP.length];
            case FRONT_FLIP        -> FRONT_FLIP[f];
            case CRAWL             -> CRAWL[f];
            case CROUCH -> CROUCH[f];
            case SNEAK  -> SNEAK[f];
            case CLIMB  -> CLIMB[f];
            case PRONE  -> PRONE[f];
            case ROLL   -> ROLL[f];
            case SWIM   -> SWIM[f];
            case PUNCH           -> PUNCH[f];
            case CROSS           -> CROSS[f];
            case HOOK            -> HOOK[f];
            case UPPERCUT        -> UPPERCUT[f];
            case HAYMAKER        -> HAYMAKER[f];
            case HEAD_KICK       -> HEAD_KICK[f];
            case LOW_KICK        -> LOW_KICK[f];
            case BODY_KICK       -> BODY_KICK[f];
            case SPINNING_BACK_KICK -> SPINNING_BACK_KICK[f];
            case SIDE_KICK       -> SIDE_KICK[f];
            case SHOOT  -> SHOOT[f];
        };
    }

    /** Draw one pose frame. Assumes gc is already translated to (cx, canvasY). */
    private static void drawPose(GraphicsContext gc, double[] p) {
        // Unpack joints — negate Y because pose Y is up, canvas Y is down
        double hx   = p[0],  hy   = -p[1];   // head centre
        double nkx  = p[2],  nky  = -p[3];   // neck
        double hpx  = p[4],  hpy  = -p[5];   // hip (spine base)
        double lsx  = p[6],  lsy  = -p[7];   // left shoulder
        double lex  = p[8],  ley  = -p[9];   // left elbow
        double lhx  = p[10], lhy  = -p[11];  // left hand
        double rsx  = p[12], rsy  = -p[13];  // right shoulder
        double rex  = p[14], rey  = -p[15];  // right elbow
        double rhx  = p[16], rhy  = -p[17];  // right hand
        double llhx = p[18], llhy = -p[19];  // left leg hip
        double lkx  = p[20], lky  = -p[21];  // left knee
        double lfx  = p[22], lfy  = -p[23];  // left foot
        double rlhx = p[24], rlhy = -p[25];  // right leg hip
        double rkx  = p[26], rky  = -p[27];  // right knee
        double rfx  = p[28], rfy  = -p[29];  // right foot

        // Head
        gc.fillOval(hx - HEAD_R, hy - HEAD_R, HEAD_R * 2, HEAD_R * 2);

        // Spine
        gc.strokeLine(nkx, nky, hpx, hpy);

        // Left arm:  shoulder → elbow → hand
        gc.strokeLine(lsx, lsy, lex, ley);
        gc.strokeLine(lex, ley, lhx, lhy);

        // Right arm: shoulder → elbow → hand
        gc.strokeLine(rsx, rsy, rex, rey);
        gc.strokeLine(rex, rey, rhx, rhy);

        // Left leg:  hip → knee → foot
        gc.strokeLine(llhx, llhy, lkx, lky);
        gc.strokeLine(lkx,  lky,  lfx, lfy);

        // Right leg: hip → knee → foot
        gc.strokeLine(rlhx, rlhy, rkx, rky);
        gc.strokeLine(rkx,  rky,  rfx, rfy);
    }

    // ── Pose data ─────────────────────────────────────────────────────────────
    // All Y values are positive = UP from feet.
    // Figure is ~55 units tall at rest (head top ≈ 55, feet = 0).
    // Defined facing RIGHT; mirrored automatically when facing left.

    private static final double[][] IDLE = {
        // 4-frame slow breathing cycle — upright natural stance, arms at sides.
        // Head slightly forward (character looks right). Front arm has natural drape,
        // back arm close to body. Right foot slightly ahead of left.

        // Frame 0 — exhale, low
        {  3,47,  2,38,  0,23,
          -4,35,  -5,26,  -4,17,
           5,35,   9,25,   8,15,
          -3,22,  -4,11,  -5, 0,
           3,22,   5,11,   6, 0 },
        // Frame 1 — mid inhale
        {  3,48,  2,39,  0,23,
          -4,36,  -5,27,  -4,18,
           5,36,   9,26,   8,16,
          -3,22,  -4,11,  -5, 0,
           3,22,   5,11,   6, 0 },
        // Frame 2 — full inhale, high
        {  3,49,  2,40,  0,24,
          -4,36,  -5,27,  -4,18,
           5,36,   9,26,   8,16,
          -3,23,  -4,12,  -5, 0,
           3,23,   5,12,   6, 0 },
        // Frame 3 — exhale returning
        {  3,48,  2,39,  0,23,
          -4,35,  -5,26,  -4,17,
           5,35,   9,25,   8,15,
          -3,22,  -4,11,  -5, 0,
           3,22,   5,11,   6, 0 }
    };

    private static final double[][] RUN = {
        // 8-frame side-run cycle (facing right = direction of travel).
        // Arms oppose legs: right leg forward → left arm forward, and vice versa.
        // Strong forward lean, high knee lift, extended trailing leg to match reference.

        // Frame 0 — RIGHT contact: right heel strikes forward, left arm punches high
        {  6,44,  4,37,  2,22,
          -1,35,  5,32, 14,30,
           3,35,  0,27, -8,18,
          -2,21, -8,12,-14, 2,
           2,21, 10,16, 18, 2 },

        // Frame 1 — RIGHT loading: body sinks over right foot, left knee drives forward
        {  5,43,  3,36,  1,21,
          -1,34,  2,30,  6,26,
           3,34,  2,27, -2,20,
          -2,20,  2,20,  4,12,
           2,20,  4,10,  6, 0 },

        // Frame 2 — RIGHT passing: left knee lifts high, right arm swings forward-up
        {  6,44,  4,37,  2,22,
          -1,35,  0,27, -6,18,
           3,35,  6,32, 14,30,
          -2,21,  6,28,  4,20,
           2,21,  2,10,  4, 0 },

        // Frame 3 — RIGHT push-off: left knee at peak, right toe driving off
        {  6,44,  4,37,  2,22,
          -1,35, -2,29, -8,20,
           3,35,  6,33, 14,32,
           0,21,  8,28,  6,20,
          -2,21,  0, 9, -2, 0 },

        // Frame 4 — FLOAT / peak: both feet off ground, max extension
        {  6,46,  4,39,  2,24,
          -1,37, -6,31,-12,23,
           3,37,  6,34, 14,32,
          -2,23, 10,20, 16, 8,
           2,23, -5,13,-14, 4 },

        // Frame 5 — LEFT contact: left heel strikes forward, right arm punches high
        {  6,44,  4,37,  2,22,
          -1,35,  0,27, -8,18,
           3,35,  5,32, 14,30,
          -2,21, 10,16, 18, 2,
           2,21, -8,12,-14, 2 },

        // Frame 6 — LEFT loading: body sinks over left foot, right knee drives forward
        {  5,43,  3,36,  1,21,
          -1,34,  2,27, -2,20,
           3,34,  2,30,  6,26,
          -2,20,  4,10,  6, 0,
           2,20,  2,20,  4,12 },

        // Frame 7 — LEFT push-off: right knee at peak, left toe driving off
        {  6,44,  4,37,  2,22,
          -1,35,  6,33, 14,32,
           3,35, -2,29, -8,20,
          -2,21,  0, 9, -2, 0,
           0,21,  8,28,  6,20 },

        // Frames 8-15 — second cycle
        {  6,44,  4,37,  2,22,
          -1,35,  5,32, 14,30,
           3,35,  0,27, -8,18,
          -2,21, -8,12,-14, 2,
           2,21, 10,16, 18, 2 },
        {  5,43,  3,36,  1,21,
          -1,34,  2,30,  6,26,
           3,34,  2,27, -2,20,
          -2,20,  2,20,  4,12,
           2,20,  4,10,  6, 0 },
        {  6,44,  4,37,  2,22,
          -1,35,  0,27, -6,18,
           3,35,  6,32, 14,30,
          -2,21,  6,28,  4,20,
           2,21,  2,10,  4, 0 },
        {  6,44,  4,37,  2,22,
          -1,35, -2,29, -8,20,
           3,35,  6,33, 14,32,
           0,21,  8,28,  6,20,
          -2,21,  0, 9, -2, 0 },
        {  6,46,  4,39,  2,24,
          -1,37, -6,31,-12,23,
           3,37,  6,34, 14,32,
          -2,23, 10,20, 16, 8,
           2,23, -5,13,-14, 4 },
        {  6,44,  4,37,  2,22,
          -1,35,  0,27, -8,18,
           3,35,  5,32, 14,30,
          -2,21, 10,16, 18, 2,
           2,21, -8,12,-14, 2 },
        {  5,43,  3,36,  1,21,
          -1,34,  2,27, -2,20,
           3,34,  2,30,  6,26,
          -2,20,  4,10,  6, 0,
           2,20,  2,20,  4,12 },
        {  6,44,  4,37,  2,22,
          -1,35,  6,33, 14,32,
           3,35, -2,29, -8,20,
          -2,21,  0, 9, -2, 0,
           0,21,  8,28,  6,20 },

        // Frames 16-23 — third cycle
        {  6,44,  4,37,  2,22,
          -1,35,  5,32, 14,30,
           3,35,  0,27, -8,18,
          -2,21, -8,12,-14, 2,
           2,21, 10,16, 18, 2 },
        {  5,43,  3,36,  1,21,
          -1,34,  2,30,  6,26,
           3,34,  2,27, -2,20,
          -2,20,  2,20,  4,12,
           2,20,  4,10,  6, 0 },
        {  6,44,  4,37,  2,22,
          -1,35,  0,27, -6,18,
           3,35,  6,32, 14,30,
          -2,21,  6,28,  4,20,
           2,21,  2,10,  4, 0 },
        {  6,44,  4,37,  2,22,
          -1,35, -2,29, -8,20,
           3,35,  6,33, 14,32,
           0,21,  8,28,  6,20,
          -2,21,  0, 9, -2, 0 },
        {  6,46,  4,39,  2,24,
          -1,37, -6,31,-12,23,
           3,37,  6,34, 14,32,
          -2,23, 10,20, 16, 8,
           2,23, -5,13,-14, 4 },
        {  6,44,  4,37,  2,22,
          -1,35,  0,27, -8,18,
           3,35,  5,32, 14,30,
          -2,21, 10,16, 18, 2,
           2,21, -8,12,-14, 2 },
        {  5,43,  3,36,  1,21,
          -1,34,  2,27, -2,20,
           3,34,  2,30,  6,26,
          -2,20,  4,10,  6, 0,
           2,20,  2,20,  4,12 },
        {  6,44,  4,37,  2,22,
          -1,35,  6,33, 14,32,
           3,35, -2,29, -8,20,
          -2,21,  0, 9, -2, 0,
           0,21,  8,28,  6,20 }
    };

    private static final double[][] JUMP = {
        // Frame 0 — launch: arms thrust up, legs push
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        // Frame 1 — peak: legs tuck, arms spread
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        // Frames 2-7 — repeat cycle
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        // Frames 8-19 — continue alternating cycle
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 },
        {  0,47,  0,38,  0,24,
          -4,37, -14,44, -18,50,
           4,37,  14,44,  18,50,
          -3,22,  -8,15,  -6, 6,
           3,22,   8,15,   6, 6 },
        {  0,47,  0,38,  0,26,
          -4,37, -16,46, -20,52,
           4,37,  16,46,  20,52,
          -4,24, -10,16,  -8, 8,
           4,24,  10,16,   8, 8 }
    };

    private static final double[][] FALL = {
        // Arms wide, legs trailing — repeated ×8
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        // Frames 8-19 — continue
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 },
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 }
    };

    private static final double[][] GOTHIT01 = {
        // Frame 0 — staggered backward
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        // Frame 1 — stumble deeper
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        // Frames 2-7 — repeat cycle
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        // Frames 8-19 — continue alternating cycle
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 },
        { -4,45, -2,37,  0,22,
          -3,35, -12,28, -14,20,
           4,35,  12,38,  16,44,
          -2,21,  -4,10,  -5, 0,
           3,21,   8,11,  10, 0 },
        { -6,44, -3,36, -1,21,
          -4,34, -14,26, -16,18,
           3,34,  10,36,  14,42,
          -3,20,  -3, 9,  -2, 0,
           2,20,  10, 9,  12, 0 }
    };

    private static final double[][] GOTHIT02 = {
        // Gut/body hit — doubles over forward, arms clutch stomach.
        // Frame A — sharp forward fold on impact
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        // Frame B — sustain, slightly less extreme
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  8,40,  5,33,  1,21,
           0,32,  2,26,  4,22,
           8,31,  6,25,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 },
        {  6,41,  4,33,  0,21,
          -1,33,  1,27,  3,23,
           7,32,  5,26,  3,22,
          -3,20, -4,10, -5, 0,
           3,20,  5,10,  6, 0 }
    };

    private static final double[][] GOTHIT03 = {
        // Head hit — head snaps backward, body rocks back, arms flung wide.
        // Frame A — sharp backward snap on impact
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        // Frame B — settle slightly
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -8,46, -4,38, -1,22,
          -6,36,-10,30,-12,22,
           4,36, 10,31, 14,24,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 },
        { -6,45, -3,37, -1,22,
          -5,36, -9,29,-11,21,
           4,36,  9,30, 13,23,
          -3,21, -3,11, -4, 0,
           2,21,  5,10,  6, 0 }
    };

    private static final double[][] STAFF_IDLE = {
        // Frames 0-7: bo staff twirl — upright stance, legs together, staff spins 360° (45°/frame).
        // Right hand grips centre of staff at (8,26). Both tips rotate around that point.
        // Weapon joints: [30,31]=trailing tip, [34,35]=leading tip.

        // Frame 0 — staff vertical, tip up (θ=90°)
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        // Frame 1 — staff tilting forward-up (θ=45°)
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
          -5,13,  0, 0, 21,39 },
        // Frame 2 — staff horizontal, tip forward (θ=0°)
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
         -11,26,  0, 0, 27,26 },
        // Frame 3 — staff tilting forward-down (θ=315°)
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
          -5,39,  0, 0, 21,13 },
        // Frame 4 — staff vertical, tip down (θ=270°)
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8,45,  0, 0,  8, 7 },
        // Frame 5 — staff tilting back-down (θ=225°)
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
          21,39,  0, 0, -5,13 },
        // Frame 6 — staff horizontal, tip back (θ=180°)
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
          27,26,  0, 0,-11,26 },
        // Frame 7 — staff tilting back-up (θ=135°), completing the spin
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
          21,13,  0, 0, -5,39 },

        // Frames 8-19: staff held vertical at rest, gentle idle breathing (12 frames)
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,47,  0,38,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 },
        {  0,48,  0,39,  0,23,
          -4,36, -5,28, -6,20,
           4,36,  8,31,  8,26,
          -2,22, -1,11, -1, 0,
           2,22,  1,11,  1, 0,
           8, 7,  0, 0,  8,45 }
    };

    private static final double[][] MORNING_STAR_IDLE = {
        // Frame 0 — relaxed upright stance; right arm at side holding handle, ball hanging down
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        // Frame 1 — slight weight shift, ball sways gently
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        // Frames 2-7 — repeat cycle
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        // Frames 8-19 — continue alternating cycle
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          10, 4,  0, 0,  0, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -8,27, -7,18,
           4,36,  8,27, 10,18,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0,
          12, 5,  0, 0,  0, 0 }
    };

    private static final double[][] DAGGER_IDLE = {
        // Frame 0 — crouching forward lunge; overhand grip, blade pointing down at ground
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        // Frame 1 — slight crouch variation
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        // Frames 2-7 — repeat cycle
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        // Frames 8-19 — continue alternating cycle
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 },
        {  4,42,  3,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 14,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          16, 2,  0, 0,  0, 0 },
        {  3,42,  2,35,  0,20,
          -2,32, -6,26, -4,18,
           5,32, 10,24, 13,12,
          -2,19, -8,10,-12, 0,
           4,19, 10,10, 16, 0,
          15, 2,  0, 0,  0, 0 }
    };

    private static final double[][] AXE_2H_IDLE = {
        // Frame 0 — relaxed upright stance; haft diagonal, axe head resting at ground
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        // Frame 1 — slight weight shift
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        // Frames 2-7 — repeat cycle
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        // Frames 8-19 — continue alternating cycle
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 },
        {  0,48,  0,39,  0,23,
          -4,36, -1,28,  2,22,
           4,36,  5,24,  4,12,
          -3,22, -5,11, -4, 0,
           3,22,  5,11,  4, 0 }
    };

    private static final double[][] SWORD_2H_IDLE = {
        // Combat guard stance — sword held in front at chest height, pointing forward-up.
        // SWORD_2H draws blade through both hands: blade extends 28 units past R hand,
        // pommel 10 units behind L hand. Both hands close together drive the sword angle.
        // L hand (lower grip / pommel side), R hand (upper grip / crossguard side).

        // Frame 0 — guard stance, exhale
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        // Frame 1 — guard stance, inhale (head rises slightly)
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        // Frames 2-19 — continue breathing cycle
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  1,31,  5,25,
           4,36,  8,32, 12,28,
          -3,22, -1,11,  2, 0,
           3,22,  4,10, -2, 0 }
    };

    private static final double[][] BOW_IDLE = {
        // Archer draw stance — left arm extended forward holding bow, right arm pulled to face.
        // BOW draws D-arc at L hand, string to R hand, arrow pointing forward from R hand.
        // Wide stable stance facing the target.

        // Frame 0 — full draw, exhale
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 1 — full draw, inhale (bow arm trembles +1)
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frames 2-19 — continue breathing / hold tremor
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,39,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 }
    };

    private static final double[][] CROUCH = initCrouch();
    private static double[][] initCrouch() { return new double[][] {
        // Deep squat — body at ~60% normal height, knees wide, arms resting.
        // 20 frames alternating two breathing poses.
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,33,  0,27,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 }
    }; }

    private static final double[][] SNEAK = initSneak();
    private static double[][] initSneak() { return new double[][] {
        // Hunched forward walk — 4-frame cycle × 5.
        // Body at ~60% height, strong forward lean, short shuffling steps.

        // Frame 0 — right contact, left arm forward
        {  5,34,  3,27,  0,14,
          -1,26,  4,22, 10,18,
           2,26,  0,20, -4,14,
          -2,13, -6, 7,-10, 0,
           2,13,  6, 9, 10, 2 },
        // Frame 1 — right loading, transition
        {  4,33,  2,26,  0,13,
          -1,25,  2,20,  5,16,
           2,25,  1,19,  0,14,
          -2,12, -2,10,  0, 4,
           2,12,  4, 7,  6, 0 },
        // Frame 2 — left contact, right arm forward
        {  5,34,  3,27,  0,14,
          -1,26,  0,20, -4,14,
           2,26,  4,22, 10,18,
          -2,13,  6, 9, 10, 2,
           2,13, -6, 7,-10, 0 },
        // Frame 3 — left loading, transition
        {  4,33,  2,26,  0,13,
          -1,25,  1,19,  0,14,
           2,25,  2,20,  5,16,
          -2,12,  4, 7,  6, 0,
           2,12, -2,10,  0, 4 },
        // Frames 4-19 — repeat cycle
        {  5,34,  3,27,  0,14,
          -1,26,  4,22, 10,18,
           2,26,  0,20, -4,14,
          -2,13, -6, 7,-10, 0,
           2,13,  6, 9, 10, 2 },
        {  4,33,  2,26,  0,13,
          -1,25,  2,20,  5,16,
           2,25,  1,19,  0,14,
          -2,12, -2,10,  0, 4,
           2,12,  4, 7,  6, 0 },
        {  5,34,  3,27,  0,14,
          -1,26,  0,20, -4,14,
           2,26,  4,22, 10,18,
          -2,13,  6, 9, 10, 2,
           2,13, -6, 7,-10, 0 },
        {  4,33,  2,26,  0,13,
          -1,25,  1,19,  0,14,
           2,25,  2,20,  5,16,
          -2,12,  4, 7,  6, 0,
           2,12, -2,10,  0, 4 },
        {  5,34,  3,27,  0,14,
          -1,26,  4,22, 10,18,
           2,26,  0,20, -4,14,
          -2,13, -6, 7,-10, 0,
           2,13,  6, 9, 10, 2 },
        {  4,33,  2,26,  0,13,
          -1,25,  2,20,  5,16,
           2,25,  1,19,  0,14,
          -2,12, -2,10,  0, 4,
           2,12,  4, 7,  6, 0 },
        {  5,34,  3,27,  0,14,
          -1,26,  0,20, -4,14,
           2,26,  4,22, 10,18,
          -2,13,  6, 9, 10, 2,
           2,13, -6, 7,-10, 0 },
        {  4,33,  2,26,  0,13,
          -1,25,  1,19,  0,14,
           2,25,  2,20,  5,16,
          -2,12,  4, 7,  6, 0,
           2,12, -2,10,  0, 4 },
        {  5,34,  3,27,  0,14,
          -1,26,  4,22, 10,18,
           2,26,  0,20, -4,14,
          -2,13, -6, 7,-10, 0,
           2,13,  6, 9, 10, 2 },
        {  4,33,  2,26,  0,13,
          -1,25,  2,20,  5,16,
           2,25,  1,19,  0,14,
          -2,12, -2,10,  0, 4,
           2,12,  4, 7,  6, 0 },
        {  5,34,  3,27,  0,14,
          -1,26,  0,20, -4,14,
           2,26,  4,22, 10,18,
          -2,13,  6, 9, 10, 2,
           2,13, -6, 7,-10, 0 },
        {  4,33,  2,26,  0,13,
          -1,25,  1,19,  0,14,
           2,25,  2,20,  5,16,
          -2,12,  4, 7,  6, 0,
           2,12, -2,10,  0, 4 },
        {  5,34,  3,27,  0,14,
          -1,26,  4,22, 10,18,
           2,26,  0,20, -4,14,
          -2,13, -6, 7,-10, 0,
           2,13,  6, 9, 10, 2 },
        {  4,33,  2,26,  0,13,
          -1,25,  2,20,  5,16,
           2,25,  1,19,  0,14,
          -2,12, -2,10,  0, 4,
           2,12,  4, 7,  6, 0 },
        {  5,34,  3,27,  0,14,
          -1,26,  0,20, -4,14,
           2,26,  4,22, 10,18,
          -2,13,  6, 9, 10, 2,
           2,13, -6, 7,-10, 0 },
        {  4,33,  2,26,  0,13,
          -1,25,  1,19,  0,14,
           2,25,  2,20,  5,16,
          -2,12,  4, 7,  6, 0,
           2,12, -2,10,  0, 4 }
    }; }

    private static final double[][] CLIMB = initClimb();
    private static double[][] initClimb() { return new double[][] {
        // Ladder climb — 4-frame cycle × 5. Arms alternate reaching up.
        // Body stays centered (facing right or left — mirrored automatically).

        // Frame 0 — left arm high, right arm low; left knee up
        {  0,48,  0,40,  0,25,
          -2,38, -4,46, -2,52,
           2,38,  4,32,  2,26,
          -2,23, -2,15, -2, 6,
           2,23,  2,11,  2, 2 },
        // Frame 1 — arms crossing mid
        {  0,47,  0,39,  0,24,
          -2,38, -4,41, -2,46,
           2,38,  4,38,  2,42,
          -2,22, -2,14, -2, 4,
           2,22,  2,13,  2, 3 },
        // Frame 2 — right arm high, left arm low; right knee up
        {  0,48,  0,40,  0,25,
          -2,38, -4,32, -2,26,
           2,38,  4,46,  2,52,
          -2,23, -2,11, -2, 2,
           2,23,  2,15,  2, 6 },
        // Frame 3 — arms crossing mid (mirror of frame 1)
        {  0,47,  0,39,  0,24,
          -2,38, -4,38, -2,42,
           2,38,  4,41,  2,46,
          -2,22, -2,13, -2, 3,
           2,22,  2,14,  2, 4 },
        // Frames 4-19 — repeat cycle
        {  0,48,  0,40,  0,25,
          -2,38, -4,46, -2,52,
           2,38,  4,32,  2,26,
          -2,23, -2,15, -2, 6,
           2,23,  2,11,  2, 2 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,41, -2,46,
           2,38,  4,38,  2,42,
          -2,22, -2,14, -2, 4,
           2,22,  2,13,  2, 3 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,32, -2,26,
           2,38,  4,46,  2,52,
          -2,23, -2,11, -2, 2,
           2,23,  2,15,  2, 6 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,38, -2,42,
           2,38,  4,41,  2,46,
          -2,22, -2,13, -2, 3,
           2,22,  2,14,  2, 4 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,46, -2,52,
           2,38,  4,32,  2,26,
          -2,23, -2,15, -2, 6,
           2,23,  2,11,  2, 2 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,41, -2,46,
           2,38,  4,38,  2,42,
          -2,22, -2,14, -2, 4,
           2,22,  2,13,  2, 3 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,32, -2,26,
           2,38,  4,46,  2,52,
          -2,23, -2,11, -2, 2,
           2,23,  2,15,  2, 6 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,38, -2,42,
           2,38,  4,41,  2,46,
          -2,22, -2,13, -2, 3,
           2,22,  2,14,  2, 4 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,46, -2,52,
           2,38,  4,32,  2,26,
          -2,23, -2,15, -2, 6,
           2,23,  2,11,  2, 2 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,41, -2,46,
           2,38,  4,38,  2,42,
          -2,22, -2,14, -2, 4,
           2,22,  2,13,  2, 3 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,32, -2,26,
           2,38,  4,46,  2,52,
          -2,23, -2,11, -2, 2,
           2,23,  2,15,  2, 6 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,38, -2,42,
           2,38,  4,41,  2,46,
          -2,22, -2,13, -2, 3,
           2,22,  2,14,  2, 4 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,46, -2,52,
           2,38,  4,32,  2,26,
          -2,23, -2,15, -2, 6,
           2,23,  2,11,  2, 2 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,41, -2,46,
           2,38,  4,38,  2,42,
          -2,22, -2,14, -2, 4,
           2,22,  2,13,  2, 3 },
        {  0,48,  0,40,  0,25,
          -2,38, -4,32, -2,26,
           2,38,  4,46,  2,52,
          -2,23, -2,11, -2, 2,
           2,23,  2,15,  2, 6 },
        {  0,47,  0,39,  0,24,
          -2,38, -4,38, -2,42,
           2,38,  4,41,  2,46,
          -2,22, -2,13, -2, 3,
           2,22,  2,14,  2, 4 }
    }; }

    private static final double[][] PRONE = initProne();
    private static double[][] initProne() { return new double[][] {
        // Ground crawl — body nearly horizontal (head at y~13, feet at y~0).
        // 4-frame alternating arm pull cycle × 5.

        // Frame 0 — right arm reaching forward
        { 18,13, 12,10,  4, 6,
           8,11,  4, 9,  0, 7,
          10,11, 16,12, 22,10,
           2, 5, -3, 3, -6, 0,
           4, 5, -1, 2, -2, 0 },
        // Frame 1 — arms mid-stroke
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        // Frame 2 — left arm reaching forward
        { 18,13, 12,10,  4, 6,
           8,11, 14,12, 20,10,
          10,11,  4, 9,  0, 7,
           2, 5, -1, 2, -2, 0,
           4, 5, -3, 3, -6, 0 },
        // Frame 3 — arms mid-stroke (same as frame 1)
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        // Frames 4-19 — repeat cycle
        { 18,13, 12,10,  4, 6,
           8,11,  4, 9,  0, 7,
          10,11, 16,12, 22,10,
           2, 5, -3, 3, -6, 0,
           4, 5, -1, 2, -2, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 14,12, 20,10,
          10,11,  4, 9,  0, 7,
           2, 5, -1, 2, -2, 0,
           4, 5, -3, 3, -6, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11,  4, 9,  0, 7,
          10,11, 16,12, 22,10,
           2, 5, -3, 3, -6, 0,
           4, 5, -1, 2, -2, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 14,12, 20,10,
          10,11,  4, 9,  0, 7,
           2, 5, -1, 2, -2, 0,
           4, 5, -3, 3, -6, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11,  4, 9,  0, 7,
          10,11, 16,12, 22,10,
           2, 5, -3, 3, -6, 0,
           4, 5, -1, 2, -2, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 14,12, 20,10,
          10,11,  4, 9,  0, 7,
           2, 5, -1, 2, -2, 0,
           4, 5, -3, 3, -6, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11,  4, 9,  0, 7,
          10,11, 16,12, 22,10,
           2, 5, -3, 3, -6, 0,
           4, 5, -1, 2, -2, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 14,12, 20,10,
          10,11,  4, 9,  0, 7,
           2, 5, -1, 2, -2, 0,
           4, 5, -3, 3, -6, 0 },
        { 18,13, 12,10,  4, 6,
           8,11, 12,12, 16,10,
          10,11,  6, 9,  2, 7,
           2, 5, -2, 3, -4, 0,
           4, 5, -2, 2, -4, 0 }
    }; }

    private static final double[][] ROLL = initRoll();
    private static double[][] initRoll() { return new double[][] {
        // Forward roll — one-shot (plays through like GOTHIT01).
        // Frames 0-7: dive, tuck, roll, recover. Frames 8-19: hold crouch.

        // Frame 0 — forward lean, about to dive
        {  6,44,  4,36,  2,22,
           0,34,  6,30, 12,24,
           3,34,  6,28,  8,20,
          -2,21, -4,12, -6, 2,
           2,21,  4,12,  6, 2 },
        // Frame 1 — body pitching forward hard
        { 10,38,  7,31,  2,20,
           2,32,  8,28, 14,22,
           4,32,  8,24, 10,16,
          -2,19, -4,10, -4, 2,
           2,19,  2,10,  4, 2 },
        // Frame 2 — airborne horizontal dive
        { 22,20, 16,16,  6,12,
          10,18,  6,16,  2,14,
          12,18, 18,18, 24,16,
           4,10, -2, 8, -6, 6,
           6,10,  0, 6, -4, 2 },
        // Frame 3 — tucked ball mid-air
        { 12,18,  8,14,  4,10,
           6,16,  2,12,  6, 8,
           8,16, 10,10, 14, 6,
           2, 8, 10, 6, 14, 4,
           4, 8, 12, 4, 16, 2 },
        // Frame 4 — ball descending, about to land
        { 10,14,  6,10,  2, 6,
           4,12,  0, 8,  4, 4,
           6,12,  8, 6, 12, 2,
           0, 4,  8, 2, 12, 0,
           2, 4, 10, 2, 14, 0 },
        // Frame 5 — one knee driving forward, coming out
        {  6,28,  3,22,  0,12,
          -2,22, -4,16, -2,10,
           2,22,  4,16,  2,10,
          -2,11,  2,20,  4,12,
           2,11,  4, 6,  6, 0 },
        // Frame 6 — landing in low crouch, arms bracing
        {  2,26,  1,20,  0,10,
          -4,20, -8,14, -6, 8,
           4,20,  8,14,  6, 8,
          -4, 9, -8, 4, -6, 0,
           4, 9,  8, 4,  6, 0 },
        // Frame 7 — settling into crouch
        {  0,30,  0,24,  0,12,
          -4,22, -8,16, -6,12,
           4,22,  8,16,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        // Frames 8-19 — hold crouch at end of roll
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 },
        {  0,32,  0,26,  0,12,
          -4,24, -8,18, -6,12,
           4,24,  8,18,  6,12,
          -4,11, -8, 6, -6, 0,
           4,11,  8, 6,  6, 0 }
    }; }

    private static final double[][] SWIM = initSwim();
    private static double[][] initSwim() { return new double[][] {
        // Freestyle swim stroke — 4-frame cycle × 5.
        // Body angled forward, alternating arm reach overhead then pull back.

        // Frame 0 — right arm reaching forward-up, left arm pulling back
        {  6,36,  4,30,  0,18,
          -2,30, -6,26,-10,20,
           2,30,  8,34, 14,36,
          -2,16, -4,10, -6, 4,
           2,16,  4,10,  6, 6 },
        // Frame 1 — arms mid-stroke
        {  5,35,  3,29,  0,17,
          -2,29, -4,24, -4,18,
           2,29,  4,28,  6,22,
          -2,15, -4, 9, -8, 4,
           2,15,  4, 9,  4, 2 },
        // Frame 2 — left arm reaching forward-up, right arm pulling back
        {  6,36,  4,30,  0,18,
          -2,30, -8,34,-14,36,
           2,30,  6,26, 10,20,
          -2,16, -4,10, -8, 6,
           2,16,  2,10,  4, 4 },
        // Frame 3 — arms mid-stroke (mirror of frame 1)
        {  5,35,  3,29,  0,17,
          -2,29, -4,28, -6,22,
           2,29,  4,24,  4,18,
          -2,15, -2, 9, -4, 2,
           2,15,  4, 9,  8, 4 },
        // Frames 4-19 — repeat cycle
        {  6,36,  4,30,  0,18,
          -2,30, -6,26,-10,20,
           2,30,  8,34, 14,36,
          -2,16, -4,10, -6, 4,
           2,16,  4,10,  6, 6 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,24, -4,18,
           2,29,  4,28,  6,22,
          -2,15, -4, 9, -8, 4,
           2,15,  4, 9,  4, 2 },
        {  6,36,  4,30,  0,18,
          -2,30, -8,34,-14,36,
           2,30,  6,26, 10,20,
          -2,16, -4,10, -8, 6,
           2,16,  2,10,  4, 4 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,28, -6,22,
           2,29,  4,24,  4,18,
          -2,15, -2, 9, -4, 2,
           2,15,  4, 9,  8, 4 },
        {  6,36,  4,30,  0,18,
          -2,30, -6,26,-10,20,
           2,30,  8,34, 14,36,
          -2,16, -4,10, -6, 4,
           2,16,  4,10,  6, 6 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,24, -4,18,
           2,29,  4,28,  6,22,
          -2,15, -4, 9, -8, 4,
           2,15,  4, 9,  4, 2 },
        {  6,36,  4,30,  0,18,
          -2,30, -8,34,-14,36,
           2,30,  6,26, 10,20,
          -2,16, -4,10, -8, 6,
           2,16,  2,10,  4, 4 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,28, -6,22,
           2,29,  4,24,  4,18,
          -2,15, -2, 9, -4, 2,
           2,15,  4, 9,  8, 4 },
        {  6,36,  4,30,  0,18,
          -2,30, -6,26,-10,20,
           2,30,  8,34, 14,36,
          -2,16, -4,10, -6, 4,
           2,16,  4,10,  6, 6 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,24, -4,18,
           2,29,  4,28,  6,22,
          -2,15, -4, 9, -8, 4,
           2,15,  4, 9,  4, 2 },
        {  6,36,  4,30,  0,18,
          -2,30, -8,34,-14,36,
           2,30,  6,26, 10,20,
          -2,16, -4,10, -8, 6,
           2,16,  2,10,  4, 4 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,28, -6,22,
           2,29,  4,24,  4,18,
          -2,15, -2, 9, -4, 2,
           2,15,  4, 9,  8, 4 },
        {  6,36,  4,30,  0,18,
          -2,30, -6,26,-10,20,
           2,30,  8,34, 14,36,
          -2,16, -4,10, -6, 4,
           2,16,  4,10,  6, 6 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,24, -4,18,
           2,29,  4,28,  6,22,
          -2,15, -4, 9, -8, 4,
           2,15,  4, 9,  4, 2 },
        {  6,36,  4,30,  0,18,
          -2,30, -8,34,-14,36,
           2,30,  6,26, 10,20,
          -2,16, -4,10, -8, 6,
           2,16,  2,10,  4, 4 },
        {  5,35,  3,29,  0,17,
          -2,29, -4,28, -6,22,
           2,29,  4,24,  4,18,
          -2,15, -2, 9, -4, 2,
           2,15,  4, 9,  8, 4 }
    }; }

    private static final double[][] PUNCH = initPunch();
    private static double[][] initPunch() { return new double[][] {
        // Right-hand jab — one-shot 20 frames.
        // Frames 0-1: guard stance.  2-3: wind-up.  4-5: extend.  6-7: impact.
        // 8-9: retract.  10-19: return to neutral guard.

        // Frame 0 — guard, weight slightly back
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — guard, slight bob
        {  0,46,  0,37,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 2 — wind-up: body rotates back, right arm cocks
        { -1,47, -1,38,  0,23,
          -3,36,  -8,32,  -6,28,
           4,36,  -2,32,  -6,26,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 3 — wind-up deeper, shoulder back
        { -2,47, -1,38,  0,23,
          -3,36,  -7,32,  -5,28,
           4,36,  -4,32,  -8,26,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 4 — punch launches: right arm shooting forward
        {  2,47,  1,38,  0,23,
          -5,36, -10,32,  -9,28,
           4,36,   8,34,  14,32,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 5 — arm near full extension
        {  3,47,  2,38,  0,23,
          -6,36, -11,32, -10,28,
           4,36,  10,35,  18,34,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 6 — full extension / impact
        {  4,47,  2,38,  0,23,
          -6,36, -12,32, -10,28,
           4,36,  11,35,  20,35,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 7 — impact held
        {  4,47,  2,38,  0,23,
          -6,36, -12,32, -10,28,
           4,36,  11,35,  20,35,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 8 — retract begins
        {  2,47,  1,38,  0,23,
          -5,36, -10,32,  -8,28,
           4,36,   9,34,  15,33,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 9 — arm pulling back quickly
        {  1,47,  0,38,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   7,33,  10,30,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frames 10-19 — settle back to guard
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 }
    }; }

    private static final double[][] CROSS = initCross();
    private static double[][] initCross() { return new double[][] {
        // Left-hand cross — rear power punch crossing the body. 20 frames.
        // 0-1: guard.  2-3: wind-up, rear shoulder rotates forward.
        // 4-5: left arm drives across body.  6-7: impact.  8-9: retract.  10-19: recover.

        // Frame 0 — guard
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — guard bob
        {  0,46,  0,37,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 2 — wind-up: left shoulder rotates forward, right arm cocks back
        { -1,47, -1,38,  0,23,
          -3,36,  -5,32,  -3,28,
           4,36,  -1,32,  -5,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 3 — deeper wind, hips driving
        { -2,47, -1,38,  0,23,
          -3,36,  -3,32,  -1,28,
           4,36,  -3,32,  -8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 4 — left arm driving across, crossing body centre
        {  2,47,  1,38,  0,23,
          -3,36,   4,34,  12,33,
           4,36,  -2,32,  -6,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 5 — near full extension
        {  3,47,  2,38,  0,23,
          -3,36,   6,34,  18,33,
           4,36,  -3,32,  -8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 6 — full extension / impact
        {  4,47,  2,38,  0,23,
          -3,36,   7,34,  20,33,
           4,36,  -4,32,  -9,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 7 — impact held
        {  4,47,  2,38,  0,23,
          -3,36,   7,34,  20,33,
           4,36,  -4,32,  -9,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 8 — retract begins
        {  2,47,  1,38,  0,23,
          -3,36,   4,33,  14,32,
           4,36,  -2,32,  -5,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 9 — arm pulling back past centre
        {  1,47,  0,38,  0,23,
          -4,36,  -4,32,   2,30,
           4,36,   2,32,   4,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frames 10-19 — return to guard
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] HOOK = initHook();
    private static double[][] initHook() { return new double[][] {
        // Right-arm hook — elbow bent, fist swings horizontal arc. 20 frames.
        // 0-1: guard.  2-3: arm swings back-out.  4-5: fist sweeps across.
        // 6-7: impact.  8-9: follow-through.  10-19: recover.

        // Frame 0 — guard
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — guard bob
        {  0,46,  0,37,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 2 — wind-up: body coils, right arm swings back and out
        { -2,47, -1,38,  0,23,
          -3,36,  -8,32,  -6,28,
           5,36,  14,34,   8,30,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 3 — elbow up, arm fully out ~90 degrees
        { -3,47, -2,38,  0,23,
          -3,36,  -8,32,  -6,28,
           5,36,  16,35,  10,30,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 4 — hook starts sweeping in
        {  2,47,  1,38,  0,23,
          -5,36, -10,32,  -8,28,
           5,36,  14,36,   6,36,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 5 — fist crossing centre line
        {  3,47,  2,38,  0,23,
          -6,36, -11,32,  -9,28,
           4,36,  10,37,  -2,37,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 6 — impact, fist past centre
        {  4,47,  2,38,  0,23,
          -6,36, -11,32,  -9,28,
           4,36,   8,37,  -6,37,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 7 — impact held
        {  4,47,  2,38,  0,23,
          -6,36, -11,32,  -9,28,
           4,36,   8,37,  -6,37,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 8 — follow-through, arc continuing
        {  2,47,  1,38,  0,23,
          -5,36, -10,32,  -8,28,
           4,36,   7,35,  -2,33,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 9 — arm returning
        {  1,47,  0,38,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,33,   4,30,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frames 10-19 — return to guard
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] UPPERCUT = initUppercut();
    private static double[][] initUppercut() { return new double[][] {
        // Right-arm uppercut — dip then explosive upward drive. 20 frames.
        // 0-1: guard.  2-3: dip low.  4-5: explosive drive upward.
        // 6-7: fist at peak above head.  8-9: arm drops.  10-19: recover.

        // Frame 0 — guard
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — guard bob
        {  0,46,  0,37,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 2 — dip: body lowers, right arm drops below guard
        {  0,44,  0,35,  0,21,
          -4,33,  -9,26,  -7,20,
           4,33,   4,25,   4,18,
          -3,20,  -6, 9,  -4, 0,
           3,20,   6, 9,   4, 0 },
        // Frame 3 — deep dip, arm fully cocked low
        {  0,43,  0,34,  0,20,
          -4,32,  -9,25,  -7,19,
           4,32,   3,22,   2,14,
          -3,19,  -6, 8,  -3, 0,
           3,19,   6, 8,   3, 0 },
        // Frame 4 — explosive drive: body surges up, fist flying upward
        {  2,47,  1,38,  0,23,
          -4,36,  -9,30,  -7,24,
           4,36,   7,40,   6,47,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 5 — fist past head, continuing upward
        {  2,48,  1,39,  0,23,
          -4,36,  -9,30,  -7,24,
           4,36,   7,42,   5,50,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 6 — fist at peak above head
        {  1,48,  0,39,  0,23,
          -4,36,  -9,30,  -7,24,
           4,36,   6,42,   4,50,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 7 — peak held
        {  1,48,  0,39,  0,23,
          -4,36,  -9,30,  -7,24,
           4,36,   6,42,   4,50,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 8 — arm beginning to drop
        {  0,47,  0,38,  0,23,
          -4,36,  -9,31,  -7,25,
           4,36,   7,40,   6,44,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 9 — arm dropping toward guard
        {  0,47,  0,38,  0,23,
          -4,36,  -9,31,  -7,25,
           4,36,   7,36,   7,34,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frames 10-19 — return to guard
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] HAYMAKER = initHaymaker();
    private static double[][] initHaymaker() { return new double[][] {
        // Wide overhand right — big looping punch from high and back. 20 frames.
        // 0-1: guard.  2-3: arm swings back-high.  4-5: wide arc overhead.
        // 6-7: impact from above.  8-9: follow-through.  10-19: recover.

        // Frame 0 — guard
        {  0,47,  0,38,  0,23,
          -4,36, -10,32,  -8,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — guard bob
        {  0,46,  0,37,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,32,   8,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 2 — wind-up: right arm swings way back and up high
        { -3,47, -2,38,  0,23,
          -3,36,  -8,32,  -6,28,
           5,36,  14,42,  18,46,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 3 — arm fully cocked back-high, body coiled
        { -4,47, -2,38,  0,23,
          -3,36,  -7,32,  -5,28,
           5,36,  16,42,  20,46,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 4 — wide arc begins, arm swinging overhead
        {  2,47,  1,38,  0,23,
          -5,36, -10,32,  -8,28,
           4,36,  12,42,  16,40,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 5 — arm descending in arc toward target
        {  4,47,  2,38,  0,23,
          -6,36, -11,32,  -9,28,
           4,36,  10,38,  12,32,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 6 — impact from above at steep angle
        {  5,47,  3,38,  0,23,
          -6,36, -11,32,  -9,28,
           4,36,   8,34,   4,26,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 7 — impact held, body leaning forward
        {  5,47,  3,38,  0,23,
          -6,36, -11,32,  -9,28,
           4,36,   8,34,   4,26,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 8 — follow-through
        {  3,47,  2,38,  0,23,
          -5,36, -10,32,  -8,28,
           4,36,   7,32,   4,24,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 9 — arm returning
        {  1,47,  1,38,  0,23,
          -4,36,  -9,32,  -7,28,
           4,36,   6,32,   6,28,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frames 10-19 — return to guard
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -10,32,  -8,28,  4,36,  6,32,  8,28,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] HEAD_KICK = initHeadKick();
    private static double[][] initHeadKick() { return new double[][] {
        // Right-leg head kick — foot reaches head height. 20 frames.
        // 0-1: guard.  2-3: chamber knee high.  4-5: leg extends upward.
        // 6-7: foot at head level impact.  8-9: retract.  10-19: recover.

        // Frame 0 — standing guard
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — weight shifting to left foot
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,12,   5, 0 },
        // Frame 2 — right knee begins rising high
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,24,   8,14 },
        // Frame 3 — knee fully chambered, aiming high
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  12,30,   8,24 },
        // Frame 4 — leg extending upward-forward toward head height
        {  0,47,  0,38,  0,23,
          -6,36, -10,28,  -8,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  12,30,  20,30 },
        // Frame 5 — foot nearing head level
        {  1,47,  1,38,  0,23,
          -7,36, -12,28, -10,20,
           5,36,   8,28,   6,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,28,  24,36 },
        // Frame 6 — full extension, foot at head height
        {  2,47,  1,38,  0,23,
          -7,36, -12,28, -10,20,
           5,36,   8,28,   6,20,
          -3,22,  -6,12,  -5, 0,
           3,22,   8,26,  24,40 },
        // Frame 7 — impact held
        {  2,47,  1,38,  0,23,
          -7,36, -12,28, -10,20,
           5,36,   8,28,   6,20,
          -3,22,  -6,12,  -5, 0,
           3,22,   8,26,  24,40 },
        // Frame 8 — leg dropping, knee pulling back
        {  0,47,  0,38,  0,23,
          -5,36,  -9,28,  -7,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  10,26,  16,22 },
        // Frame 9 — foot swings down
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,18,   6, 8 },
        // Frames 10-19 — recover to neutral
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] LOW_KICK = initLowKick();
    private static double[][] initLowKick() { return new double[][] {
        // Right-leg low kick — sweeping at shin/thigh level. 20 frames.
        // 0-1: guard.  2-3: leg cocks back low.  4-5: sweeping forward-low.
        // 6-7: impact at low height.  8-9: retract.  10-19: recover.

        // Frame 0 — standing guard
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — weight shifting forward
        { -1,46, -1,37,  0,22,
          -4,35,  -8,27,  -6,19,
           5,35,   9,27,   7,19,
          -3,21,  -5,10,  -4, 0,
           3,21,   4,10,  -2, 0 },
        // Frame 2 — right leg pulling back low, ready to sweep
        { -1,46, -1,37,  0,22,
          -4,35,  -8,27,  -6,19,
           5,35,   9,27,   7,19,
          -3,21,  -5,10,  -4, 0,
           3,21,   2, 8,  -4, 0 },
        // Frame 3 — coiled, weight fully on left leg
        { -2,46, -1,37,  0,22,
          -4,35,  -8,27,  -6,19,
           5,35,   9,27,   7,19,
          -3,21,  -5,10,  -4, 0,
           3,21,   0, 8,  -6, 0 },
        // Frame 4 — leg sweeping forward at low height
        {  1,47,  0,38,  0,23,
          -5,36,  -9,28,  -7,20,
           5,36,   9,28,   7,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,10,  16, 6 },
        // Frame 5 — leg at impact height, shin sweeping target
        {  2,47,  1,38,  0,23,
          -6,36, -10,28,  -8,20,
           5,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  10, 8,  20, 4 },
        // Frame 6 — full low kick impact
        {  2,47,  1,38,  0,23,
          -6,36, -10,28,  -8,20,
           5,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  10, 6,  22, 2 },
        // Frame 7 — impact held
        {  2,47,  1,38,  0,23,
          -6,36, -10,28,  -8,20,
           5,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  10, 6,  22, 2 },
        // Frame 8 — leg begins retracting
        {  1,47,  0,38,  0,23,
          -5,36,  -9,28,  -7,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,10,  14, 8 },
        // Frame 9 — foot back to ground
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   6,12,   6, 2 },
        // Frames 10-19 — recover to neutral
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] BODY_KICK = initBodyKick();
    private static double[][] initBodyKick() { return new double[][] {
        // Right-leg body kick — front kick to the midsection. 20 frames.
        // 0-1: ready stance.  2-3: chamber knee.  4-5: extend.
        // 6-7: full kick.  8-9: retract.  10-19: recover to stand.

        // Frame 0 — standing guard
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — weight shifting to left foot
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,12,   5, 0 },
        // Frame 2 — right knee begins rising toward chest
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,   8,30,   6,20 },
        // Frame 3 — knee fully chambered at chest height (ref: image 2)
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,   8,34,   6,24 },
        // Frame 4 — leg driving forward, knee still high
        {  0,47,  0,38,  0,23,
          -6,36, -10,28,  -8,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,32,  18,28 },
        // Frame 5 — leg nearing full extension at midsection height
        {  1,47,  1,38,  0,23,
          -7,36, -12,28, -10,20,
           5,36,   8,28,   6,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,28,  22,26 },
        // Frame 6 — full kick impact at solar plexus (ref: image 3)
        {  2,47,  1,38,  0,23,
          -7,36, -12,28, -10,20,
           5,36,   8,28,   6,20,
          -3,22,  -6,12,  -5, 0,
           3,22,   8,26,  24,24 },
        // Frame 7 — impact held
        {  2,47,  1,38,  0,23,
          -7,36, -12,28, -10,20,
           5,36,   8,28,   6,20,
          -3,22,  -6,12,  -5, 0,
           3,22,   8,26,  24,24 },
        // Frame 8 — leg retracting, knee bends back
        {  0,47,  0,38,  0,23,
          -5,36, -10,28,  -8,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  10,26,  14,18 },
        // Frame 9 — knee drops, foot swings down
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,18,   6, 6 },
        // Frames 10-19 — recover to neutral
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] SPINNING_BACK_KICK = initSpinningBackKick();
    private static double[][] initSpinningBackKick() { return new double[][] {
        // Spinning back heel kick — pivot 180, heel drives behind. 20 frames.
        // 0-1: guard.  2-3: spin begins, right leg lifts.  4-5: leg cocked behind.
        // 6-7: heel drives back, full extension.  8-9: retract and return.  10-19: recover.

        // Frame 0 — standing guard
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — weight shifting to left, right foot lifting
        {  1,47,  1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -4,22,  -6,12,  -6, 0,
           4,22,   6,12,   6, 2 },
        // Frame 2 — body starting to spin, right knee rising
        {  1,47,  1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,18,   4,10 },
        // Frame 3 — 90 degrees through spin, right leg rising behind
        {  2,47,  1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,20,   2,12 },
        // Frame 4 — 180 degrees, fully turned, leg cocked behind
        {  2,47,  1,38,  0,23,
          -5,36,  -9,28,  -7,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   6,14,  -2, 8 },
        // Frame 5 — leg driving back/outward
        {  2,47,  1,38,  0,23,
          -5,36,  -9,28,  -7,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   2,12, -12, 8 },
        // Frame 6 — full back kick, heel extended
        {  2,47,  1,38,  0,23,
          -5,36,  -9,28,  -7,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  -2,10, -18, 8 },
        // Frame 7 — impact held
        {  2,47,  1,38,  0,23,
          -5,36,  -9,28,  -7,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,  -2,10, -18, 8 },
        // Frame 8 — leg retracts, spinning back to face forward
        {  1,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   4,14,  -4, 6 },
        // Frame 9 — spin completes, foot returning to ground
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   6,16,   2, 4 },
        // Frames 10-19 — recover to neutral
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] SIDE_KICK = initSideKick();
    private static double[][] initSideKick() { return new double[][] {
        // Side thrust kick — body leans back, leg extends horizontal. 20 frames.
        // 0-1: guard.  2-3: knee chambers.  4-5: leg thrusts outward, body leans.
        // 6-7: full extension impact.  8-9: retract.  10-19: recover.

        // Frame 0 — standing guard
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — weight shifting, hip rotating
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,12,   5, 0 },
        // Frame 2 — knee chambers up, hip turned
        { -1,47, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,26,   8,20 },
        // Frame 3 — knee fully chambered, body turning sideways
        { -2,47, -1,38,  0,23,
          -5,36,  -9,28,  -7,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,28,   8,22 },
        // Frame 4 — leg thrusting, body leaning back for power
        { -3,46, -2,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  12,22,  22,18 },
        // Frame 5 — near full thrust
        { -4,46, -3,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,20,  24,16 },
        // Frame 6 — full extension impact
        { -4,46, -3,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,20,  26,16 },
        // Frame 7 — impact held
        { -4,46, -3,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  10,20,  26,16 },
        // Frame 8 — leg retracting, body straightening
        { -2,46, -1,38,  0,23,
          -4,36,  -8,28,  -6,20,
           5,36,   9,28,   7,20,
          -3,22,  -6,12,  -5, 0,
           3,22,  12,24,  16,14 },
        // Frame 9 — foot drops, stance returns
        {  0,47,  0,38,  0,23,
          -4,36,  -8,28,  -6,20,
           4,36,   8,28,   6,20,
          -3,22,  -5,11,  -4, 0,
           3,22,   8,18,   6, 6 },
        // Frames 10-19 — recover to neutral
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 },
        {  0,47,  0,38,  0,23,  -4,36, -8,28, -6,20,  4,36,  8,28,  6,20,  -3,22, -5,11, -4,0,  3,22,  5,11,  4,0 }
    }; }

    private static final double[][] SHOOT = initShoot();
    private static double[][] initShoot() { return new double[][] {
        // Bow-shoot release — one-shot 20 frames.
        // Frames 0-3: draw aiming.  4-5: full draw / hold.  6-7: release snap.
        // 8-11: follow-through.  12-19: recover to bow idle.

        // Frame 0 — aiming, right arm drawing back
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -1,37, -7,37,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 1 — draw deeper
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,37, -9,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 2 — near full draw
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 3 — full draw, exhale
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 4 — full draw locked
        {  2,47,  1,38,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 5 — slight tension tremor
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 6 — RELEASE: draw hand snaps back, bow arm recoils
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36,  2,37,  0,35,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 7 — arm snap through
        {  2,46,  1,38,  0,23,
          -3,36,  5,36, 13,35,
           4,36,  4,34,  2,30,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 8 — follow-through, both arms settling
        {  2,46,  1,38,  0,23,
          -3,36,  5,36, 12,35,
           4,36,  2,34,  0,28,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 9 — settling down
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36,  0,35, -3,32,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 10 — arms relaxing to bow idle
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -1,37, -6,36,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frame 11 — returning to draw ready
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        // Frames 12-19 — hold bow idle
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,38,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,38,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,38,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,46,  1,38,  0,23,
          -3,36,  4,36, 12,36,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 },
        {  2,47,  1,38,  0,23,
          -3,36,  4,36, 12,37,
           4,36, -2,38,-10,38,
          -3,22, -5,11, -8, 0,
           3,22,  5,12,  8, 0 }
    }; }

    private static final double[][] KNOCKED = {
        // Frame 0 — impact: head snaps back, arms fling out wide
        {  6, 44,  3, 36,  0, 22,
          -5, 35, -14, 27, -22, 22,
           5, 35,  14, 27,  22, 22,
          -3, 21,  -5, 11,  -4,  0,
           3, 21,   5, 11,   4,  0,
          18, 30, -26, 18,   0, 44 },
        // Frame 1 — staggering back, body leaning ~20°
        { 10, 41,  6, 33,  1, 20,
          -4, 32, -13, 24, -20, 17,
           5, 32,  14, 35,  22, 36,
          -2, 19,  -2, 10,   0,  0,
           3, 19,   6, 10,   8,  0,
          18, 26, -26, 16,   0, 44 },
        // Frame 2 — falling hard ~40° backward
        { 14, 34,  8, 27,  2, 16,
          -4, 26, -12, 18, -18, 11,
           4, 26,  13, 30,  21, 33,
          -1, 15,   3,  7,   8,  0,
           3, 15,   4,  7,   1,  0,
          17, 19, -27, 13,   0, 44 },
        // Frame 3 — nearly horizontal ~60° back, arms splayed
        { 17, 23, 10, 18,  2, 11,
          -4, 17, -13, 11, -21,  6,
           4, 17,  14, 21,  22, 25,
           0, 10,   6,  4,  14,  0,
           3, 10,   1,  4,  -4,  0,
          16, 11, -28,  9,   0, 44 },
        // Frame 4 — body ~80° back, crashing down
        { 19, 13, 11, 10,  2,  6,
          -4, 10, -13,  6, -22,  3,
           4, 10,  14, 14,  23, 18,
           1,  7,   8,  3,  17,  0,
           3,  7,  -1,  3,  -7,  0,
          15,  5, -29,  5,   0, 44 },
        // Frame 5 — flat on ground, arms spread wide (final hold pose)
        {-20,  8, -10,  5,   0,  4,
          -8,  5, -18,  3,  -28,  2,
           2,  5,  12,  3,   22,  2,
           3,  4,   8, 17,   16,  3,
           5,  4,  12, 19,   21,  4,
          18,-10, -32,  2,    0, 44 },
        // Frame 6 — hold flat
        {-20,  8, -10,  5,   0,  4,
          -8,  5, -18,  3,  -28,  2,
           2,  5,  12,  3,   22,  2,
           3,  4,   8, 17,   16,  3,
           5,  4,  12, 19,   21,  4,
          18,-10, -32,  2,    0, 44 },
        // Frame 7 — hold flat
        {-20,  8, -10,  5,   0,  4,
          -8,  5, -18,  3,  -28,  2,
           2,  5,  12,  3,   22,  2,
           3,  4,   8, 17,   16,  3,
           5,  4,  12, 19,   21,  4,
          18,-10, -32,  2,    0, 44 },
        // Frame 8 — hold flat
        {-20,  8, -10,  5,   0,  4,
          -8,  5, -18,  3,  -28,  2,
           2,  5,  12,  3,   22,  2,
           3,  4,   8, 17,   16,  3,
           5,  4,  12, 19,   21,  4,
          18,-10, -32,  2,    0, 44 },
        // Frame 9 — hold flat
        {-20,  8, -10,  5,   0,  4,
          -8,  5, -18,  3,  -28,  2,
           2,  5,  12,  3,   22,  2,
           3,  4,   8, 17,   16,  3,
           5,  4,  12, 19,   21,  4,
          18,-10, -32,  2,    0, 44 },
        // Frame 10 — hold flat
        {-20,  8, -10,  5,   0,  4,
          -8,  5, -18,  3,  -28,  2,
           2,  5,  12,  3,   22,  2,
           3,  4,   8, 17,   16,  3,
           5,  4,  12, 19,   21,  4,
          18,-10, -32,  2,    0, 44 },
        // Frame 11
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 12
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 13
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 14
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 15
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 16
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 17
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 },
        // Frame 18
        { -20, 8, -10, 5, 0, 4,
          -8, 5, -15, 3, -22, 2,
          2, 5, 7, 3, 12, 2,
          3, 4, 8, 17, 16, 3,
          5, 4, 12, 19, 21, 4,
          18, -10, -32, 2, 0, 44 }
    };

    // ── Front Flip ────────────────────────────────────────────────────────────
    // One-shot aerial forward rotation: takeoff → inverted tuck → landing.
    // 8 frames at 70 ms each ≈ 560 ms total.
    //
    // Rotation stages per frame:
    //   F0  0° — lean forward, arms back (takeoff prep)
    //   F1 20° — launch upward, arms swing HIGH forward
    //   F2 65° — body pitching forward, tucking begins, airborne
    //  F3 130° — near-inverted, tight tuck ascending
    //  F4 180° — fully inverted at apex, tight ball
    //  F5 250° — past apex, opening, head rising on far side
    //  F6 310° — nearly upright, arms wide, legs extending for landing
    //  F7 360° — deep crouch landing, arms spread
    private static final double[][] FRONT_FLIP = {
        // Frame 0 — lean forward, arms hanging back (takeoff prep)
        {  4,44,  2,36,  0,22,
          -4,33,-11,26,-15,20,
           5,33, 12,26, 16,20,
          -2,21, -4,11, -4, 0,
           2,21,  4,11,  4, 0 },
        // Frame 1 — jump, arms swing HIGH forward-up
        {  2,48,  1,40,  0,26,
          -3,38,-10,45,-16,51,
           4,38, 11,45, 16,51,
          -2,25, -4,14, -5, 3,
           2,25,  4,14,  6, 3 },
        // Frame 2 — body ~65° forward, tucking begins, airborne
        { 12,42,  8,37,  2,30,
          -1,35, -5,29, -7,23,
           4,34,  1,28, -2,22,
           0,29,  6,36, 11,40,
           2,29,  8,35, 13,38 },
        // Frame 3 — body ~130° forward, ascending, tight tuck
        { 10,24,  7,29,  2,37,
           0,32,  5,27,  8,23,
           4,31, -1,26, -3,22,
           0,37,  6,43, 10,47,
           2,37,  8,42, 12,45 },
        // Frame 4 — fully inverted 180°, apex, tight ball
        {  0,22,  0,30,  0,44,
          -4,28, -6,34, -8,40,
           4,28,  6,34,  8,40,
          -3,42, -6,36, -4,28,
           3,42,  6,36,  4,28 },
        // Frame 5 — ~250°, past apex, opening; head rising on far side
        { -8,26, -5,31,  0,36,
          -4,32, -8,38,-10,43,
           4,31,  2,37,  0,42,
           0,36,  6,30, 11,23,
           2,36,  8,28, 13,19 },
        // Frame 6 — ~310°, nearly upright, arms wide, legs extending
        { -4,38, -2,32,  0,22,
          -4,28,-12,22,-18,15,
           5,28, 12,22, 18,15,
          -2,21, -4,11, -4, 2,
           2,21,  4,11,  4, 2 },
        // Frame 7 — landing deep crouch, arms spread for balance
        {  0,28,  0,22,  0,14,
          -5,21,-13,15,-19, 9,
           5,21, 13,15, 19, 9,
          -3,13, -7, 6, -6, 0,
           3,13,  7, 6,  6, 0 }
    };

    // ── Kip-Up ────────────────────────────────────────────────────────────────
    // One-shot recovery: rises from KNOCKED_DOWN ground pose back to IDLE.
    // 20 frames at 40 ms each ≈ 800 ms total.
    //
    // Sequence maps the 11-pose sprite sheet reference with 9 interpolated
    // in-between frames for smooth motion:
    //   flat → tuck roll → candlestick (peak load) → explosive kick →
    //   body rotates up → forward arch/momentum → absorb crouch → stand.
    //
    // Joint order (15 pairs = 30 values per frame):
    //   head, neck, hip,
    //   l.shoulder, l.elbow, l.hand,
    //   r.shoulder, r.elbow, r.hand,
    //   l.hipJoint, l.knee, l.foot,
    //   r.hipJoint, r.knee, r.foot
    private static final double[][] KIP_UP = {
        // F0  — 0001: flat on back; head circle far-left, body horizontal, bent knee right
        {-22, 8, -12, 5,   0, 4,
          -8, 5, -14, 3,  -20, 2,
           4, 5,   8, 3,   12, 2,
           2, 4,  10,14,   18, 3,
           4, 4,  14,16,   22, 2 },
        // F1  — 0002: candlestick/loaded kip; body lower-right on floor, legs overhead-left
        {  6, 8,   2,14,   -4,26,
           4,14,   8, 8,   10, 3,
           0,14,  -4, 8,   -6, 3,
          -6,26, -14,38,  -20,46,
          -4,25, -12,36,  -18,44 },
        // F2  — 0003: candlestick (held, slight leg extension)
        {  6, 8,   2,14,   -4,26,
           4,14,   8, 8,   10, 3,
           0,14,  -4, 8,   -6, 3,
          -6,26, -15,39,  -21,47,
          -4,25, -13,37,  -19,45 },
        // F3  — 0004: tight tuck; body on floor, thighs vertical, lower legs folded left
        {  2, 8,  -1,14,   -4,22,
          -2,13,  -6, 8,   -8, 3,
           3,13,   7, 8,    9, 3,
          -6,23,  -6,38,  -14,28,
          -4,22,  -4,36,  -12,26 },
        // F4  — 0005: tight tuck (same, held)
        {  2, 8,  -1,14,   -4,22,
          -2,13,  -6, 8,   -8, 3,
           3,13,   7, 8,    9, 3,
          -6,23,  -6,38,  -14,28,
          -4,22,  -4,36,  -12,26 },
        // F5  — 0006: explosive kick; body right/low, legs flying out left
        { 12, 8,   6,12,   -2,18,
           8,14,   4, 8,    0, 3,
           4,14,   0,10,   -4, 6,
          -4,18, -14,16,  -24,12,
          -2,18, -12,14,  -22,10 },
        // F6  — 0007: explosive kick (same, held)
        { 12, 8,   6,12,   -2,18,
           8,14,   4, 8,    0, 3,
           4,14,   0,10,   -4, 6,
          -4,18, -14,16,  -24,12,
          -2,18, -12,14,  -22,10 },
        // F7  — 0008: tight tuck (second rotation; same as F3)
        {  2, 8,  -1,14,   -4,22,
          -2,13,  -6, 8,   -8, 3,
           3,13,   7, 8,    9, 3,
          -6,23,  -6,38,  -14,28,
          -4,22,  -4,36,  -12,26 },
        // F8  — 0009: tight tuck (held)
        {  2, 8,  -1,14,   -4,22,
          -2,13,  -6, 8,   -8, 3,
           3,13,   7, 8,    9, 3,
          -6,23,  -6,38,  -14,28,
          -4,22,  -4,36,  -12,26 },
        // F9  — 0010: candlestick again (second swing)
        {  6, 8,   2,14,   -4,26,
           4,14,   8, 8,   10, 3,
           0,14,  -4, 8,   -6, 3,
          -6,26, -14,38,  -20,46,
          -4,25, -12,36,  -18,44 },
        // F10 — 0011: candlestick (held)
        {  6, 8,   2,14,   -4,26,
           4,14,   8, 8,   10, 3,
           0,14,  -4, 8,   -6, 3,
          -6,26, -14,38,  -20,46,
          -4,25, -12,36,  -18,44 },
        // F11 — 0012: nearly standing; tall figure, slight forward lean, feet slightly left
        {  0,44,  -1,36,   -1,22,
          -4,33,  -5,25,   -4,17,
           3,33,   4,25,    3,17,
          -3,22,  -6,11,   -8, 0,
           1,22,  -2,11,   -3, 0 },
        // F12 — 0013: nearly standing (same, slight weight shift)
        {  0,44,  -1,36,   -1,22,
          -4,33,  -5,25,   -4,17,
           3,33,   4,25,    3,17,
          -3,22,  -6,11,   -8, 0,
           1,22,  -2,11,   -3, 0 },
        // F13 — 0014: airborne back arch; head left/low, dramatic C-arc, feet right
        {-16,20,  -8,26,    0,30,
         -10,28, -18,24,  -24,18,
           8,28,  16,22,   22,16,
          -2,29,   8,20,   16,10,
           4,28,  14,18,   22, 8 },
        // F14 — 0015: airborne back arch (same, peak)
        {-16,20,  -8,26,    0,30,
         -10,28, -18,24,  -24,18,
           8,28,  16,22,   22,16,
          -2,29,   8,20,   16,10,
           4,28,  14,18,   22, 8 },
        // F15 — 0016: backward lean; head upper-left, body diagonal, feet planted right
        {-14,34,  -6,28,    2,18,
          -8,25, -16,21,  -22,16,
          -2,24, -12,19,  -18,14,
           0,17,   4, 8,    6, 0,
           4,17,   8, 8,   10, 0 },
        // F16 — 0017: backward lean (same, held)
        {-14,34,  -6,28,    2,18,
          -8,25, -16,21,  -22,16,
          -2,24, -12,19,  -18,14,
           0,17,   4, 8,    6, 0,
           4,17,   8, 8,   10, 0 },
        // F17 — 0018: deep squat; arms forward, deep knee bend
        {  2,34,   1,27,    0,18,
          -4,25,  -8,18,   -2,14,
           5,25,   9,18,    3,14,
          -3,17,  -8, 6,   -4, 0,
           3,17,   8, 6,    4, 0 },
        // F18 — 0019: deep squat (same, held)
        {  2,34,   1,27,    0,18,
          -4,25,  -8,18,   -2,14,
           5,25,   9,18,    3,14,
          -3,17,  -8, 6,   -4, 0,
           3,17,   8, 6,    4, 0 },
        // F19 — 0020: standing upright; slight forward lean, arms at sides
        {  2,46,   1,37,    0,22,
          -6,35,  -7,26,   -6,17,
           5,35,   6,26,    5,17,
          -3,21,  -4,11,   -5, 0,
           3,21,   4,11,    6, 0 }
    };

    // ── Crawl ─────────────────────────────────────────────────────────────────
    // Six-frame looping crawl cycle on hands and knees.
    // Body is roughly horizontal (hips ~y=20, shoulders ~y=20).
    // Diagonal pairs alternate: (L-arm + R-knee) then (R-arm + L-knee).
    // 120 ms/frame × 6 = 720 ms per full cycle.
    private static final double[][] CRAWL = {
        // Frame 0 — neutral four-point, left arm forward / right knee forward
        { 16,27, 10,23,  0,20,
          10,23, 16,13, 20, 1,    // left arm reaches forward
           8,23,  2,13, -4, 1,    // right arm planted back
          -2,20, -8,10,-14, 1,    // left leg planted (back)
           2,20,  6,10, 12, 1 },  // right leg planted (forward)
        // Frame 1 — right arm lifts and swings forward, left knee lifts
        { 18,28, 12,24,  0,21,
          10,24, 16,14, 20, 1,    // left arm planted
           8,24, 14,16, 22, 3,    // right arm swings forward (lifted)
          -2,21, -4,16,  0, 4,    // left knee lifts and moves forward
           2,21,  6,10, 12, 1 },  // right knee still planted
        // Frame 2 — right arm plants forward, left knee lands
        { 16,27, 10,23,  0,20,
           8,23,  2,13, -4, 1,    // left arm now back
          10,23, 16,13, 22, 1,    // right arm planted forward
           -2,20,  2,10,  8, 1,   // left knee now forward
           2,20,  6,10, 12, 1 },  // right knee middle
        // Frame 3 — left arm lifts and swings forward, right knee lifts
        { 18,28, 12,24,  0,21,
          10,24, 18,16, 26, 3,    // left arm swings forward (lifted)
          10,24, 16,14, 22, 1,    // right arm planted
          -2,21,  2,10,  8, 1,    // left knee planted (forward)
           2,21,  4,16,  8, 4 },  // right knee lifts and moves forward
        // Frame 4 — left arm plants further forward, right knee lands
        { 16,27, 10,23,  0,20,
          12,23, 18,13, 24, 1,    // left arm planted forward
          10,23, 16,13, 22, 1,    // right arm middle
          -2,20,  2,10,  8, 1,    // left knee forward
           2,20,  8,10, 14, 1 },  // right knee planted
        // Frame 5 — body weight shifts, preparing next cycle
        { 16,27, 10,23,  0,20,
          12,23, 18,13, 22, 1,    // left arm forward
           8,23,  2,13, -2, 1,    // right arm back
          -4,20, -8,10,-14, 1,    // left knee pulled back
           2,20,  8,10, 14, 1 }   // right knee forward
    };
}
