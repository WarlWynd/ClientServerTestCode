package com.game.client.state;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

/**
 * Builds 2D arrow meshes for the HUD movement pad.
 * All meshes are centered at the origin and sized to fit within ±(size/2).
 * Face culling must be disabled on the material (shapes are flat, no normals needed).
 */
class ArrowMesh {

    enum Dir { UP, DOWN, LEFT, RIGHT, TURN_LEFT, TURN_RIGHT }

    static Mesh build(Dir dir, float size) {
        return switch (dir) {
            case UP         -> straight(size, 90f);
            case DOWN       -> straight(size, 270f);
            case LEFT       -> straight(size, 180f);
            case RIGHT      -> straight(size, 0f);
            case TURN_LEFT  -> curved(size, true);
            case TURN_RIGHT -> curved(size, false);
        };
    }

    // ── Straight arrow (head triangle + shaft rectangle), pointing right at 0° ──

    private static Mesh straight(float size, float angleDeg) {
        float hh = size * 0.34f;  // head half-width
        float hl = size * 0.40f;  // head length
        float sh = size * 0.13f;  // shaft half-width
        float sl = size * 0.30f;  // shaft length

        // Pointing RIGHT (+x)
        float[] v = {
            // Head triangle: tip at (hl,0), base at (0,±hh)
             hl,   0,  0,   // 0: tip
              0,  hh,  0,   // 1: base top
              0, -hh,  0,   // 2: base bot
            // Shaft rectangle: from x=0 to x=-sl, y=±sh
              0,  sh,  0,   // 3
            -sl,  sh,  0,   // 4
            -sl, -sh,  0,   // 5
              0, -sh,  0,   // 6
        };
        // CCW winding (face cull off, but keep consistent)
        short[] idx = { 0, 1, 2,   3, 4, 5,   3, 5, 6 };

        rotate2D(v, angleDeg);
        return mesh(v, idx);
    }

    // ── Curved arc arrow (↺ for left, ↻ for right) ──────────────────────────────

    private static Mesh curved(float size, boolean left) {
        float r1   = size * 0.43f;  // outer radius
        float r2   = size * 0.27f;  // inner radius
        int   segs = 10;            // arc smoothness

        // Arc from 0° (right) sweeping 240° counter-clockwise, ending lower-left
        float startRad = 0f;
        float sweepRad = (float) Math.toRadians(240);

        // Vertex layout: segs+1 pairs of (outer, inner) = 2*(segs+1) vertices
        // Plus 3 arrowhead vertices at the end
        int vTotal = (segs + 1) * 2 + 3;
        float[] v  = new float[vTotal * 3];

        for (int i = 0; i <= segs; i++) {
            float a  = startRad + sweepRad * i / segs;
            float cx = (float) Math.cos(a);
            float cy = (float) Math.sin(a);
            int   oi = i * 6;
            v[oi]   = cx * r1;  v[oi+1] = cy * r1;  v[oi+2] = 0;  // outer
            v[oi+3] = cx * r2;  v[oi+4] = cy * r2;  v[oi+5] = 0;  // inner
        }

        // Arrowhead at the END of the arc
        float endA = startRad + sweepRad;
        float ex = (float) Math.cos(endA);
        float ey = (float) Math.sin(endA);
        // CCW tangent at end angle: (-sin, cos)
        float tx = -ey, ty = ex;
        float midR = (r1 + r2) * 0.5f;
        float ah   = (r1 - r2) * 1.6f;  // protrusion along tangent
        int   ai   = (segs + 1) * 2 * 3;
        // tip
        v[ai]   = ex * midR + tx * ah;  v[ai+1] = ey * midR + ty * ah;  v[ai+2] = 0;
        // outer base (arc outer edge at end angle)
        v[ai+3] = ex * r1;              v[ai+4] = ey * r1;              v[ai+5] = 0;
        // inner base
        v[ai+6] = ex * r2;              v[ai+7] = ey * r2;              v[ai+8] = 0;

        // Build indices: 2 triangles per arc segment + 1 arrowhead triangle
        int triCount = segs * 2 + 1;
        short[] idx = new short[triCount * 3];
        int k = 0;
        for (int i = 0; i < segs; i++) {
            short o0 = (short)(i * 2),     i0 = (short)(i * 2 + 1);
            short o1 = (short)(i * 2 + 2), i1 = (short)(i * 2 + 3);
            idx[k++] = o0; idx[k++] = o1; idx[k++] = i0;
            idx[k++] = i0; idx[k++] = o1; idx[k++] = i1;
        }
        int ah0 = (segs + 1) * 2;
        idx[k++] = (short) ah0;
        idx[k++] = (short)(ah0 + 1);
        idx[k++] = (short)(ah0 + 2);

        // Mirror on X for the right-turn arrow
        if (!left) {
            for (int i = 0; i < v.length; i += 3) v[i] = -v[i];
        }

        return mesh(v, idx);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static void rotate2D(float[] v, float angleDeg) {
        if (angleDeg == 0f) return;
        double rad = Math.toRadians(angleDeg);
        float  cos = (float) Math.cos(rad);
        float  sin = (float) Math.sin(rad);
        for (int i = 0; i < v.length; i += 3) {
            float x = v[i], y = v[i + 1];
            v[i]     = x * cos - y * sin;
            v[i + 1] = x * sin + y * cos;
        }
    }

    private static Mesh mesh(float[] v, short[] idx) {
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(v));
        m.setBuffer(VertexBuffer.Type.Index,    3, BufferUtils.createShortBuffer(idx));
        m.updateBound();
        return m;
    }
}
