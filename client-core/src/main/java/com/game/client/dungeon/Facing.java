package com.game.client.dungeon;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;

/**
 * Cardinal direction a character faces on the dungeon grid.
 *
 * Convention matches jME3 coordinate space:
 *   NORTH = -Z, EAST = +X, SOUTH = +Z, WEST = -X
 */
public enum Facing {
    NORTH, EAST, SOUTH, WEST;

    public Facing turnRight() {
        return values()[(ordinal() + 1) % 4];
    }

    public Facing turnLeft() {
        return values()[(ordinal() + 3) % 4];
    }

    /** Grid delta for one step forward in this direction: [dx, dz]. */
    public int[] forwardDelta() {
        return switch (this) {
            case NORTH -> new int[]{ 0, -1};
            case EAST  -> new int[]{ 1,  0};
            case SOUTH -> new int[]{ 0,  1};
            case WEST  -> new int[]{-1,  0};
        };
    }

    /**
     * Y-axis rotation angle (radians) to orient a spatial so its -Z axis
     * points in this direction. Positive = counter-clockwise from above.
     */
    public float toYaw() {
        return switch (this) {
            case NORTH ->  0f;
            case EAST  ->  FastMath.HALF_PI;
            case SOUTH ->  FastMath.PI;
            case WEST  -> -FastMath.HALF_PI;
        };
    }

    public Vector3f toVector3f() {
        return switch (this) {
            case NORTH -> new Vector3f( 0, 0, -1);
            case EAST  -> new Vector3f( 1, 0,  0);
            case SOUTH -> new Vector3f( 0, 0,  1);
            case WEST  -> new Vector3f(-1, 0,  0);
        };
    }
}
