package com.game.client.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * On-screen virtual joystick for touch input.
 *
 * Draws a base ring and a movable thumb in the bottom-left of its canvas.
 * Exposes normalized dx/dy in [-1, 1] via {@link #getDx()} and {@link #getDy()}.
 *
 * Responds to touch events (native on Android/iOS; JavaFX also synthesizes
 * touch from mouse on desktop so it works for testing there too).
 */
public class VirtualJoystick extends Canvas {

    private static final double BASE_R  = 60.0;
    private static final double THUMB_R = 25.0;
    private static final double MARGIN  = 20.0;

    private final double centerX;
    private final double centerY;

    private double  thumbDx      = 0;
    private double  thumbDy      = 0;
    private boolean active       = false;
    private int     activeTouchId = -1;

    public VirtualJoystick(double width, double height) {
        super(width, height);
        centerX = BASE_R + MARGIN;
        centerY = height - BASE_R - MARGIN;

        setOnTouchPressed(e -> {
            if (!active) {
                activeTouchId = e.getTouchPoint().getId();
                active = true;
            }
            e.consume();
        });

        setOnTouchMoved(e -> {
            for (var tp : e.getTouchPoints()) {
                if (tp.getId() == activeTouchId) {
                    double rawDx = tp.getX() - centerX;
                    double rawDy = tp.getY() - centerY;
                    double dist  = Math.sqrt(rawDx * rawDx + rawDy * rawDy);
                    double clamp = Math.min(dist, BASE_R);
                    double norm  = Math.max(dist, 0.001);
                    thumbDx = (clamp / BASE_R) * (rawDx / norm);
                    thumbDy = (clamp / BASE_R) * (rawDy / norm);
                    draw();
                }
            }
            e.consume();
        });

        setOnTouchReleased(e -> {
            if (e.getTouchPoint().getId() == activeTouchId) {
                thumbDx = 0;
                thumbDy = 0;
                active  = false;
                activeTouchId = -1;
                draw();
            }
            e.consume();
        });

        draw();
    }

    public double getDx() { return thumbDx; }
    public double getDy() { return thumbDy; }

    private void draw() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, getWidth(), getHeight());

        // Base ring
        gc.setFill(Color.color(1, 1, 1, 0.12));
        gc.fillOval(centerX - BASE_R, centerY - BASE_R, BASE_R * 2, BASE_R * 2);
        gc.setStroke(Color.color(1, 1, 1, 0.35));
        gc.setLineWidth(2);
        gc.strokeOval(centerX - BASE_R, centerY - BASE_R, BASE_R * 2, BASE_R * 2);

        // Thumb
        double tx = centerX + thumbDx * BASE_R;
        double ty = centerY + thumbDy * BASE_R;
        gc.setFill(Color.color(1, 1, 1, 0.55));
        gc.fillOval(tx - THUMB_R, ty - THUMB_R, THUMB_R * 2, THUMB_R * 2);
    }
}
