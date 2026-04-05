package com.game.client.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

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

    public enum State { IDLE, RUN, JUMP, FALL, HIT, KNOCKED_DOWN }

    // ── Timing (ms per frame) ─────────────────────────────────────────────────
    private static final long IDLE_MS  = 650;
    private static final long RUN_MS   = 105;
    private static final long HIT_MS   = 140;
    private static final long OTHER_MS = 180;

    // ── Drawing constants ─────────────────────────────────────────────────────
    private static final double LINE_W      = 5.0;
    private static final double HEAD_R      = 8.0;
    /** Canvas units above the feet position to draw the name label. */
    public  static final double LABEL_ABOVE = 60.0;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile State   state       = State.IDLE;
    private volatile int     frame       = 0;
    private          long    lastFrameMs = 0;
    private volatile boolean facingRight = true;

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

    /** Force into hit-stagger animation (future combat hook). */
    public void triggerHit() {
        state       = State.HIT;
        frame       = 0;
        lastFrameMs = System.currentTimeMillis();
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
        advanceFrame();
        double[] pose = currentPose();

        gc.save();
        gc.translate(cx, canvasY);
        if (!facingRight) gc.scale(-1, 1);   // mirror for left-facing

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
        if ((state == State.HIT || state == State.KNOCKED_DOWN)
                && frame < poseCount(state) - 1) {
            return state;
        }
        if (!onGround) return velY > 0.5f ? State.JUMP : State.FALL;
        if (Math.abs(velX) > 0.3f) return State.RUN;
        return State.IDLE;
    }

    private void advanceFrame() {
        long now = System.currentTimeMillis();
        long interval = switch (state) {
            case IDLE -> IDLE_MS;
            case RUN  -> RUN_MS;
            case HIT  -> HIT_MS;
            default   -> OTHER_MS;
        };
        if (now - lastFrameMs >= interval) {
            frame = (frame + 1) % poseCount(state);
            lastFrameMs = now;
        }
    }

    private int poseCount(State s) {
        return switch (s) {
            case IDLE         -> IDLE.length;
            case RUN          -> RUN.length;
            case JUMP         -> JUMP.length;
            case FALL         -> FALL.length;
            case HIT          -> HIT.length;
            case KNOCKED_DOWN -> KNOCKED.length;
        };
    }

    private double[] currentPose() {
        int f = frame % poseCount(state);
        return switch (state) {
            case IDLE         -> IDLE[f];
            case RUN          -> RUN[f];
            case JUMP         -> JUMP[f];
            case FALL         -> FALL[f];
            case HIT          -> HIT[f];
            case KNOCKED_DOWN -> KNOCKED[f];
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
        // Frame 0 — neutral standing
        {  0,47,  0,38,  0,23,
          -4,36, -10,27,  -8,18,
           4,36,  10,27,   8,18,
          -3,22,  -5,11,  -4, 0,
           3,22,   5,11,   4, 0 },
        // Frame 1 — slight weight shift
        {  0,48,  0,39,  0,23,
          -4,36, -11,27,  -9,18,
           4,36,   9,27,   7,18,
          -3,22,  -6,11,  -5, 0,
           3,22,   4,11,   3, 0 }
    };

    private static final double[][] RUN = {
        // Frame 0 — left leg forward, right arm forward
        {  3,44,  2,36,  0,22,
          -2,34, -10,26, -13,18,
           3,34,   9,31,  13,40,
          -3,21,   5,12,  10, 2,
           2,21,  -3,10,  -8, 0 },
        // Frame 1 — right leg forward, left arm forward
        {  3,44,  2,36,  0,22,
          -2,34,  -3,29,   3,38,
           3,34,  10,26,  14,18,
          -3,21,  -4,10,  -8, 0,
           2,21,   6,12,  12, 2 },
        // Frame 2 — left leg raised mid-stride
        {  3,45,  2,37,  0,23,
          -2,34, -12,28, -15,20,
           3,34,  10,33,  15,42,
          -3,22,   6,14,  12, 4,
           2,22,  -2,10,  -6, 0 },
        // Frame 3 — right leg raised mid-stride
        {  3,45,  2,37,  0,23,
          -2,34,  -3,30,   4,40,
           3,34,  11,27,  15,19,
          -3,22,  -2,10,  -5, 0,
           2,22,   7,14,  14, 4 }
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
           4,24,  10,16,   8, 8 }
    };

    private static final double[][] FALL = {
        // Arms wide, legs trailing
        {  0,44,  0,36,  0,22,
          -4,35, -16,38, -20,42,
           4,35,  16,38,  20,42,
          -3,21,  -7,10,  -5, 2,
           3,21,   7,10,   5, 2 }
    };

    private static final double[][] HIT = {
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
           2,20,  10, 9,  12, 0 }
    };

    private static final double[][] KNOCKED = {
        // Frame 0 — flying backward through air
        { -6,32, -2,24,  2,14,
          -4,22, -12,28, -16,34,
           4,22,  12,16,  16,10,
           0,12,   8, 6,  14, 0,
           4,12,  -4, 6, -10, 0 },
        // Frame 1 — crumpled on ground
        {-16, 6,-10, 4, -2, 4,
          -8, 5,-16, 8, -22,10,
          -2, 5,  6, 8,  10,10,
           0, 4,   8, 2,  16, 0,
           2, 4,  -6, 2, -14, 0 }
    };
}
