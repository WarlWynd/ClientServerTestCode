package com.game.client.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Weapon shape archetypes with procedural drawing logic.
 *
 * draw() is called with:
 *   - gc already translated to (cx, canvasY) and x-scaled for facing direction
 *   - p[]  the full canvas-space pose array (Y already negated); may be length 30
 *          (body only) or 36 (body + weapon joints).
 *          Key indices (canvas space, Y-down):
 *            p[10], p[11]  left  hand
 *            p[16], p[17]  right hand  (main hand when facing right)
 *            p[30], p[31]  1H weapon tip  / staff bottom tip
 *            p[32], p[33]  shield centre
 *            p[34], p[35]  2H weapon tip  / staff top tip
 *   - slot  which equipment slot this weapon occupies
 *   - tint  the weapon's own colour (already resolved — never null)
 *   - lineW base stroke width (5.0 — matches PlayerAnimator)
 */
public enum WeaponType {

    NONE {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {}
    },

    // ── Staves ────────────────────────────────────────────────────────────────

    /**
     * Two-handed diagonal pole.
     * Drawn between the two staff-tip weapon joints when available;
     * falls back to extending through both hand positions.
     */
    STAFF {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double x1, y1, x2, y2;
            if (p.length >= 36 && (p[30] != 0 || p[31] != 0 || p[34] != 0 || p[35] != 0)) {
                x1 = p[30]; y1 = p[31];
                x2 = p[34]; y2 = p[35];
            } else {
                double lhx = p[10], lhy = p[11];
                double rhx = p[16], rhy = p[17];
                double dx = rhx - lhx, dy = rhy - lhy;
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len < 1) return;
                double ux = dx / len, uy = dy / len, ext = 14;
                x1 = lhx - ux * ext; y1 = lhy - uy * ext;
                x2 = rhx + ux * ext; y2 = rhy + uy * ext;
            }
            gc.setLineWidth(lineW * 0.75);
            gc.strokeLine(x1, y1, x2, y2);
        }
    },

    // ── Swords ────────────────────────────────────────────────────────────────

    /**
     * One-handed straight sword — blade + crossguard from the main hand.
     */
    SWORD_1H {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double gx = p[16], gy = p[17];
            double tx = (p.length >= 36 && (p[30] != 0 || p[31] != 0)) ? p[30] : gx + 22;
            double ty = (p.length >= 36 && (p[30] != 0 || p[31] != 0)) ? p[31] : gy - 10;
            double dx = tx - gx, dy = ty - gy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;

            gc.setLineWidth(lineW * 0.6);
            gc.strokeLine(gx, gy, tx, ty);                                   // blade
            double cx = gx + ux * len * 0.25, cy = gy + uy * len * 0.25;
            gc.setLineWidth(lineW * 1.1);
            gc.strokeLine(cx + uy * 7, cy - ux * 7, cx - uy * 7, cy + ux * 7); // crossguard
        }
    },

    /**
     * Two-handed greatsword — longer blade, wider crossguard, visible off-hand grip.
     */
    SWORD_2H {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double lhx = p[10], lhy = p[11];  // off hand — lower grip
            double rhx = p[16], rhy = p[17];  // main hand — upper grip
            // Blade extends from main hand forward; pommel behind off hand
            double dx = rhx - lhx, dy = rhy - lhy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;
            double bladeExt = 28, pommelExt = 10;
            double tx = rhx + ux * bladeExt, ty = rhy + uy * bladeExt;  // tip
            double px = lhx - ux * pommelExt, py = lhy - uy * pommelExt; // pommel

            gc.setLineWidth(lineW * 0.7);
            gc.strokeLine(px, py, tx, ty);                                    // full length

            // Pommel ball
            gc.setLineWidth(lineW * 0.5);
            gc.strokeOval(px - 3, py - 3, 6, 6);

            // Crossguard at main-hand position (wider than 1H)
            gc.setLineWidth(lineW * 1.3);
            gc.strokeLine(rhx + uy * 10, rhy - ux * 10, rhx - uy * 10, rhy + ux * 10);

            // Off-hand grip mark on handle
            gc.setLineWidth(lineW * 0.45);
            gc.strokeLine(lhx + uy * 4, lhy - ux * 4, lhx - uy * 4, lhy + ux * 4);
        }
    },

    // ── Axes ──────────────────────────────────────────────────────────────────

    /**
     * One-handed axe — short handle, forward-curved blade head.
     */
    AXE_1H {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double gx = p[16], gy = p[17];
            double tx = gx + 11, ty = gy - 15;          // handle tip
            double dx = tx - gx, dy = ty - gy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;         // along handle
            double nx = -uy, ny = ux;                    // perpendicular (forward)

            gc.setLineWidth(lineW * 0.65);
            gc.strokeLine(gx, gy, tx, ty);               // handle

            // Blade: forward-facing crescent — two lines forming a blade wedge
            gc.setLineWidth(lineW * 0.7);
            double b1x = tx + nx * 10, b1y = ty + ny * 10;  // blade tip (forward)
            double b2x = tx + ux * 5,  b2y = ty + uy * 5;   // top of axe head
            gc.strokeLine(tx, ty, b1x, b1y);             // bottom blade edge
            gc.strokeLine(tx, ty, b2x, b2y);             // top socket edge
            gc.strokeLine(b1x, b1y, b2x, b2y);           // cutting edge arc (straight approx)

            // Butt spike on back
            gc.setLineWidth(lineW * 0.5);
            gc.strokeLine(tx, ty, tx - nx * 5, ty - ny * 5);
        }
    },

    /**
     * Two-handed greataxe — long haft, double-sided massive blade.
     */
    AXE_2H {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double lhx = p[10], lhy = p[11];
            double rhx = p[16], rhy = p[17];
            double dx = rhx - lhx, dy = rhy - lhy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;
            double nx = -uy, ny = ux;                    // perpendicular (forward)
            // Extend haft past both hands
            double hx1 = lhx - ux * 8,  hy1 = lhy - uy * 8;  // butt
            double hx2 = rhx + ux * 10, hy2 = rhy + uy * 10;  // head base

            gc.setLineWidth(lineW * 0.7);
            gc.strokeLine(hx1, hy1, hx2, hy2);          // haft

            // Large blade at haft top — forward and back
            gc.setLineWidth(lineW * 0.75);
            gc.strokeLine(hx2, hy2, hx2 + nx * 16, hy2 + ny * 16);          // front blade
            gc.strokeLine(hx2, hy2, hx2 - nx * 10, hy2 - ny * 10);          // back blade
            gc.strokeLine(hx2 + ux * 7 + nx * 14, hy2 + uy * 7 + ny * 14,
                          hx2 + nx * 16, hy2 + ny * 16);  // front top edge
            gc.strokeLine(hx2 + ux * 7 - nx * 8, hy2 + uy * 7 - ny * 8,
                          hx2 - nx * 10, hy2 - ny * 10);  // back top edge

            // Pommel cap
            gc.setLineWidth(lineW * 0.5);
            gc.strokeOval(hx1 - 3, hy1 - 3, 6, 6);
        }
    },

    // ── Polearms ──────────────────────────────────────────────────────────────

    /**
     * Spear — long shaft with a narrow diamond point at the top.
     */
    SPEAR {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double lhx = p[10], lhy = p[11];
            double rhx = p[16], rhy = p[17];
            double dx = rhx - lhx, dy = rhy - lhy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;
            double nx = -uy, ny = ux;
            double shaftExt = 22, buttExt = 12;
            double tx = rhx + ux * shaftExt, ty = rhy + uy * shaftExt; // spear tip base
            double bx = lhx - ux * buttExt, by = lhy - uy * buttExt;   // butt

            gc.setLineWidth(lineW * 0.6);
            gc.strokeLine(bx, by, tx, ty);               // shaft

            // Diamond spear tip
            double tipLen = 12;
            gc.setLineWidth(lineW * 0.65);
            gc.strokeLine(tx, ty, tx + nx * 4 + ux * tipLen / 2, ty + ny * 4 + uy * tipLen / 2);
            gc.strokeLine(tx, ty, tx - nx * 4 + ux * tipLen / 2, ty - ny * 4 + uy * tipLen / 2);
            gc.strokeLine(tx + nx * 4 + ux * tipLen / 2, ty + ny * 4 + uy * tipLen / 2,
                          tx + ux * tipLen, ty + uy * tipLen);
            gc.strokeLine(tx - nx * 4 + ux * tipLen / 2, ty - ny * 4 + uy * tipLen / 2,
                          tx + ux * tipLen, ty + uy * tipLen);

            // Butt cap
            gc.setLineWidth(lineW * 0.5);
            gc.strokeLine(bx + nx * 3, by + ny * 3, bx - nx * 3, by - ny * 3);
        }
    },

    // ── Daggers ───────────────────────────────────────────────────────────────

    /**
     * One-handed dagger — short double-edged blade with small crossguard.
     * Uses weapon joint [30,31] for blade tip when defined (allows pose-driven direction,
     * e.g. downward stab vs forward slash).
     */
    DAGGER {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double gx = p[16], gy = p[17];
            double tx, ty;
            if (p.length >= 36 && (p[30] != 0 || p[31] != 0)) {
                tx = p[30]; ty = p[31];      // pose-driven tip
            } else {
                tx = gx + 13; ty = gy - 7;  // default: forward-up slash
            }
            double dx = tx - gx, dy = ty - gy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;

            gc.setLineWidth(lineW * 0.55);
            gc.strokeLine(gx, gy, tx, ty);               // blade

            double cx = gx + ux * len * 0.2, cy = gy + uy * len * 0.2;
            gc.setLineWidth(lineW * 0.9);
            gc.strokeLine(cx + uy * 5, cy - ux * 5, cx - uy * 5, cy + ux * 5); // crossguard
        }
    },

    // ── Shields ───────────────────────────────────────────────────────────────

    /**
     * Shield — rounded rectangle at the off-hand / shield joint.
     */
    SHIELD {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double sx = (p.length >= 36 && (p[32] != 0 || p[33] != 0)) ? p[32] : p[10];
            double sy = (p.length >= 36 && (p[32] != 0 || p[33] != 0)) ? p[33] : p[11];
            gc.setLineWidth(lineW * 0.7);
            gc.strokeRoundRect(sx - 7, sy - 10, 14, 20, 4, 4);
            // Centre boss (raised rivet)
            gc.setLineWidth(lineW * 0.5);
            gc.strokeOval(sx - 3, sy - 3, 6, 6);
        }
    },

    // ── Ranged ────────────────────────────────────────────────────────────────

    /**
     * Bow — D-shaped arc in the off hand with string and a nocked arrow.
     */
    BOW {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double lhx = p[10], lhy = p[11];  // off hand holds the bow limb centre
            double rhx = p[16], rhy = p[17];  // draw hand at string

            // Bow stave (D arc)
            gc.setLineWidth(lineW * 0.65);
            gc.beginPath();
            gc.moveTo(lhx - 3, lhy - 15);
            gc.bezierCurveTo(lhx - 18, lhy - 15, lhx - 18, lhy + 15, lhx - 3, lhy + 15);
            gc.stroke();

            // String
            gc.setLineWidth(lineW * 0.25);
            gc.strokeLine(lhx - 3, lhy - 15, rhx, rhy);
            gc.strokeLine(lhx - 3, lhy + 15, rhx, rhy);

            // Nocked arrow — shaft from draw hand, tip pointing forward
            double ax = rhx, ay = rhy;
            double tipX = lhx - 3, tipY = lhy;              // arrow points toward bow centre
            gc.setLineWidth(lineW * 0.4);
            gc.strokeLine(ax, ay, tipX + 14, tipY);         // arrow shaft

            // Arrowhead (small triangle)
            gc.setLineWidth(lineW * 0.5);
            gc.strokeLine(tipX + 14, tipY, tipX + 20, tipY - 3);
            gc.strokeLine(tipX + 14, tipY, tipX + 20, tipY + 3);

            // Fletching at draw hand
            gc.setLineWidth(lineW * 0.35);
            gc.strokeLine(ax, ay, ax - 5, ay - 5);
            gc.strokeLine(ax, ay, ax - 5, ay + 5);
        }
    },

    // ── Blunt ─────────────────────────────────────────────────────────────────

    /**
     * One-handed mace — handle with a flanged ball head.
     */
    MACE_1H {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double gx = p[16], gy = p[17];
            double tx = gx + 10, ty = gy - 17;            // head centre
            double dx = tx - gx, dy = ty - gy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            double ux = dx / len, uy = dy / len;
            double nx = -uy, ny = ux;

            gc.setLineWidth(lineW * 0.65);
            gc.strokeLine(gx, gy, tx, ty);                // handle

            // Head — circle
            gc.setLineWidth(lineW * 0.55);
            gc.strokeOval(tx - 6, ty - 6, 12, 12);

            // Flanges (6 radiating spikes)
            gc.setLineWidth(lineW * 0.5);
            for (int i = 0; i < 6; i++) {
                double angle = Math.PI * i / 3.0;
                double fx = Math.cos(angle) * 8, fy = Math.sin(angle) * 8;
                gc.strokeLine(tx + fx * 0.5, ty + fy * 0.5, tx + fx, ty + fy);
            }
        }
    },

    // ── Exotic ────────────────────────────────────────────────────────────────

    /**
     * Nunchucks — two short handles connected by a chain.
     * Handle 1 is held in the main hand; handle 2 swings freely.
     */
    NUNCHUCKS {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double gx = p[16], gy = p[17];  // grip point

            // Handle 1 — held in hand, points downward-forward
            double h1x = gx + 4,  h1y = gy + 10;  // far end of handle 1
            gc.setLineWidth(lineW * 0.75);
            gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            gc.strokeLine(gx, gy, h1x, h1y);

            // Chain — bezier arc swinging forward
            double h2x = gx + 18, h2y = gy - 4;  // near end of handle 2
            double h2ex = gx + 22, h2ey = gy + 8; // far end of handle 2
            gc.setLineWidth(lineW * 0.25);
            gc.beginPath();
            gc.moveTo(h1x, h1y);
            gc.bezierCurveTo(h1x + 8, h1y - 10, h2x - 4, h2y - 8, h2x, h2y);
            gc.stroke();

            // Chain links — 4 dots along the arc
            gc.setLineWidth(lineW * 0.4);
            double[][] links = {
                { h1x + 3, h1y - 2 }, { h1x + 6, h1y - 5 },
                { h2x - 5, h2y - 3 }, { h2x - 2, h2y - 1 }
            };
            for (double[] l : links) gc.strokeOval(l[0] - 1, l[1] - 1, 2, 2);

            // Handle 2 — swinging freely
            gc.setLineWidth(lineW * 0.75);
            gc.strokeLine(h2x, h2y, h2ex, h2ey);
        }
    },

    /**
     * Morning star / flail — short handle, beaded chain, spiked ball at end.
     * Ball position driven by weapon joint [30,31] when defined; otherwise swings
     * upper-left as a default idle position.
     */
    MORNING_STAR {
        @Override
        public void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW) {
            double gx = p[16], gy = p[17];  // grip (right hand, raised)

            // Handle — short stick going slightly downward from grip
            double hx = gx - 3, hy = gy + 7;
            gc.setLineWidth(lineW * 0.65);
            gc.strokeLine(gx, gy, hx, hy);

            // Ball centre — weapon joint [30,31] if defined, else default overhead-left
            double bx = (p.length >= 36 && (p[30] != 0 || p[31] != 0)) ? p[30] : gx - 12;
            double by = (p.length >= 36 && (p[30] != 0 || p[31] != 0)) ? p[31] : gy - 14;

            // Chain — quadratic bezier from grip to ball, bowing outward
            double cpx = (gx + bx) / 2.0 + (by - gy) * 0.25;
            double cpy = (gy + by) / 2.0 - (gx - bx) * 0.15;
            gc.setLineWidth(lineW * 0.28);
            gc.beginPath();
            gc.moveTo(gx, gy);
            gc.quadraticCurveTo(cpx, cpy, bx, by);
            gc.stroke();

            // Chain links — small ovals sampled along the bezier
            gc.setLineWidth(lineW * 0.45);
            for (int i = 1; i <= 4; i++) {
                double t  = i / 5.0;
                double lx = (1-t)*(1-t)*gx + 2*(1-t)*t*cpx + t*t*bx;
                double ly = (1-t)*(1-t)*gy + 2*(1-t)*t*cpy + t*t*by;
                gc.strokeOval(lx - 2, ly - 2, 4, 4);
            }

            // Spiked ball
            double r = 6;
            gc.setLineWidth(lineW * 0.5);
            gc.strokeOval(bx - r, by - r, r * 2, r * 2);
            gc.setLineWidth(lineW * 0.45);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4.0;
                double sx = Math.cos(angle), sy = Math.sin(angle);
                gc.strokeLine(bx + sx * r, by + sy * r, bx + sx * (r + 5), by + sy * (r + 5));
            }
        }
    };

    /**
     * Returns true when this weapon should be drawn BEFORE the body (appears behind it).
     * Default is false (drawn in front). Override in weapon types carried on the back.
     */
    public boolean rendersBehindBody() { return false; }

    /**
     * @param gc    graphics context, already translated + mirrored to player origin
     * @param p     full canvas-space pose (Y negated); length 30 or 36
     * @param slot  which slot this weapon is in
     * @param tint  weapon colour (already resolved — never null)
     * @param lineW base line width
     */
    public abstract void draw(GraphicsContext gc, double[] p, EquipSlot slot, Color tint, double lineW);
}
