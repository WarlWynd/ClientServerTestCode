package com.game.admin.ui;

import com.game.shared.WorldConstants;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.paint.*;
import javafx.scene.shape.Box;
import javafx.scene.transform.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * First-person 3D preview of a Board.
 *
 * Tile types: FLOOR, WALL, DOOR_CLOSED, DOOR_OPEN, VOID.
 * Furnishings are stored in board.tileItems keyed by tile coordinate.
 * Item position strings: N, E, S, W, Center.
 */
public class Board3DViewWindow {

    private static final double TILE   = WorldConstants.TILE_SIZE;
    private static final double WALL_H = WorldConstants.WALL_HEIGHT;
    private static final double EYE_H  = 1.6;
    private static final double HALF   = TILE / 2.0;

    // Camera local look = +Z. R_Y(θ) on (0,0,1) = (sinθ, 0, cosθ).
    // NORTH=-Z: θ=180  EAST=+X: θ=90  SOUTH=+Z: θ=0  WEST=-X: θ=-90
    private static final double[] YAW = {180.0, 90.0, 0.0, -90.0};

    // Distance furniture center is offset from tile center toward its wall.
    private static final double FURN_OFF = HALF - 0.43;

    // ── Static materials (independent of texture choice) ──────────────────────

    // Texture-dependent materials are created at show() time from board settings.
    private static final PhongMaterial MAT_WOOD_FRAME = mat("#7a5828", "#b08040", 24);
    private static final PhongMaterial MAT_DOOR_PANEL = mat("#3c1c08", "#604020", 16);
    private static final PhongMaterial MAT_VOID       = mat("#050508", "#050508",  2);
    private static final PhongMaterial MAT_TABLE      = mat("#8a6030", "#c09050", 22);
    private static final PhongMaterial MAT_CHAIR      = mat("#7a5025", "#a07840", 20);
    private static final PhongMaterial MAT_BARREL     = mat("#6a4820", "#9a6830", 16);
    private static final PhongMaterial MAT_CHEST_BOX  = mat("#7a5828", "#b08040", 20);
    private static final PhongMaterial MAT_CHEST_LID  = mat("#8a6820", "#d0a040", 48);
    private static final PhongMaterial MAT_IRON       = mat("#404048", "#909090", 64);
    private static final PhongMaterial MAT_FIRE       = mat("#ff8810", "#ffee80", 100);
    private static final PhongMaterial MAT_CLOTH      = mat("#6a4055", "#9a6080", 10);
    private static final PhongMaterial MAT_BOOK       = mat("#203060", "#4060a0", 18);

    // ── State ─────────────────────────────────────────────────────────────────

    private final BoardDevPanel.Board board;
    private PerspectiveCamera camera;
    private PointLight        torchLight;
    private Stage             stage;

    // Persistent transform objects so their properties can be animated.
    private final Rotate yawRotate   = new Rotate(0,  Rotate.Y_AXIS);
    private final Rotate pitchRotate = new Rotate(12, Rotate.X_AXIS);
    private Timeline currentAnim;
    private boolean  firstPosition = true;

    public Board3DViewWindow(BoardDevPanel.Board board) { this.board = board; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void show() {
        Group world = new Group();
        buildGeometry(world);

        AmbientLight ambient = new AmbientLight(Color.gray(0.2));
        torchLight = new PointLight(Color.web("#ffe8a8"));
        torchLight.setMaxRange(TILE * 8);
        world.getChildren().addAll(ambient, torchLight);

        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.05);
        camera.setFarClip(TILE * (board.width + board.depth));
        camera.setFieldOfView(75);
        // Transforms applied right-to-left: pitch (X) first, then yaw (Y).
        camera.getTransforms().setAll(yawRotate, pitchRotate);

        SubScene sub = new SubScene(world, 800, 540, true, SceneAntialiasing.DISABLED);
        sub.setFill(Color.web("#08080f"));
        sub.setCamera(camera);

        Pane root = new Pane(sub);
        root.setStyle("-fx-background-color: #08080f;");
        sub.widthProperty() .bind(root.widthProperty());
        sub.heightProperty().bind(root.heightProperty());

        stage = new Stage();
        stage.setTitle("3D View — " + board.name);
        stage.setScene(new javafx.scene.Scene(root, 800, 540));
        stage.show();
    }

    public void updatePlayer(int px, int pz, int facing) {
        if (camera == null) return;
        double cx = px * TILE + HALF;
        double cz = pz * TILE + HALF;
        animateTo(cx, cz, YAW[facing], 220);
    }

    public Stage getStage() { return stage; }

    /** Preview tile: first call snaps instantly, subsequent A/D turns animate. */
    public void positionPreviewCamera(int px, int pz, int facing) {
        if (camera == null) return;
        double cx = px * TILE + HALF;
        double cz = pz * TILE + HALF;
        animateTo(cx, cz, YAW[facing], 180);
    }

    // ── Camera animation ──────────────────────────────────────────────────────

    private void animateTo(double tx, double tz, double targetYaw, int ms) {
        // First placement is always instant so the scene doesn't fly in from origin.
        if (firstPosition) {
            firstPosition = false;
            yawRotate.setAngle(targetYaw);
            camera.setTranslateX(tx);
            camera.setTranslateY(-EYE_H);
            camera.setTranslateZ(tz);
            torchLight.setTranslateX(tx);
            torchLight.setTranslateY(-EYE_H);
            torchLight.setTranslateZ(tz);
            return;
        }

        if (currentAnim != null) currentAnim.stop();

        double fromX   = camera.getTranslateX();
        double fromZ   = camera.getTranslateZ();
        double fromYaw = yawRotate.getAngle();

        // Rotate the short way around (never more than 180°).
        double delta = targetYaw - fromYaw;
        while (delta >  180) delta -= 360;
        while (delta < -180) delta += 360;
        double toYaw = fromYaw + delta;

        KeyFrame kf0 = new KeyFrame(Duration.ZERO,
            new KeyValue(camera.translateXProperty(), fromX),
            new KeyValue(camera.translateZProperty(), fromZ),
            new KeyValue(torchLight.translateXProperty(), fromX),
            new KeyValue(torchLight.translateZProperty(), fromZ),
            new KeyValue(yawRotate.angleProperty(), fromYaw));

        KeyFrame kf1 = new KeyFrame(Duration.millis(ms),
            new KeyValue(camera.translateXProperty(), tx,    Interpolator.EASE_BOTH),
            new KeyValue(camera.translateZProperty(), tz,    Interpolator.EASE_BOTH),
            new KeyValue(torchLight.translateXProperty(), tx, Interpolator.EASE_BOTH),
            new KeyValue(torchLight.translateZProperty(), tz, Interpolator.EASE_BOTH),
            new KeyValue(yawRotate.angleProperty(), toYaw,   Interpolator.EASE_BOTH));

        currentAnim = new Timeline(kf0, kf1);
        // Normalize accumulated yaw so it never drifts far from [-360, 360].
        double finalYaw = targetYaw;
        currentAnim.setOnFinished(e -> yawRotate.setAngle(finalYaw));
        currentAnim.play();
    }

    // ── Geometry dispatch ─────────────────────────────────────────────────────

    private void buildGeometry(Group world) {
        for (int z = 0; z < board.depth; z++) {
            for (int x = 0; x < board.width; x++) {
                double cx = x * TILE + HALF;
                double cz = z * TILE + HALF;
                switch (board.tileAt(x, z)) {
                    case "FLOOR"       -> buildFloor     (world, x, z, cx, cz);
                    case "WALL"        -> buildWall      (world, x, z, cx, cz);
                    case "DOOR_CLOSED" -> buildDoorClosed(world, x, z, cx, cz);
                    case "DOOR_OPEN"   -> buildDoorOpen  (world, x, z, cx, cz);
                    case "VOID"        -> buildVoid      (world, cx, cz);
                }
            }
        }
    }

    // ── Tile builders ─────────────────────────────────────────────────────────

    private void buildFloor(Group world, int gx, int gz, double cx, double cz) {
        PhongMaterial fm = floorMat(gx, gz), cm = ceilMat(gx, gz);
        world.getChildren().add(box(cx, -0.075,          cz, TILE-0.08, 0.15, TILE-0.08, fm));
        world.getChildren().add(box(cx, -(WALL_H+0.075), cz, TILE-0.08, 0.15, TILE-0.08, cm));
        for (BoardDevPanel.TileItem item : board.getTileItems(gx, gz))
            renderItem(world, item.itemId, cx, cz, item.position);
    }

    private void buildWall(Group world, int gx, int gz, double cx, double cz) {
        world.getChildren().add(box(cx, -WALL_H/2.0, cz, TILE+0.1, WALL_H+0.4, TILE+0.1, wallMat(gx, gz)));
    }

    private void buildDoorClosed(Group world, int gx, int gz, double cx, double cz) {
        PhongMaterial df = doorFloorMat(gx, gz), cm = ceilMat(gx, gz);
        world.getChildren().add(box(cx, -0.075,          cz, TILE-0.08, 0.15, TILE-0.08, df));
        world.getChildren().add(box(cx, -(WALL_H+0.075), cz, TILE-0.08, 0.15, TILE-0.08, cm));
        cornerPosts(world, cx, cz);
        double ph = WALL_H * 0.88, py = -(ph/2.0 + 0.15);
        world.getChildren().add(box(cx, py, cz, TILE*0.84, ph, 0.10, MAT_DOOR_PANEL));
        world.getChildren().add(box(cx, py, cz, 0.10, ph, TILE*0.84, MAT_DOOR_PANEL));
    }

    private void buildDoorOpen(Group world, int gx, int gz, double cx, double cz) {
        PhongMaterial df = doorFloorMat(gx, gz), cm = ceilMat(gx, gz);
        world.getChildren().add(box(cx, -0.075,          cz, TILE-0.08, 0.15, TILE-0.08, df));
        world.getChildren().add(box(cx, -(WALL_H+0.075), cz, TILE-0.08, 0.15, TILE-0.08, cm));
        cornerPosts(world, cx, cz);
        double ly = -(WALL_H - 0.15);
        world.getChildren().add(box(cx, ly, cz, TILE+0.05, 0.22, 0.22, MAT_WOOD_FRAME));
        world.getChildren().add(box(cx, ly, cz, 0.22, 0.22, TILE+0.05, MAT_WOOD_FRAME));
        for (BoardDevPanel.TileItem item : board.getTileItems(gx, gz))
            renderItem(world, item.itemId, cx, cz, item.position);
    }

    private void buildVoid(Group world, double cx, double cz) {
        world.getChildren().add(box(cx, TILE*2, cz, TILE-0.08, 0.1, TILE-0.08, MAT_VOID));
    }

    // ── Item rendering ────────────────────────────────────────────────────────

    private void renderItem(Group world, String id, double cx, double cz, String pos) {
        double[] off = posOffset(pos);
        double fx = cx + off[0], fz = cz + off[1], yaw = off[2];
        switch (id) {
            case "table"     -> addTable    (world, fx, fz, yaw);
            case "chair"     -> addChair    (world, fx, fz, yaw);
            case "barrel"    -> addBarrel   (world, fx, fz, yaw);
            case "chest"     -> addChest    (world, fx, fz, yaw);
            case "torch"     -> addTorch    (world, cx, cz, pos);
            case "bookshelf" -> addBookshelf(world, fx, fz, yaw);
            case "bed"       -> addBed      (world, fx, fz, yaw);
            case "rug"       -> addRug      (world, cx, cz);
        }
    }

    /** Returns {offsetX, offsetZ, yaw} for a position string. */
    private static double[] posOffset(String pos) {
        return switch (pos) {
            case "N" -> new double[]{ 0,        -FURN_OFF, 0  };
            case "E" -> new double[]{ FURN_OFF,  0,        270};
            case "S" -> new double[]{ 0,         FURN_OFF, 180};
            case "W" -> new double[]{-FURN_OFF,  0,        90 };
            default  -> new double[]{ 0,         0,        180}; // Center
        };
    }

    // ── Furniture ─────────────────────────────────────────────────────────────

    private void addTable(Group world, double tx, double tz, double yaw) {
        Group g = new Group();
        g.getChildren().add(localBox(0, -0.74, 0,  1.50, 0.08, 0.70, MAT_TABLE));
        double lx = 0.65, lz = 0.28, ly = -0.37;
        for (int sx : new int[]{-1,1}) for (int sz : new int[]{-1,1})
            g.getChildren().add(localBox(sx*lx, ly, sz*lz, 0.09, 0.74, 0.09, MAT_TABLE));
        placeGroup(world, g, tx, tz, yaw);
    }

    private void addChair(Group world, double tx, double tz, double yaw) {
        Group g = new Group();
        g.getChildren().add(localBox(0, -0.45, 0,  0.46, 0.07, 0.46, MAT_CHAIR));
        double lx = 0.18, lz = 0.18, ly = -0.225;
        for (int sx : new int[]{-1,1}) for (int sz : new int[]{-1,1})
            g.getChildren().add(localBox(sx*lx, ly, sz*lz, 0.07, 0.43, 0.07, MAT_CHAIR));
        g.getChildren().add(localBox(0, -0.67, -0.19, 0.46, 0.44, 0.07, MAT_CHAIR));
        placeGroup(world, g, tx, tz, yaw);
    }

    private void addBarrel(Group world, double tx, double tz, double yaw) {
        Group g = new Group();
        g.getChildren().add(localBox(0, -0.35, 0, 0.32, 0.58, 0.32, MAT_BARREL));
        g.getChildren().add(localBox(0, -0.07, 0, 0.35, 0.05, 0.35, MAT_IRON));
        g.getChildren().add(localBox(0, -0.63, 0, 0.35, 0.05, 0.35, MAT_IRON));
        g.getChildren().add(localBox(0, -0.35, 0, 0.35, 0.05, 0.35, MAT_IRON));
        placeGroup(world, g, tx, tz, yaw);
    }

    private void addChest(Group world, double tx, double tz, double yaw) {
        Group g = new Group();
        // Shift up 0.11 so chest bottom sits on floor surface (floor top = y -0.15)
        g.getChildren().add(localBox(0, -0.29, 0,  0.56, 0.28, 0.38, MAT_CHEST_BOX));
        g.getChildren().add(localBox(0, -0.18, 0,  0.56, 0.08, 0.38, MAT_CHEST_LID));
        g.getChildren().add(localBox(0, -0.21, -0.20, 0.09, 0.09, 0.05, MAT_IRON));
        placeGroup(world, g, tx, tz, yaw);
    }

    /** Torch is wall-mounted; uses raw position string to place on correct wall face. */
    private void addTorch(Group world, double cx, double cz, String pos) {
        double wallD = HALF - 0.10;
        double mountY = -(WALL_H * 0.72);
        double tx, tz;
        switch (pos) {
            case "N" -> { tx = cx;        tz = cz - wallD; }
            case "E" -> { tx = cx + wallD; tz = cz;        }
            case "S" -> { tx = cx;        tz = cz + wallD; }
            default  -> { tx = cx - wallD; tz = cz;        } // W / Center
        }
        world.getChildren().add(box(tx, mountY + 0.04, tz, 0.05, 0.06, 0.14, MAT_IRON));
        world.getChildren().add(box(tx, mountY - 0.10, tz, 0.05, 0.20, 0.05, MAT_TABLE));
        world.getChildren().add(box(tx, mountY - 0.25, tz, 0.10, 0.14, 0.10, MAT_FIRE));
    }

    private void addBookshelf(Group world, double tx, double tz, double yaw) {
        Group g = new Group();
        double h = WALL_H * 0.70, cy = -(WALL_H * 0.35);
        g.getChildren().add(localBox(0, cy, 0.10, 0.88, h, 0.26, MAT_TABLE));
        for (double sy : new double[]{-0.15, -0.58, -1.00})
            g.getChildren().add(localBox(0, cy + sy, -0.04, 0.82, 0.05, 0.24, MAT_IRON));
        g.getChildren().add(localBox( 0.30, cy - 0.38, -0.06, 0.10, 0.38, 0.20, MAT_BOOK));
        g.getChildren().add(localBox(-0.20, cy - 0.38, -0.06, 0.10, 0.30, 0.20, MAT_DOOR_PANEL));
        g.getChildren().add(localBox( 0.10, cy - 0.80, -0.06, 0.12, 0.34, 0.20, MAT_BOOK));
        placeGroup(world, g, tx, tz, yaw);
    }

    private void addBed(Group world, double tx, double tz, double yaw) {
        Group g = new Group();
        g.getChildren().add(localBox(0, -0.20, 0,    0.65, 0.14, 1.10, MAT_TABLE));
        g.getChildren().add(localBox(0, -0.12, 0.05, 0.58, 0.11, 0.92, MAT_CLOTH));
        g.getChildren().add(localBox(0, -0.07, -0.40, 0.50, 0.09, 0.20, MAT_CLOTH));
        g.getChildren().add(localBox(0, -0.45, -0.50, 0.65, 0.50, 0.08, MAT_TABLE));
        placeGroup(world, g, tx, tz, yaw);
    }

    private void addRug(Group world, double cx, double cz) {
        // Wider rug (85% of tile) so edges reach near the walls and appear in the lower FOV.
        world.getChildren().add(box(cx, -0.16, cz, TILE*0.85, 0.02, TILE*0.85, MAT_CLOTH));
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static void placeGroup(Group world, Group g, double tx, double tz, double yaw) {
        g.getTransforms().add(new Rotate(yaw, Rotate.Y_AXIS));
        g.setTranslateX(tx); g.setTranslateZ(tz);
        world.getChildren().add(g);
    }

    private void cornerPosts(Group world, double cx, double cz) {
        double ps = 0.28, py = -WALL_H/2.0, edge = HALF - ps/2.0;
        for (int sx : new int[]{-1,1}) for (int sz : new int[]{-1,1})
            world.getChildren().add(box(cx+sx*edge, py, cz+sz*edge, ps, WALL_H, ps, MAT_WOOD_FRAME));
    }

    private static Box box(double tx, double ty, double tz,
                           double w, double h, double d, PhongMaterial mat) {
        Box b = new Box(w, h, d);
        b.setMaterial(mat);
        b.setTranslateX(tx); b.setTranslateY(ty); b.setTranslateZ(tz);
        return b;
    }

    private static Box localBox(double tx, double ty, double tz,
                                double w, double h, double d, PhongMaterial mat) {
        return box(tx, ty, tz, w, h, d, mat);
    }

    // ── Per-tile texture lookups ──────────────────────────────────────────────

    private PhongMaterial wallMat(int x, int z) {
        String tex = board.getWallTexture(x, z);
        return switch (tex) {
            case "Brick"  -> textured("Brick",  "#c07858", 16);
            case "Wood"   -> textured("Wood",   "#d0a060", 12);
            case "Ice"    -> textured("Ice",    "#e0f0ff", 80);
            case "Cave"   -> textured("Cave",   "#806040",  6);
            case "Marble" -> textured("Marble", "#e0dcd8", 64);
            default       -> textured("Stone",  "#8090a0", 32);
        };
    }

    private PhongMaterial ceilMat(int x, int z) {
        String tex = board.getWallTexture(x, z);
        String name = switch (tex) {
            case "Brick" -> "Brick"; case "Wood"   -> "Wood";
            case "Ice"   -> "Ice";   case "Cave"   -> "Cave";
            case "Marble"-> "Marble";default        -> "Stone";
        };
        return texturedDark(name, 0.22, "#303030", 4);
    }

    private PhongMaterial floorMat(int x, int z) {
        String tex = board.getFloorTexture(x, z);
        return switch (tex) {
            case "Wood Planks" -> textured("Wood Planks", "#d0a060", 18);
            case "Dirt"        -> textured("Dirt",        "#907050",  4);
            case "Marble"      -> textured("Marble",      "#e8e4e0", 72);
            case "Grass"       -> textured("Grass",       "#60a040",  6);
            case "Ice"         -> textured("Ice",         "#d0e8ff", 90);
            default            -> textured("Stone",       "#a09080", 12);
        };
    }

    private PhongMaterial doorFloorMat(int x, int z) {
        String tex = board.getFloorTexture(x, z);
        String name = switch (tex) {
            case "Wood Planks" -> "Wood Planks"; case "Dirt"  -> "Dirt";
            case "Marble"      -> "Marble";      case "Grass" -> "Grass";
            case "Ice"         -> "Ice";         default      -> "Stone";
        };
        return texturedDark(name, 0.6, "#505050", 8);
    }

    private static PhongMaterial textured(String texName, String specHex, double specPow) {
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(ProceduralTextures.get(texName));
        m.setSpecularColor(Color.web(specHex));
        m.setSpecularPower(specPow);
        return m;
    }

    private static PhongMaterial texturedDark(String texName, double brightness, String specHex, double specPow) {
        PhongMaterial m = new PhongMaterial(Color.gray(brightness));
        m.setDiffuseMap(ProceduralTextures.get(texName));
        m.setSpecularColor(Color.web(specHex));
        m.setSpecularPower(specPow);
        return m;
    }

    private static PhongMaterial mat(String diffuse, String specular, double power) {
        PhongMaterial m = new PhongMaterial(Color.web(diffuse));
        m.setSpecularColor(Color.web(specular));
        m.setSpecularPower(power);
        return m;
    }
}
