package com.game.admin.ui;

import com.game.admin.AdminConfig;
import com.game.shared.WorldDef;
import javafx.animation.AnimationTimer;
import javafx.scene.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * JavaFX 3D fly-camera preview of a WorldDef terrain.
 *
 * Terrain is rendered as a TriangleMesh sampled at MESH_STEP intervals
 * from the heightmap — sufficient resolution for editing without
 * requiring a full 257×257 vertex grid in the editor.
 *
 * Controls:
 *   WASD        — fly forward / back / strafe
 *   Space / Q   — fly up
 *   Shift / E   — fly down
 *   Mouse drag  — look (yaw + pitch)
 *   Scroll      — zoom speed
 */
public class World3DViewWindow {

    private final int   MESH_STEP;
    private final float LOOK_SENSITIVITY;
    private final float BASE_MOVE_SPEED;
    private final float TURN_SPEED;

    public World3DViewWindow() {
        AdminConfig cfg = new AdminConfig();
        MESH_STEP        = cfg.getPreviewMeshStep();
        LOOK_SENSITIVITY = cfg.getPreviewLookSensitivity();
        BASE_MOVE_SPEED  = cfg.getPreviewMoveSpeed();
        TURN_SPEED       = cfg.getPreviewTurnSpeed();
    }

    // ── Camera state ──────────────────────────────────────────────────────────

    private double camX, camY, camZ;
    private double yaw   = 0;     // degrees; 0 = looking +Z
    private double pitch = 20;    // degrees; positive = looking down (Y+ in JavaFX)

    private final Rotate yawRotate   = new Rotate(0,  Rotate.Y_AXIS);
    private final Rotate pitchRotate = new Rotate(20, Rotate.X_AXIS);

    private double lastMouseX, lastMouseY;

    // ── Key state ─────────────────────────────────────────────────────────────

    private final Set<KeyCode> held = new HashSet<>();

    // ── Scene ─────────────────────────────────────────────────────────────────

    private Group    world;
    private MeshView terrainView;
    private Stage    stage;
    private AnimationTimer loop;

    // ── Entry ─────────────────────────────────────────────────────────────────

    public void show(WorldDef def) {
        world = new Group();

        terrainView = new MeshView(buildMesh(def));
        terrainView.setMaterial(buildMaterial());
        world.getChildren().add(terrainView);

        addLights();

        PerspectiveCamera cam = new PerspectiveCamera(true);
        cam.setNearClip(0.5f);
        cam.setFarClip(5000f);
        cam.setFieldOfView(70);
        cam.getTransforms().setAll(yawRotate, pitchRotate);

        // Start above and in front of terrain centre
        double halfWorld = def.worldSize() / 2.0;
        camX = 0;
        camY = -(def.yScale * 0.15 + 20);
        camZ = -halfWorld * 0.6;
        applyCameraPosition(cam);

        SubScene sub = new SubScene(world, 860, 580, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.web("#1a2030"));
        sub.setCamera(cam);

        Pane root = new Pane(sub);
        sub.widthProperty() .bind(root.widthProperty());
        sub.heightProperty().bind(root.heightProperty());

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 860, 580);
        wireInput(scene, cam);

        stage = new Stage();
        stage.setTitle("3D World Preview");
        stage.setScene(scene);
        stage.setOnHidden(e -> loop.stop());
        stage.show();

        startLoop(cam);
    }

    /** Rebuilds the terrain mesh from an updated WorldDef (called after paint operations). */
    public void refresh(WorldDef def) {
        if (terrainView == null) return;
        terrainView.setMesh(buildMesh(def));
    }

    public Stage getStage() { return stage; }

    // ── Mesh ──────────────────────────────────────────────────────────────────

    private TriangleMesh buildMesh(WorldDef def) {
        int size  = def.heightmapSize;
        int step  = MESH_STEP;
        int gridW = (size - 1) / step + 1;   // e.g. 129 for size=257,step=2
        int gridH = gridW;

        float[] pts  = new float[gridW * gridH * 3];
        float[] uvs  = {0f, 0f};              // single dummy UV — solid colour material
        int[]   faces = new int[(gridW - 1) * (gridH - 1) * 2 * 6];
        int[]   smoothGroups = new int[(gridW - 1) * (gridH - 1) * 2];

        float halfExtent = (size - 1) * def.xzScale / 2f;

        // Vertices
        int vi = 0;
        for (int iz = 0; iz < gridH; iz++) {
            for (int ix = 0; ix < gridW; ix++) {
                int hmx = Math.min(ix * step, size - 1);
                int hmz = Math.min(iz * step, size - 1);
                float h = def.heightmap[hmz * size + hmx];
                pts[vi++] = ix * step * def.xzScale - halfExtent;   // X
                pts[vi++] = -h * def.yScale;                         // Y (Y- = up in JavaFX)
                pts[vi++] = iz * step * def.xzScale - halfExtent;   // Z
            }
        }

        // Faces (two triangles per quad) and smoothing groups
        int fi = 0, si = 0;
        for (int iz = 0; iz < gridH - 1; iz++) {
            for (int ix = 0; ix < gridW - 1; ix++) {
                int v00 = iz * gridW + ix;
                int v10 = iz * gridW + ix + 1;
                int v01 = (iz + 1) * gridW + ix;
                int v11 = (iz + 1) * gridW + ix + 1;

                // Triangle A: v00 v10 v01
                faces[fi++] = v00; faces[fi++] = 0;
                faces[fi++] = v10; faces[fi++] = 0;
                faces[fi++] = v01; faces[fi++] = 0;
                smoothGroups[si++] = 1;

                // Triangle B: v10 v11 v01
                faces[fi++] = v10; faces[fi++] = 0;
                faces[fi++] = v11; faces[fi++] = 0;
                faces[fi++] = v01; faces[fi++] = 0;
                smoothGroups[si++] = 1;
            }
        }

        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints()            .setAll(pts);
        mesh.getTexCoords()         .setAll(uvs);
        mesh.getFaces()             .setAll(faces);
        mesh.getFaceSmoothingGroups().setAll(smoothGroups);
        return mesh;
    }

    private static PhongMaterial buildMaterial() {
        PhongMaterial mat = new PhongMaterial(Color.web("#3a7a28"));
        mat.setSpecularColor(Color.TRANSPARENT);
        return mat;
    }

    private void addLights() {
        javafx.scene.LightBase ambient = new javafx.scene.AmbientLight(Color.gray(0.35));
        javafx.scene.LightBase sun     = new javafx.scene.DirectionalLight(Color.WHITE);
        ((javafx.scene.DirectionalLight) sun).setDirection(
                new javafx.geometry.Point3D(-0.5, -1.0, -0.4));
        world.getChildren().addAll(ambient, sun);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private void wireInput(javafx.scene.Scene scene, PerspectiveCamera cam) {
        scene.setOnKeyPressed (e -> held.add(e.getCode()));
        scene.setOnKeyReleased(e -> held.remove(e.getCode()));

        scene.setOnMousePressed(e -> { lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY(); });
        scene.setOnMouseDragged(e -> {
            if (!e.isSecondaryButtonDown()) return;
            double dx = e.getSceneX() - lastMouseX;
            double dy = e.getSceneY() - lastMouseY;
            lastMouseX = e.getSceneX();
            lastMouseY = e.getSceneY();
            yaw   += dx * LOOK_SENSITIVITY;
            pitch  = Math.max(-85, Math.min(85, pitch + dy * LOOK_SENSITIVITY));
            yawRotate  .setAngle(yaw);
            pitchRotate.setAngle(pitch);
        });

        scene.setOnScroll(e -> {
            // Scroll to adjust move speed — not stored, just a hint (speed scales with height)
        });
    }

    // ── Game loop ─────────────────────────────────────────────────────────────

    private void startLoop(PerspectiveCamera cam) {
        final long[] lastTime = {System.nanoTime()};
        loop = new AnimationTimer() {
            @Override public void handle(long now) {
                float tpf = Math.min((now - lastTime[0]) / 1_000_000_000f, 0.05f);
                lastTime[0] = now;

                // Horizontal speed scales with how high we are above Y=0 (terrain surface)
                float speed = BASE_MOVE_SPEED + (float) Math.max(0, -camY) * 0.4f;

                // A/D turn left/right; arrow keys still strafe
                if (held.contains(KeyCode.D)) {
                    yaw += TURN_SPEED * tpf;
                    yawRotate.setAngle(yaw);
                }
                if (held.contains(KeyCode.A)) {
                    yaw -= TURN_SPEED * tpf;
                    yawRotate.setAngle(yaw);
                }

                double rad  = Math.toRadians(yaw);
                double sinY = Math.sin(rad);
                double cosY = Math.cos(rad);

                // Forward direction: (sinY, 0, cosY) — camera looks +Z at yaw=0
                if (held.contains(KeyCode.W) || held.contains(KeyCode.UP)) {
                    camX += sinY * speed * tpf;
                    camZ += cosY * speed * tpf;
                }
                if (held.contains(KeyCode.S) || held.contains(KeyCode.DOWN)) {
                    camX -= sinY * speed * tpf;
                    camZ -= cosY * speed * tpf;
                }
                // Strafe right: (cosY, 0, -sinY)
                if (held.contains(KeyCode.RIGHT)) {
                    camX += cosY * speed * tpf;
                    camZ -= sinY * speed * tpf;
                }
                if (held.contains(KeyCode.LEFT)) {
                    camX -= cosY * speed * tpf;
                    camZ += sinY * speed * tpf;
                }
                // Vertical (Y- = up in JavaFX)
                if (held.contains(KeyCode.SPACE) || held.contains(KeyCode.Q))
                    camY -= speed * tpf;
                if (held.contains(KeyCode.SHIFT)  || held.contains(KeyCode.E))
                    camY += speed * tpf;

                applyCameraPosition(cam);
            }
        };
        loop.start();
    }

    private void applyCameraPosition(PerspectiveCamera cam) {
        cam.setTranslateX(camX);
        cam.setTranslateY(camY);
        cam.setTranslateZ(camZ);
    }
}
