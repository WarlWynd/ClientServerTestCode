package com.game.client.state;

import com.game.client.dungeon.DungeonMap;
import com.game.client.entity.CharacterEntity;
import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;

/**
 * Translates keyboard input into discrete grid moves for the local player.
 *
 * W/Up = step forward  |  S/Down = step backward
 * A/Left = turn left   |  D/Right = turn right
 * Q/E = strafe left/right
 *
 * A short cooldown between moves prevents instant cross-room sliding
 * while still feeling responsive.
 */
public class GridMovementController extends BaseAppState implements ActionListener {

    private static final String FWD   = "gmc_fwd";
    private static final String BACK  = "gmc_back";
    private static final String SL    = "gmc_sl";
    private static final String SR    = "gmc_sr";
    private static final String TL    = "gmc_tl";
    private static final String TR    = "gmc_tr";

    private static final float STEP_COOLDOWN = 0.18f;

    private final CharacterEntity player;
    private final DungeonMap      map;
    private final Runnable        onMoved;

    private float cooldown = 0f;

    public GridMovementController(CharacterEntity player, DungeonMap map, Runnable onMoved) {
        this.player  = player;
        this.map     = map;
        this.onMoved = onMoved;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application app) {
        var im = app.getInputManager();
        im.addMapping(FWD,  new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
        im.addMapping(BACK, new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
        im.addMapping(TL,   new KeyTrigger(KeyInput.KEY_A), new KeyTrigger(KeyInput.KEY_LEFT));
        im.addMapping(TR,   new KeyTrigger(KeyInput.KEY_D), new KeyTrigger(KeyInput.KEY_RIGHT));
        im.addMapping(SL,   new KeyTrigger(KeyInput.KEY_Q));
        im.addMapping(SR,   new KeyTrigger(KeyInput.KEY_E));
        im.addListener(this, FWD, BACK, SL, SR, TL, TR);
    }

    @Override
    protected void cleanup(Application app) {
        var im = app.getInputManager();
        im.removeListener(this);
        for (String m : new String[]{FWD, BACK, SL, SR, TL, TR}) {
            if (im.hasMapping(m)) im.deleteMapping(m);
        }
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    @Override
    public void update(float tpf) {
        if (cooldown > 0) cooldown -= tpf;
    }

    // ── Public API (called from on-screen buttons) ────────────────────────────

    public void forward()     { doAction(FWD);  }
    public void backward()    { doAction(BACK); }
    public void strafeLeft()  { doAction(SL);   }
    public void strafeRight() { doAction(SR);   }
    public void turnLeft()    { doAction(TL);   }
    public void turnRight()   { doAction(TR);   }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (!isPressed) return;
        doAction(name);
    }

    private void doAction(String name) {
        if (cooldown > 0) return;

        boolean moved = switch (name) {
            case FWD  -> tryStep(player.facing.forwardDelta());
            case BACK -> { int[] d = player.facing.forwardDelta(); yield tryStep(new int[]{-d[0], -d[1]}); }
            case SL   -> tryStep(leftDelta());
            case SR   -> { int[] l = leftDelta(); yield tryStep(new int[]{-l[0], -l[1]}); }
            case TL   -> { player.facing = player.facing.turnLeft();  yield true; }
            case TR   -> { player.facing = player.facing.turnRight(); yield true; }
            default   -> false;
        };

        if (moved) {
            cooldown = STEP_COOLDOWN;
            onMoved.run();
        }
    }

    private boolean tryStep(int[] delta) {
        int nx = player.gridX + delta[0];
        int nz = player.gridZ + delta[1];
        if (!map.isPassable(nx, nz)) return false;
        player.gridX = nx;
        player.gridZ = nz;
        return true;
    }

    private int[] leftDelta() {
        return switch (player.facing) {
            case NORTH -> new int[]{-1,  0};
            case EAST  -> new int[]{ 0, -1};
            case SOUTH -> new int[]{ 1,  0};
            case WEST  -> new int[]{ 0,  1};
        };
    }
}
