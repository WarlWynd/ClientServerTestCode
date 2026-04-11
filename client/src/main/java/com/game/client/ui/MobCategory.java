package com.game.client.ui;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mob body-type categories.
 *
 * Each category owns a distinct set of animation states and a different
 * skeleton layout.  The Pose Editor, Sprite Preview, and Sprite Editor
 * all filter by category so that only relevant states are shown for each
 * body type.
 *
 * Adding a new body type here (plus matching states in PlayerAnimator.State)
 * automatically propagates to all three editors.
 */
public enum MobCategory {

    HUMANOID("Humanoid",
        PlayerAnimator.State.IDLE,        PlayerAnimator.State.RUN,
        PlayerAnimator.State.JUMP,        PlayerAnimator.State.FALL,
        PlayerAnimator.State.GOTHIT01,    PlayerAnimator.State.GOTHIT02,
        PlayerAnimator.State.GOTHIT03,    PlayerAnimator.State.STAFF_IDLE,
        PlayerAnimator.State.SWORD_1H_IDLE, PlayerAnimator.State.SWORD_2H_IDLE,
        PlayerAnimator.State.AXE_1H_IDLE, PlayerAnimator.State.AXE_2H_IDLE,
        PlayerAnimator.State.DAGGER_IDLE, PlayerAnimator.State.MORNING_STAR_IDLE,
        PlayerAnimator.State.BOW_IDLE,    PlayerAnimator.State.KNOCKED_DOWN,
        PlayerAnimator.State.CROUCH,      PlayerAnimator.State.SNEAK,
        PlayerAnimator.State.CLIMB,       PlayerAnimator.State.PRONE,
        PlayerAnimator.State.ROLL,        PlayerAnimator.State.SWIM,
        PlayerAnimator.State.PUNCH,       PlayerAnimator.State.CROSS,
        PlayerAnimator.State.HOOK,        PlayerAnimator.State.UPPERCUT,
        PlayerAnimator.State.HAYMAKER,    PlayerAnimator.State.HEAD_KICK,
        PlayerAnimator.State.LOW_KICK,    PlayerAnimator.State.BODY_KICK,
        PlayerAnimator.State.SPINNING_BACK_KICK, PlayerAnimator.State.SIDE_KICK,
        PlayerAnimator.State.SHOOT,       PlayerAnimator.State.KIP_UP,
        PlayerAnimator.State.FRONT_FLIP,  PlayerAnimator.State.CRAWL,
        PlayerAnimator.State.BLOCK),

    QUADRUPED("Quadruped",
        PlayerAnimator.State.QUAD_IDLE,   PlayerAnimator.State.TROT,
        PlayerAnimator.State.GALLOP,      PlayerAnimator.State.POUNCE,
        PlayerAnimator.State.BITE,        PlayerAnimator.State.QUAD_DEATH);

    /** Human-readable label shown in UI dropdowns. */
    public final String displayName;

    private final Set<PlayerAnimator.State> stateSet;

    MobCategory(String displayName, PlayerAnimator.State... states) {
        this.displayName = displayName;
        this.stateSet    = EnumSet.copyOf(Arrays.asList(states));
    }

    /** All states that belong to this category. */
    public Set<PlayerAnimator.State> states() { return stateSet; }

    /** Sorted list of states for this category (for UI display). */
    public List<PlayerAnimator.State> sortedStates() {
        return stateSet.stream()
                .sorted(java.util.Comparator.comparing(Enum::name))
                .collect(Collectors.toList());
    }

    /** True if the given state belongs to this category. */
    public boolean contains(PlayerAnimator.State s) { return stateSet.contains(s); }

    /** Returns the category that owns the given state; defaults to HUMANOID if unknown. */
    public static MobCategory of(PlayerAnimator.State s) {
        for (MobCategory c : values()) if (c.contains(s)) return c;
        return HUMANOID;
    }

    @Override public String toString() { return displayName; }
}
