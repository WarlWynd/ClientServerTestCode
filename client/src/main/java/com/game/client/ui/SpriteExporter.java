package com.game.client.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.SnapshotParameters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders PlayerAnimator stick-figure poses to 128×128 PNG files.
 *
 * Output (per state):
 *   <state>_0.png, <state>_1.png, …  — individual frames (transparent background)
 *   <state>_sheet.png               — all frames in a horizontal sprite sheet
 *
 * Sprites are rendered in white so they can be tinted at runtime.
 * Call exportAll() on the JavaFX Application Thread.
 */
public class SpriteExporter {

    public static final int FRAME_SIZE = 128;

    private static final double SCALE  = 2.0;          // game-units → pixels
    private static final double FEET_X = FRAME_SIZE / 2.0;
    private static final double FEET_Y = FRAME_SIZE - 10.0;
    private static final double LINE_W = 5.0;
    private static final double HEAD_R = 8.0;

    private static final Color SPRITE_COLOR = Color.WHITE;

    /** Renders and saves all states. Returns the number of files written. */
    public static int exportAll(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        int count = 0;
        for (PlayerAnimator.State state : PlayerAnimator.State.values()) {
            double[][] frames   = PlayerAnimator.getFrames(state);
            String     name     = state.name().toLowerCase();

            // Individual frames
            for (int f = 0; f < frames.length; f++) {
                WritableImage img = renderFrame(frames[f]);
                save(img, outputDir.resolve(name + "_" + f + ".png").toFile());
                count++;
            }

            // Sprite sheet — all frames side by side
            WritableImage sheet = renderSheet(frames);
            save(sheet, outputDir.resolve(name + "_sheet.png").toFile());
            count++;
        }
        return count;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private static WritableImage renderFrame(double[] pose) {
        Canvas          canvas = new Canvas(FRAME_SIZE, FRAME_SIZE);
        GraphicsContext gc     = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, FRAME_SIZE, FRAME_SIZE);

        gc.save();
        gc.translate(FEET_X, FEET_Y);
        gc.scale(SCALE, SCALE);
        applyStyle(gc);
        drawPose(gc, pose);
        gc.restore();

        return snapshot(canvas);
    }

    private static WritableImage renderSheet(double[][] frames) {
        double sheetW = (double) FRAME_SIZE * frames.length;
        Canvas          canvas = new Canvas(sheetW, FRAME_SIZE);
        GraphicsContext gc     = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, sheetW, FRAME_SIZE);

        for (int f = 0; f < frames.length; f++) {
            gc.save();
            gc.translate(FRAME_SIZE * f + FEET_X, FEET_Y);
            gc.scale(SCALE, SCALE);
            applyStyle(gc);
            drawPose(gc, frames[f]);
            gc.restore();
        }
        return snapshot(canvas);
    }

    /** Snapshot with transparent background (default fill is white — must be explicit). */
    private static WritableImage snapshot(Canvas canvas) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }

    private static void applyStyle(GraphicsContext gc) {
        gc.setFill(SPRITE_COLOR);
        gc.setStroke(SPRITE_COLOR);
        gc.setLineWidth(LINE_W);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
    }

    private static void drawPose(GraphicsContext gc, double[] p) {
        double hx=p[0], hy=-p[1], nkx=p[2], nky=-p[3], hpx=p[4], hpy=-p[5];
        double lsx=p[6],lsy=-p[7],lex=p[8],ley=-p[9],lhx=p[10],lhy=-p[11];
        double rsx=p[12],rsy=-p[13],rex=p[14],rey=-p[15],rhx=p[16],rhy=-p[17];
        double llhx=p[18],llhy=-p[19],lkx=p[20],lky=-p[21],lfx=p[22],lfy=-p[23];
        double rlhx=p[24],rlhy=-p[25],rkx=p[26],rky=-p[27],rfx=p[28],rfy=-p[29];

        gc.fillOval(hx - HEAD_R, hy - HEAD_R, HEAD_R * 2, HEAD_R * 2);
        gc.strokeLine(nkx, nky, hpx, hpy);
        gc.strokeLine(lsx, lsy, lex, ley);  gc.strokeLine(lex, ley, lhx, lhy);
        gc.strokeLine(rsx, rsy, rex, rey);  gc.strokeLine(rex, rey, rhx, rhy);
        gc.strokeLine(llhx, llhy, lkx, lky); gc.strokeLine(lkx, lky, lfx, lfy);
        gc.strokeLine(rlhx, rlhy, rkx, rky); gc.strokeLine(rkx, rky, rfx, rfy);
    }

    // ── IO ────────────────────────────────────────────────────────────────────

    private static void save(WritableImage img, File file) throws IOException {
        BufferedImage buf = SwingFXUtils.fromFXImage(img, null);
        ImageIO.write(buf, "png", file);
    }
}
