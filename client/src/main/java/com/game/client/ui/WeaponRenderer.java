package com.game.client.ui;

import java.util.EnumMap;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Manages equipped weapons for a single character and renders them in two passes.
 *
 * Equipment rules:
 *   - Equipping TWO_HANDED clears MAIN_HAND and OFF_HAND.
 *   - Equipping MAIN_HAND or OFF_HAND clears TWO_HANDED.
 *   - Pass null to unequip a slot.
 *
 * Render loop usage (order matters for depth):
 *   renderer.drawBehindBody(gc, animator, cx, canvasY, color);  // BEFORE body
 *   animator.draw(gc, cx, canvasY, color);                       // body
 *   renderer.draw(gc, animator, cx, canvasY, color);             // AFTER body
 */
public class WeaponRenderer {

    private static final double LINE_W = 5.0;  // matches PlayerAnimator.LINE_W

    private Weapon mainHand  = null;
    private Weapon offHand   = null;
    private Weapon twoHanded = null;

    /** Per-slot override for behind-body rendering. Absent = use WeaponType default. */
    private final EnumMap<EquipSlot, Boolean> behindBodyOverrides = new EnumMap<>(EquipSlot.class);

    // ── Equipment API ─────────────────────────────────────────────────────────

    /**
     * Equip a weapon in the given slot. Pass null to unequip.
     * Enforces two-handed exclusivity automatically.
     */
    public void equip(EquipSlot slot, Weapon weapon) {
        switch (slot) {
            case MAIN_HAND  -> { mainHand  = weapon; twoHanded = null; }
            case OFF_HAND   -> { offHand   = weapon; twoHanded = null; }
            case TWO_HANDED -> { twoHanded = weapon; mainHand  = null; offHand = null; }
        }
    }

    public void unequip(EquipSlot slot) { equip(slot, null); }
    public void unequipAll()            { mainHand = null; offHand = null; twoHanded = null; }

    /**
     * Override the behind-body rendering flag for a specific slot.
     * Use this when a pose determines depth (e.g. staff carried on back vs held in front).
     * Call setRendersBehindBody(slot, false) — or clearRendersBehindBody(slot) — to revert
     * to the WeaponType's default.
     */
    public void setRendersBehindBody(EquipSlot slot, boolean behind) {
        behindBodyOverrides.put(slot, behind);
    }

    public void clearRendersBehindBody(EquipSlot slot) {
        behindBodyOverrides.remove(slot);
    }

    public Weapon getEquipped(EquipSlot slot) {
        return switch (slot) {
            case MAIN_HAND  -> mainHand;
            case OFF_HAND   -> offHand;
            case TWO_HANDED -> twoHanded;
        };
    }

    public boolean hasAnyWeapon() {
        return mainHand != null || offHand != null || twoHanded != null;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Draw weapons that render BEHIND the body.
     * Call this BEFORE PlayerAnimator.draw().
     */
    public void drawBehindBody(GraphicsContext gc,
                               PlayerAnimator animator,
                               double cx, double canvasY,
                               Color bodyColor) {
        drawWithFilter(gc, animator, cx, canvasY, bodyColor, true);
    }

    /**
     * Draw weapons that render IN FRONT of the body.
     * Call this AFTER PlayerAnimator.draw().
     */
    public void draw(GraphicsContext gc,
                     PlayerAnimator animator,
                     double cx, double canvasY,
                     Color bodyColor) {
        drawWithFilter(gc, animator, cx, canvasY, bodyColor, false);
    }

    private void drawWithFilter(GraphicsContext gc,
                                PlayerAnimator animator,
                                double cx, double canvasY,
                                Color bodyColor,
                                boolean behindBody) {
        if (!hasAnyWeapon()) return;
        double[] raw = animator.getCurrentCanvasPose();
        boolean  flip = !animator.isFacingRight();

        gc.save();
        gc.translate(cx, canvasY);
        if (flip) gc.scale(-1, 1);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);

        // Draw order: off-hand, two-handed, main hand
        drawSlot(gc, raw, EquipSlot.OFF_HAND,   offHand,   bodyColor, behindBody);
        drawSlot(gc, raw, EquipSlot.TWO_HANDED, twoHanded, bodyColor, behindBody);
        drawSlot(gc, raw, EquipSlot.MAIN_HAND,  mainHand,  bodyColor, behindBody);

        gc.restore();
    }

    private void drawSlot(GraphicsContext gc, double[] p,
                          EquipSlot slot, Weapon weapon, Color bodyColor,
                          boolean behindBody) {
        if (weapon == null) return;
        boolean renderBehind = behindBodyOverrides.containsKey(slot)
                ? behindBodyOverrides.get(slot)
                : weapon.type.rendersBehindBody();
        if (renderBehind != behindBody) return;
        Color c = weapon.tint != null ? weapon.tint : bodyColor;
        gc.setStroke(c);
        weapon.type.draw(gc, p, slot, c, LINE_W);
    }
}
