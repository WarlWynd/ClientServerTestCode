package com.game.client.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.control.cell.ComboBoxListCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Interactive pose editor for PlayerAnimator stick-figure sprites.
 *
 * Drag the coloured joint handles on the canvas to reposition limbs.
 * The Java code panel updates in real-time — copy it back into
 * PlayerAnimator.java (inside the appropriate pose array) to make
 * changes permanent.
 *
 * Joint colour legend:
 *   Red    — head / neck / spine
 *   Blue   — left arm
 *   Green  — right arm
 *   Amber  — left leg
 *   Purple — right leg
 */
public class SpriteEditorPanel {

    // ── Editor drawing constants ──────────────────────────────────────────────
    private static final double SCALE      = 3.5;
    private static final Path  BACKUP_DIR = Paths.get("client/src/main/resources/graphics/sprites/backups");
    private static final double CANVAS_W  = 520;
    private static final double CANVAS_H  = 330;
    private static final double FEET_CX   = CANVAS_W / 2.0;
    private static final double FEET_CY   = 275.0;
    private static final double HANDLE_R  = 6.5;
    private static final double LINE_W    = 5.0 * SCALE / 3.5;  // scaled line width
    private static final double HEAD_R    = 8.0 * SCALE / 3.5;  // scaled head radius
    private static final int    JOINTS    = 18; // 15 body + 3 weapon attachment points

    private static final String[] JOINT_NAMES = {
        "Head", "Neck", "Hip(spine)",
        "L.Shoulder", "L.Elbow", "L.Hand",
        "R.Shoulder", "R.Elbow", "R.Hand",
        "L.HipJoint", "L.Knee",  "L.Foot",
        "R.HipJoint", "R.Knee",  "R.Foot",
        "1H Weapon Tip", "Shield Edge", "2H Weapon Tip"
    };

    // ── State ─────────────────────────────────────────────────────────────────
    /** Mutable copy of all pose data — one double[][] per State ordinal, per Direction. */
    private final java.util.Map<PlayerAnimator.Direction, double[][][]> dirPoses = new java.util.HashMap<>();

    private PlayerAnimator.State     currentState     = PlayerAnimator.State.IDLE;
    private PlayerAnimator.Direction currentDirection = PlayerAnimator.Direction.FRONT;
    private int                      currentFrame     = 0;
    private int                      dragJoint        = -1;
    private boolean                  playing          = false;
    private double                   speedMult        = 1.0;  // playback speed multiplier
    private double[]                 copiedFrame      = null; // clipboard for Copy/Paste Frame

    // ── Color group visibility (8 groups match legend order) ─────────────────
    // 0=Red/spine  1=Blue/L.arm  2=Green/R.arm  3=Amber/L.leg  4=Purple/R.leg
    // 5=Gold/1H    6=Teal/Shield 7=Orange/2H
    private final boolean[] groupVisible = {true, true, true, true, true, true, true, true};

    // ── Weapon preview state ──────────────────────────────────────────────────
    private WeaponType previewWeaponType = WeaponType.NONE;
    private EquipSlot  previewSlot       = EquipSlot.TWO_HANDED;
    private Color      previewTint       = Color.web("#8B4513");

    // ── Category / body type ──────────────────────────────────────────────────
    private MobCategory currentCategory = MobCategory.HUMANOID;

    // ── UI references ─────────────────────────────────────────────────────────
    private GraphicsContext gc;
    private TextArea        codeArea;
    private Label           frameLabel;
    private Label           saveStatusLabel;
    private AnimationTimer  playTimer;
    private HBox            weaponToolbar;   // hidden for non-humanoid categories
    private HBox            legendWrapper;   // replaced when category changes

    // ── Constructor ───────────────────────────────────────────────────────────

    public SpriteEditorPanel() {
        PlayerAnimator.State[]     states = PlayerAnimator.State.values();
        PlayerAnimator.Direction[] dirs   = PlayerAnimator.Direction.values();
        for (PlayerAnimator.Direction d : dirs) {
            double[][][] poses = new double[states.length][][];
            for (PlayerAnimator.State s : states) {
                // Seed each direction with either the saved override (if any) or the built-in frames for that direction
                double[][] override = PlayerAnimator.getDirectionalPoses(s, d);
                double[][] src = (override != null && override.length > 0)
                        ? override : PlayerAnimator.getBuiltinFrames(s, d);
                double[][] copy = new double[src.length][];
                for (int i = 0; i < src.length; i++) copy[i] = src[i].clone();
                poses[s.ordinal()] = copy;
            }
            dirPoses.put(d, poses);
        }
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {

        // ── Toolbar ───────────────────────────────────────────────────────────

        // Body-type / category selector
        Label catHdr = styledLabel("Body Type:", 12, false);
        ComboBox<MobCategory> categoryBox = new ComboBox<>();
        categoryBox.getItems().addAll(MobCategory.values());
        categoryBox.setValue(currentCategory);
        styleCombo(categoryBox);

        Label stateHdr = styledLabel("State:", 12, false);

        ComboBox<PlayerAnimator.State> stateBox = new ComboBox<>();
        stateBox.getItems().addAll(currentCategory.sortedStates());
        stateBox.setValue(currentState);
        styleCombo(stateBox);

        // ── State management buttons ──────────────────────────────────────────
        Button addStateBtn    = smBtn("+",  "#1e8449");
        Button renameStateBtn = smBtn("✎",  "#0f3460");
        Button removeStateBtn = smBtn("✕",  "#a03030");
        addStateBtn.setTooltip(new Tooltip("Add new state"));
        renameStateBtn.setTooltip(new Tooltip("Rename selected state"));
        removeStateBtn.setTooltip(new Tooltip("Remove selected state"));
        addStateBtn.setOnAction(e    -> addState(stateBox));
        renameStateBtn.setOnAction(e -> renameState(stateBox));
        removeStateBtn.setOnAction(e -> removeState(stateBox));

        // ── Direction toggle buttons ──────────────────────────────────────────
        Label dirHdr = styledLabel("View:", 12, false);
        ToggleGroup dirGroup = new ToggleGroup();
        ToggleButton dirFront = dirToggleBtn("⬤ Front", PlayerAnimator.Direction.FRONT, dirGroup);
        ToggleButton dirLeft  = dirToggleBtn("◀ Left",  PlayerAnimator.Direction.LEFT,  dirGroup);
        ToggleButton dirRight = dirToggleBtn("▶ Right", PlayerAnimator.Direction.RIGHT, dirGroup);
        ToggleButton dirBack  = dirToggleBtn("⬛ Back",  PlayerAnimator.Direction.BACK,  dirGroup);
        dirFront.setSelected(true); // default to FRONT
        dirGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { old.setSelected(true); return; } // prevent deselect
            currentDirection = (PlayerAnimator.Direction) now.getUserData();
            if (currentFrame >= frames().length) currentFrame = frames().length - 1;
            refreshFrameLabel(); redraw(); refreshCode();
        });
        HBox dirBar = new HBox(4, dirHdr, dirFront, dirLeft, dirRight, dirBack);
        dirBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button prev = toolBtn("◀");
        Button next = toolBtn("▶");
        frameLabel = new Label();
        frameLabel.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 12;");
        frameLabel.setMinWidth(80);

        prev.setOnAction(e -> {
            currentFrame = (currentFrame - 1 + frames().length) % frames().length;
            refreshFrameLabel(); redraw(); refreshCode();
        });
        next.setOnAction(e -> {
            currentFrame = (currentFrame + 1) % frames().length;
            refreshFrameLabel(); redraw(); refreshCode();
        });

        // ── Play / Pause button ───────────────────────────────────────────────
        Button playPauseBtn = toolBtn("▶ Play");
        playPauseBtn.setStyle(playPauseBtn.getStyle() +
                "-fx-text-fill: #50c050; -fx-font-weight: bold;");

        Button resetBtn = toolBtn("↺ Reset frame");
        resetBtn.setOnAction(e -> resetCurrentFrame());

        Button copyBtn  = toolBtn("📋 Copy code");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(codeArea.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });

        Button saveBtn = toolBtn("💾 Save to File");
        saveBtn.setStyle(saveBtn.getStyle() + "-fx-text-fill: #53c0f0; -fx-font-weight: bold;");
        saveBtn.setOnAction(e -> saveToPlayerAnimator());

        // ── Speed control ─────────────────────────────────────────────────────
        Label speedHdr = styledLabel("Speed:", 11, false);
        Slider speedSlider = new Slider(0.05, 4.0, 1.0);
        speedSlider.setPrefWidth(150);
        speedSlider.setMajorTickUnit(1.0);
        speedSlider.setMinorTickCount(3);
        speedSlider.setSnapToTicks(false);
        speedSlider.setStyle("-fx-base: #16213e; -fx-accent: #e94560;");
        Label speedValLabel = new Label("1.0×");
        speedValLabel.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 11; -fx-min-width: 32;");
        speedSlider.valueProperty().addListener((obs, o, n) -> {
            speedMult = n.doubleValue();
            speedValLabel.setText(String.format("%.2f×", speedMult));
        });

        // ── One-shot checkbox ─────────────────────────────────────────────────
        CheckBox oneShotCheck = new CheckBox("One-shot");
        oneShotCheck.setSelected(PlayerAnimator.isOneShot(currentState));
        oneShotCheck.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 11;");
        oneShotCheck.setTooltip(new Tooltip(
                "When checked: animation plays through once and holds on the last frame.\n" +
                "When unchecked: animation loops continuously."));
        oneShotCheck.setOnAction(e -> {
            PlayerAnimator.setOneShot(currentState, oneShotCheck.isSelected());
            PlayerAnimator.saveStateFlags();
        });
        // Keep checkbox in sync when switching states
        stateBox.setOnAction(e -> {
            if (stateBox.getValue() != null) {
                currentState = stateBox.getValue();
                currentFrame = 0;
                oneShotCheck.setSelected(PlayerAnimator.isOneShot(currentState));
                refreshFrameLabel();
                redraw();
                refreshCode();
            }
        });

        // ── Category change wires state list + legend ─────────────────────────
        categoryBox.setOnAction(e -> {
            if (categoryBox.getValue() == null) return;
            currentCategory = categoryBox.getValue();
            // Reset joint visibility
            java.util.Arrays.fill(groupVisible, true);
            // Repopulate state list
            stateBox.getItems().setAll(currentCategory.sortedStates());
            currentState = currentCategory.sortedStates().get(0);
            stateBox.setValue(currentState);
            currentFrame = 0;
            // Show/hide weapon toolbar
            weaponToolbar.setVisible(currentCategory == MobCategory.HUMANOID);
            weaponToolbar.setManaged(currentCategory == MobCategory.HUMANOID);
            // Swap legend
            legendWrapper.getChildren().setAll(buildLegendFor(currentCategory));
            refreshFrameLabel(); redraw(); refreshCode();
        });

        Label hint = new Label("Drag the coloured circles to reposition joints");
        hint.setStyle("-fx-text-fill: #606080; -fx-font-size: 11;");

        HBox toolbar = new HBox(10, catHdr, categoryBox,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                stateHdr, stateBox, addStateBtn, renameStateBtn, removeStateBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), dirBar,
                new Separator(javafx.geometry.Orientation.VERTICAL), oneShotCheck,
                new Separator(javafx.geometry.Orientation.VERTICAL), prev, frameLabel, next, playPauseBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                speedHdr, speedSlider, speedValLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL), resetBtn, copyBtn, saveBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), hint);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        toolbar.setStyle("-fx-background-color: #16213e;");

        // ── Canvas ────────────────────────────────────────────────────────────
        Canvas canvas = new Canvas(CANVAS_W, CANVAS_H);
        gc = canvas.getGraphicsContext2D();

        canvas.setOnMousePressed(this::onPress);
        canvas.setOnMouseDragged(this::onDrag);
        canvas.setOnMouseReleased(e -> { dragJoint = -1; redraw(); });

        // ── Playback timer ────────────────────────────────────────────────────
        long[] lastFrameNs = { 0 };
        playTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                long baseMs = switch (currentState) {
                    case IDLE -> 650;
                    case RUN  -> 105;
                    case GOTHIT01 -> 140;
                    default   -> 180;
                };
                long intervalMs = Math.max(16, (long)(baseMs / speedMult));
                if (now - lastFrameNs[0] >= intervalMs * 1_000_000L) {
                    int nextF = currentFrame + 1;
                    int count = frames().length;
                    if (PlayerAnimator.isOneShot(currentState) && nextF >= count) {
                        currentFrame = count - 1;  // hold on last frame
                        // stop playback automatically
                        playing = false;
                        playTimer.stop();
                        canvas.setOnMousePressed(SpriteEditorPanel.this::onPress);
                        canvas.setOnMouseDragged(SpriteEditorPanel.this::onDrag);
                        canvas.setOnMouseReleased(ev -> { dragJoint = -1; redraw(); });
                        canvas.setStyle("");
                        playPauseBtn.setText("▶ Play");
                        playPauseBtn.setStyle(playPauseBtn.getStyle()
                                .replace("-fx-text-fill: #f0a030;", "-fx-text-fill: #50c050;"));
                        prev.setDisable(false);
                        next.setDisable(false);
                        resetBtn.setDisable(false);
                        refreshCode();
                    } else {
                        currentFrame = nextF % count;
                    }
                    refreshFrameLabel();
                    redraw();
                    lastFrameNs[0] = now;
                }
            }
        };

        playPauseBtn.setOnAction(e -> {
            playing = !playing;
            if (playing) {
                lastFrameNs[0] = 0;
                playTimer.start();
                canvas.setOnMousePressed(null);
                canvas.setOnMouseDragged(null);
                canvas.setOnMouseReleased(null);
                canvas.setStyle("-fx-cursor: default; -fx-opacity: 0.85;");
                playPauseBtn.setText("⏸ Pause");
                playPauseBtn.setStyle(playPauseBtn.getStyle()
                        .replace("-fx-text-fill: #50c050;", "-fx-text-fill: #f0a030;"));
                prev.setDisable(true);
                next.setDisable(true);
                resetBtn.setDisable(true);
            } else {
                playTimer.stop();
                canvas.setOnMousePressed(SpriteEditorPanel.this::onPress);
                canvas.setOnMouseDragged(SpriteEditorPanel.this::onDrag);
                canvas.setOnMouseReleased(ev -> { dragJoint = -1; redraw(); });
                canvas.setStyle("");
                playPauseBtn.setText("▶ Play");
                playPauseBtn.setStyle(playPauseBtn.getStyle()
                        .replace("-fx-text-fill: #f0a030;", "-fx-text-fill: #50c050;"));
                prev.setDisable(false);
                next.setDisable(false);
                resetBtn.setDisable(false);
                refreshCode();
            }
        });

        // ── Code output ───────────────────────────────────────────────────────
        Label codeHdr = styledLabel(
                "Paste the line below back into PlayerAnimator.java in the matching pose array:", 11, false);
        codeHdr.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        codeArea = new TextArea();
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        codeArea.setPrefHeight(240);
        codeArea.setPrefWidth(CANVAS_W / 1.5);
        codeArea.setMaxWidth(CANVAS_W / 1.5);
        codeArea.setStyle(
                "-fx-control-inner-background: #0f0f1e;" +
                "-fx-text-fill: #c8e8c8;" +
                "-fx-font-family: monospace;" +
                "-fx-font-size: 12;");

        saveStatusLabel = new Label();
        saveStatusLabel.setStyle("-fx-font-size: 11;");

        VBox codePanel = new VBox(4, codeHdr, codeArea, saveStatusLabel);
        codePanel.setPadding(new Insets(6, 12, 10, 12));

        // ── Legend (swappable by category) ───────────────────────────────────
        legendWrapper = new HBox();
        legendWrapper.setPadding(new Insets(2, 12, 6, 12));
        legendWrapper.getChildren().add(buildLegendFor(currentCategory));

        // ── Frame management toolbar ──────────────────────────────────────────
        Button addFrameBtn    = toolBtn("+ Add Frame");
        Button removeFrameBtn = toolBtn("✕ Remove Frame");
        Button copyFrameBtn   = toolBtn("📋 Copy Frame");
        Button pasteFrameBtn  = toolBtn("📌 Paste Frame");
        pasteFrameBtn.setDisable(true); // nothing copied yet

        addFrameBtn.setOnAction(e -> {
            double[][] old = frames();
            double[][] updated = new double[old.length + 1][];
            // Insert copy of current frame right after current position
            for (int i = 0; i <= currentFrame; i++)       updated[i] = old[i].clone();
            updated[currentFrame + 1] = old[currentFrame].clone(); // new frame = copy of current
            for (int i = currentFrame + 2; i < updated.length; i++) updated[i] = old[i - 1].clone();
            dirPoses.get(currentDirection)[currentState.ordinal()] = updated;
            currentFrame++;   // move to the newly inserted frame
            refreshFrameLabel(); redraw(); refreshCode();
            setSaveStatus("Frame added at position " + (currentFrame + 1) + " — save to persist.", true);
        });

        removeFrameBtn.setOnAction(e -> {
            if (frames().length <= 1) { setSaveStatus("✗ Cannot remove the last frame.", false); return; }
            double[][] old = frames();
            double[][] updated = new double[old.length - 1][];
            int dst = 0;
            for (int i = 0; i < old.length; i++) if (i != currentFrame) updated[dst++] = old[i].clone();
            dirPoses.get(currentDirection)[currentState.ordinal()] = updated;
            if (currentFrame >= updated.length) currentFrame = updated.length - 1;
            refreshFrameLabel(); redraw(); refreshCode();
            setSaveStatus("Frame removed — save to persist.", true);
        });

        copyFrameBtn.setOnAction(e -> {
            copiedFrame = frames()[currentFrame].clone();
            pasteFrameBtn.setDisable(false);
            setSaveStatus("Frame " + (currentFrame + 1) + " copied.", true);
        });

        pasteFrameBtn.setOnAction(e -> {
            if (copiedFrame == null) return;
            frames()[currentFrame] = copiedFrame.clone();
            redraw(); refreshCode();
            setSaveStatus("Pasted onto frame " + (currentFrame + 1) + " — save to persist.", true);
        });

        Button backupBtn  = toolBtn("📦 Backup All");
        Button restoreBtn = toolBtn("🔄 Restore...");
        backupBtn.setStyle(backupBtn.getStyle()   + "-fx-text-fill: #f0a030;");
        restoreBtn.setStyle(restoreBtn.getStyle() + "-fx-text-fill: #53c0f0;");
        backupBtn.setOnAction(e  -> backupAll());
        restoreBtn.setOnAction(e -> restoreBackup());

        HBox frameToolbar = new HBox(8, addFrameBtn, removeFrameBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), copyFrameBtn, pasteFrameBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), backupBtn, restoreBtn);
        frameToolbar.setAlignment(Pos.CENTER_LEFT);
        frameToolbar.setPadding(new Insets(6, 12, 6, 12));
        frameToolbar.setStyle("-fx-background-color: #13132a;");

        // ── Weapon preview toolbar ────────────────────────────────────────────
        Label wepHdr = styledLabel("Weapon Preview:", 11, false);

        ComboBox<WeaponType> weaponBox = new ComboBox<>();
        weaponBox.getItems().addAll(WeaponType.values());
        weaponBox.setValue(previewWeaponType);
        weaponBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(WeaponType t) { return wepLabel(t); }
            @Override public WeaponType fromString(String s) { return null; }
        });
        styleCombo(weaponBox);

        ComboBox<EquipSlot> slotBox = new ComboBox<>();
        slotBox.getItems().addAll(EquipSlot.values());
        slotBox.setValue(previewSlot);
        slotBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(EquipSlot s) {
                return switch (s) {
                    case MAIN_HAND  -> "Main Hand";
                    case OFF_HAND   -> "Off Hand";
                    case TWO_HANDED -> "Two-Handed";
                };
            }
            @Override public EquipSlot fromString(String s) { return null; }
        });
        styleCombo(slotBox);

        // Preset tints
        String[][] tints = {
            {"Brown",  "#8B4513"}, {"Iron",    "#909090"}, {"Gold",   "#ffd700"},
            {"Red",    "#cc2200"}, {"Blue",    "#4488cc"}, {"Green",  "#44aa22"},
            {"Purple", "#8833cc"}, {"Frost",   "#66ccff"}, {"Shadow", "#442266"},
            {"White",  "#e0e0e0"}, {"Crimson", "#cc0033"}
        };
        ComboBox<String> tintBox = new ComboBox<>();
        for (String[] t : tints) tintBox.getItems().add(t[0]);
        tintBox.setValue("Brown");
        styleCombo(tintBox);
        Label tintHdr = styledLabel("Tint:", 11, false);

        weaponBox.setOnAction(e -> {
            if (weaponBox.getValue() != null) { previewWeaponType = weaponBox.getValue(); redraw(); }
        });
        slotBox.setOnAction(e -> {
            if (slotBox.getValue() != null) { previewSlot = slotBox.getValue(); redraw(); }
        });
        tintBox.setOnAction(e -> {
            for (String[] t : tints)
                if (t[0].equals(tintBox.getValue())) { previewTint = Color.web(t[1]); break; }
            redraw();
        });

        Label wepHint = styledLabel("Weapon drawn at current pose's weapon joints — drag gold/orange/teal handles to reposition.", 11, false);
        wepHint.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");

        weaponToolbar = new HBox(10, wepHdr,
                weaponBox, slotBox,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                tintHdr, tintBox,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                wepHint);
        weaponToolbar.setAlignment(Pos.CENTER_LEFT);
        weaponToolbar.setPadding(new Insets(6, 12, 6, 12));
        weaponToolbar.setStyle("-fx-background-color: #0f0f1e;");

        // ── Assemble ──────────────────────────────────────────────────────────
        VBox root = new VBox(
                toolbar,
                frameToolbar,
                weaponToolbar,
                new Separator(),
                canvas,
                legendWrapper,
                new Separator(),
                codePanel);
        root.setStyle("-fx-background-color: #1a1a2e;");

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");

        refreshFrameLabel();
        redraw();
        refreshCode();
        return scroll;
    }

    // ── Mouse events ──────────────────────────────────────────────────────────

    private void onPress(MouseEvent e) {
        dragJoint = nearestJoint(e.getX(), e.getY());
        redraw();
    }

    private void onDrag(MouseEvent e) {
        if (dragJoint < 0) return;
        double[] pose = frames()[currentFrame];
        pose[dragJoint * 2]     = round1((e.getX() - FEET_CX) / SCALE);
        pose[dragJoint * 2 + 1] = round1(-(e.getY() - FEET_CY) / SCALE);
        redraw();
        refreshCode();
    }

    private int nearestJoint(double mx, double my) {
        double[] pose = frames()[currentFrame];
        int    best = -1;
        double bestD = HANDLE_R * 3.0;
        for (int i = 0; i < jointCount(); i++) {
            double jcx = FEET_CX + pose[i * 2] * SCALE;
            double jcy = FEET_CY - pose[i * 2 + 1] * SCALE;
            double d   = Math.hypot(mx - jcx, my - jcy);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void redraw() {
        gc.setFill(Color.web("#0f0f1e"));
        gc.fillRect(0, 0, CANVAS_W, CANVAS_H);

        // Grid
        gc.setStroke(Color.web("#1e1e3a"));
        gc.setLineWidth(1);
        for (int x = 0; x <= CANVAS_W; x += 35) gc.strokeLine(x, 0, x, CANVAS_H);
        for (int y = 0; y <= CANVAS_H; y += 35) gc.strokeLine(0, y, CANVAS_W, y);

        // Centre axis
        gc.setStroke(Color.web("#2a2a5a"));
        gc.setLineWidth(1.5);
        gc.strokeLine(FEET_CX, 0, FEET_CX, CANVAS_H);

        // Direction indicator — tinted header bar + label
        Color dirColor = switch (currentDirection) {
            case FRONT -> Color.web("#4488ff");
            case LEFT  -> Color.web("#ff8844");
            case RIGHT -> Color.web("#44cc44");
            case BACK  -> Color.web("#aa66ff");
        };
        gc.setFill(dirColor.deriveColor(0, 1, 0.18, 1));
        gc.fillRect(0, 0, CANVAS_W, 22);
        gc.setFill(dirColor);
        gc.setFont(Font.font("System", FontWeight.BOLD, 12));
        String dirArrow = switch (currentDirection) {
            case FRONT -> "⬤  Front View";
            case LEFT  -> "◀  Left View  (mirrored in-game)";
            case RIGHT -> "▶  Right View";
            case BACK  -> "⬛  Back View";
        };
        gc.fillText(dirArrow, 8, 15);

        // Floor line — tinted to match direction
        gc.setStroke(dirColor.deriveColor(0, 0.7, 0.5, 1));
        gc.setLineWidth(2);
        gc.strokeLine(0, FEET_CY, CANVAS_W, FEET_CY);
        gc.setFill(dirColor.deriveColor(0, 0.7, 0.5, 1));
        gc.setFont(Font.font("System", 10));
        gc.fillText("floor (y = 0)", 4, FEET_CY - 4);

        double[] pose = frames()[currentFrame];

        // Stick figure — mirror display when editing LEFT direction
        gc.save();
        gc.translate(FEET_CX, FEET_CY);
        if (currentDirection == PlayerAnimator.Direction.LEFT) gc.scale(-1, 1);
        gc.setFill(Color.web("#d0d0ff"));
        gc.setStroke(Color.web("#d0d0ff"));
        gc.setLineWidth(LINE_W);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        if (currentCategory == MobCategory.QUADRUPED)
            drawPoseQuadScaled(gc, pose, groupVisible);
        else
            drawPoseScaled(gc, pose, groupVisible);
        gc.restore();

        // Weapon preview and attachment lines — humanoid only
        if (currentCategory == MobCategory.HUMANOID) {
            if (previewWeaponType != WeaponType.NONE && previewWeaponType.rendersBehindBody()) {
                drawWeaponPreview(gc, scaledCanvasPose(pose));
            }
            if (pose.length >= 36) {
                double rhcx = FEET_CX + pose[16] * SCALE, rhcy = FEET_CY - pose[17] * SCALE;
                double lhcx = FEET_CX + pose[10] * SCALE, lhcy = FEET_CY - pose[11] * SCALE;
                double w1cx = FEET_CX + pose[30] * SCALE, w1cy = FEET_CY - pose[31] * SCALE;
                double shcx = FEET_CX + pose[32] * SCALE, shcy = FEET_CY - pose[33] * SCALE;
                double w2cx = FEET_CX + pose[34] * SCALE, w2cy = FEET_CY - pose[35] * SCALE;
                gc.setLineDashes(6, 4);
                gc.setLineWidth(2.5);
                gc.setLineCap(StrokeLineCap.ROUND);
                if (groupVisible[5]) { gc.setStroke(Color.web("#ffe033")); gc.strokeLine(rhcx, rhcy, w1cx, w1cy); }
                if (groupVisible[6]) { gc.setStroke(Color.web("#00ddcc")); gc.strokeLine(lhcx, lhcy, shcx, shcy); }
                if (groupVisible[7]) { gc.setStroke(Color.web("#ff7700")); gc.strokeLine(lhcx, lhcy, w2cx, w2cy);
                                                                            gc.strokeLine(rhcx, rhcy, w2cx, w2cy); }
                gc.setLineDashes();
            }
            if (previewWeaponType != WeaponType.NONE && !previewWeaponType.rendersBehindBody()) {
                drawWeaponPreview(gc, scaledCanvasPose(pose));
            }
        }

        // Joint handles
        for (int i = 0; i < jointCount(); i++) {
            if (!groupVisible[getJointGroup(i)]) continue;
            double jcx = FEET_CX + pose[i * 2] * SCALE;
            double jcy = FEET_CY - pose[i * 2 + 1] * SCALE;
            boolean sel = (i == dragJoint);
            Color c = getJointColor(i);

            gc.setFill(sel ? Color.web("#f0a030") : c.deriveColor(0, 1, 1.2, 1));
            gc.fillOval(jcx - HANDLE_R, jcy - HANDLE_R, HANDLE_R * 2, HANDLE_R * 2);
            gc.setStroke(sel ? Color.WHITE : Color.color(1, 1, 1, 0.7));
            gc.setLineWidth(sel ? 2 : 1);
            gc.strokeOval(jcx - HANDLE_R, jcy - HANDLE_R, HANDLE_R * 2, HANDLE_R * 2);

            gc.setFont(Font.font("System", FontWeight.BOLD, sel ? 10 : 9));
            gc.setFill(sel ? Color.WHITE : c);
            if (sel) {
                String coords = String.format("(%s, %s)", fmt(pose[i*2]), fmt(pose[i*2+1]));
                gc.fillText(getJointName(i), jcx + 9, jcy - 5);
                gc.fillText(coords, jcx + 9, jcy + 6);
            } else {
                gc.fillText(String.valueOf(i), jcx + 7, jcy + 4);
            }
        }

    }

    /** Draws pose using editor SCALE (gc translated to feet origin). */
    private static void drawPoseScaled(GraphicsContext gc, double[] p, boolean[] vis) {
        double s = SCALE;
        double hx=p[0]*s, hy=-p[1]*s, nkx=p[2]*s, nky=-p[3]*s, hpx=p[4]*s, hpy=-p[5]*s;
        double lsx=p[6]*s,  lsy=-p[7]*s,  lex=p[8]*s, ley=-p[9]*s, lhx=p[10]*s,lhy=-p[11]*s;
        double rsx=p[12]*s, rsy=-p[13]*s, rex=p[14]*s,rey=-p[15]*s,rhx=p[16]*s,rhy=-p[17]*s;
        double llhx=p[18]*s,llhy=-p[19]*s,lkx=p[20]*s,lky=-p[21]*s,lfx=p[22]*s,lfy=-p[23]*s;
        double rlhx=p[24]*s,rlhy=-p[25]*s,rkx=p[26]*s,rky=-p[27]*s,rfx=p[28]*s,rfy=-p[29]*s;

        if (vis[0]) { gc.fillOval(hx - HEAD_R, hy - HEAD_R, HEAD_R * 2, HEAD_R * 2);
                      gc.strokeLine(nkx, nky, hpx, hpy); }         // head + spine
        if (vis[1]) { gc.strokeLine(lsx,lsy,lex,ley);
                      gc.strokeLine(lex,ley,lhx,lhy); }             // left arm
        if (vis[2]) { gc.strokeLine(rsx,rsy,rex,rey);
                      gc.strokeLine(rex,rey,rhx,rhy); }             // right arm
        if (vis[3]) { gc.strokeLine(llhx,llhy,lkx,lky);
                      gc.strokeLine(lkx,lky,lfx,lfy); }             // left leg
        if (vis[4]) { gc.strokeLine(rlhx,rlhy,rkx,rky);
                      gc.strokeLine(rkx,rky,rfx,rfy); }             // right leg
    }

    // ── Category-aware joint helpers ─────────────────────────────────────────

    private int jointCount() {
        return currentCategory == MobCategory.QUADRUPED ? 20 : 18;
    }

    private String getJointName(int i) {
        if (currentCategory == MobCategory.QUADRUPED) return QUAD_JOINT_NAMES[i];
        return i < JOINT_NAMES.length ? JOINT_NAMES[i] : "J" + i;
    }

    private int getJointGroup(int i) {
        if (currentCategory == MobCategory.QUADRUPED) {
            return switch (i) {
                case 0, 1, 2, 3, 4, 5 -> 0;  // Red:    head + spine
                case 6, 7              -> 5;  // Gold:   tail
                case 8, 9, 10          -> 1;  // Blue:   front-left leg
                case 11, 12, 13        -> 2;  // Green:  front-right leg
                case 14, 15, 16        -> 3;  // Amber:  back-left leg
                default                -> 4;  // Purple: back-right leg
            };
        }
        return switch (i) {
            case 15 -> 5;
            case 16 -> 6;
            case 17 -> 7;
            default -> i / 3;
        };
    }

    private Color getJointColor(int i) {
        if (currentCategory == MobCategory.QUADRUPED) {
            return switch (getJointGroup(i)) {
                case 0  -> Color.web("#e94560");  // red:    head/spine
                case 1  -> Color.web("#53c0f0");  // blue:   front-left
                case 2  -> Color.web("#50c050");  // green:  front-right
                case 3  -> Color.web("#f0a030");  // amber:  back-left
                case 4  -> Color.web("#bd10e0");  // purple: back-right
                default -> Color.web("#ffe033");  // gold:   tail
            };
        }
        return switch (i) {
            case 15 -> Color.web("#ffe033");
            case 16 -> Color.web("#00ddcc");
            case 17 -> Color.web("#ff7700");
            default -> switch (i / 3) {
                case 0  -> Color.web("#e94560");
                case 1  -> Color.web("#53c0f0");
                case 2  -> Color.web("#50c050");
                case 3  -> Color.web("#f0a030");
                default -> Color.web("#bd10e0");
            };
        };
    }

    // ── Static joint metadata (humanoid) ──────────────────────────────────────

    private static int jointGroup(int i) {
        return switch (i) {
            case 15 -> 5;  // Gold  — 1H weapon tip
            case 16 -> 6;  // Teal  — shield edge
            case 17 -> 7;  // Orange — 2H weapon tip
            default -> i / 3;  // 0=Red 1=Blue 2=Green 3=Amber 4=Purple
        };
    }

    private static Color jointColor(int i) {
        return switch (i) {
            case 15 -> Color.web("#ffe033");  // 1H weapon tip — gold
            case 16 -> Color.web("#00ddcc");  // shield edge    — teal
            case 17 -> Color.web("#ff7700");  // 2H weapon tip  — orange
            default -> switch (i / 3) {
                case 0  -> Color.web("#e94560");  // head/neck/hip
                case 1  -> Color.web("#53c0f0");  // left arm
                case 2  -> Color.web("#50c050");  // right arm
                case 3  -> Color.web("#f0a030");  // left leg
                default -> Color.web("#bd10e0");  // right leg
            };
        };
    }

    private static final String[] QUAD_JOINT_NAMES = {
        "Head", "Snout", "Neck",
        "Front-Shoulder", "Mid-Back", "Rump",
        "Tail-Base", "Tail-Tip",
        "FL-Upper", "FL-Knee", "FL-Paw",
        "FR-Upper", "FR-Knee", "FR-Paw",
        "BL-Hip",   "BL-Knee", "BL-Foot",
        "BR-Hip",   "BR-Knee", "BR-Foot"
    };

    // ── Code generation ───────────────────────────────────────────────────────

    private void refreshCode() {
        double[] pose = frames()[currentFrame];
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(currentState.name())
          .append(" [").append(currentDirection.name()).append("]")
          .append(" — Frame ").append(currentFrame).append("\n");
        sb.append("{ ");
        for (int i = 0; i < pose.length; i++) {
            sb.append(fmt(pose[i]));
            if (i < pose.length - 1) {
                sb.append(",");
                // Line break every 6 values (= 3 joints = one body-part group)
                if ((i + 1) % 6 == 0) sb.append("\n  ");
                else sb.append(" ");
            }
        }
        sb.append(" }");
        codeArea.setText(sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Draw the weapon preview on the editor canvas (gc not translated — method handles it). */
    private void drawWeaponPreview(GraphicsContext gc, double[] scaledPose) {
        gc.save();
        gc.translate(FEET_CX, FEET_CY);
        gc.setStroke(previewTint);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        previewWeaponType.draw(gc, scaledPose, previewSlot, previewTint, LINE_W);
        gc.restore();
    }

    /** Convert a pose array to canvas space at editor scale (Y negated, × SCALE). */
    private static double[] scaledCanvasPose(double[] pose) {
        double[] p = new double[pose.length];
        for (int i = 0; i < pose.length; i++)
            p[i] = (i % 2 == 1) ? -pose[i] * SCALE : pose[i] * SCALE;
        return p;
    }

    private static String wepLabel(WeaponType t) {
        return switch (t) {
            case NONE      -> "— None —";
            case STAFF     -> "Staff (2H)";
            case SWORD_1H  -> "Sword (1H)";
            case SWORD_2H  -> "Sword (2H)";
            case AXE_1H    -> "Axe (1H)";
            case AXE_2H    -> "Axe (2H)";
            case SPEAR     -> "Spear (2H)";
            case DAGGER    -> "Dagger (1H)";
            case SHIELD    -> "Shield";
            case BOW       -> "Bow & Arrow (2H)";
            case MACE_1H   -> "Mace (1H)";
            case NUNCHUCKS     -> "Nunchucks";
            case MORNING_STAR  -> "Morning Star (1H)";
        };
    }

    private static <T> void styleCombo(ComboBox<T> box) {
        box.setStyle(
            "-fx-background-color: #16213e;" +
            "-fx-border-color: #3a3a6a;" +
            "-fx-border-radius: 4;" +
            "-fx-background-radius: 4;");
        // Use the box's own StringConverter (if set) for display text
        javafx.util.StringConverter<T> conv = box.getConverter();
        box.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle("-fx-background-color: #16213e;"); }
                else { setText(conv != null ? conv.toString(item) : item.toString());
                       setStyle("-fx-text-fill: #e0e0e0; -fx-background-color: #16213e; -fx-font-size: 12;"); }
            }
        });
        box.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle("-fx-background-color: #16213e;"); }
                else { setText(conv != null ? conv.toString(item) : item.toString());
                       setStyle("-fx-text-fill: #e0e0e0; -fx-background-color: #16213e; -fx-font-size: 12;"); }
            }
        });
    }

    private double[][] frames() {
        double[][] raw = dirPoses.get(currentDirection)[currentState.ordinal()];
        // Pad any 30-value (body-only) frame to 36 values with default weapon positions
        for (int f = 0; f < raw.length; f++) {
            if (raw[f].length < 36) {
                double[] p = new double[36];
                System.arraycopy(raw[f], 0, p, 0, raw[f].length);
                double rhx = p[16], rhy = p[17]; // right hand
                double lhx = p[10], lhy = p[11]; // left hand
                p[30] = rhx + 6;   p[31] = rhy - 12; // 1H weapon tip (forward of right hand)
                p[32] = lhx - 10;  p[33] = lhy;       // shield edge (outward left)
                p[34] = 0;         p[35] = 44;          // 2H weapon top (centred, raised)
                raw[f] = p;
            }
        }
        return raw;
    }


    private void resetCurrentFrame() {
        // For non-RIGHT directions, reset to the saved override or base RIGHT frames
        double[][] base;
        if (currentDirection == PlayerAnimator.Direction.RIGHT) {
            base = PlayerAnimator.getFrames(currentState);
        } else {
            double[][] override = PlayerAnimator.getDirectionalPoses(currentState, currentDirection);
            base = (override != null && override.length > 0)
                    ? override : PlayerAnimator.getFrames(currentState);
        }
        if (currentFrame < base.length)
            dirPoses.get(currentDirection)[currentState.ordinal()][currentFrame] = base[currentFrame].clone();
        redraw();
        refreshCode();
    }

    private void refreshFrameLabel() {
        frameLabel.setText("Frame " + (currentFrame + 1) + " / " + frames().length);
    }

    private static double round1(double v) { return Math.round(v * 2.0) / 2.0; }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v))
                ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static Label styledLabel(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: " + size + ";"
                + (bold ? "-fx-font-weight: bold;" : ""));
        return l;
    }

    private static Button toolBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #16213e;" +
                   "-fx-text-fill: #e0e0e0;" +
                   "-fx-background-radius: 4;" +
                   "-fx-border-color: #3a3a6a;" +
                   "-fx-border-radius: 4;" +
                   "-fx-padding: 4 10 4 10;");
        return b;
    }

    private static ToggleButton dirToggleBtn(String text, PlayerAnimator.Direction dir, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.setUserData(dir);
        b.setStyle("-fx-background-color: #16213e;" +
                   "-fx-text-fill: #a0a0c0;" +
                   "-fx-background-radius: 4;" +
                   "-fx-border-color: #3a3a6a;" +
                   "-fx-border-radius: 4;" +
                   "-fx-padding: 4 10 4 10;" +
                   "-fx-font-size: 11;");
        b.selectedProperty().addListener((obs, old, sel) ->
            b.setStyle(b.getStyle()
                .replace("-fx-text-fill: #a0a0c0;", sel ? "-fx-text-fill: #ffffff;" : "-fx-text-fill: #a0a0c0;")
                .replace("-fx-background-color: #16213e;", sel ? "-fx-background-color: #0f3460;" : "-fx-background-color: #16213e;")
            )
        );
        return b;
    }

    private static <T> ListCell<T> themedCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: #16213e;");
                } else {
                    setText(item.toString());
                    setStyle("-fx-text-fill: #e0e0e0;" +
                             "-fx-background-color: #16213e;" +
                             "-fx-font-size: 12;");
                }
            }
        };
    }

    // ── Save to PlayerAnimator.java ───────────────────────────────────────────

    private void saveToPlayerAnimator() {
        // Auto-backup before every save so you can always roll back
        backupAll();

        double[][] editedFrames = dirPoses.get(currentDirection)[currentState.ordinal()];

        // RIGHT direction → overwrite source array in PlayerAnimator.java (existing behaviour)
        if (currentDirection == PlayerAnimator.Direction.RIGHT) {
            Path file = Paths.get(
                    "client/src/main/java/com/game/client/ui/PlayerAnimator.java");
            if (!Files.exists(file)) {
                setSaveStatus("✗ PlayerAnimator.java not found at: " + file.toAbsolutePath(), false);
                return;
            }
            try {
                String source    = Files.readString(file);
                String arrName   = arrayNameFor(currentState);
                String newBlock  = buildArrayBlock(arrName, editedFrames);
                String startMarker = "private static final double[][] " + arrName + " = {";
                int start = source.indexOf(startMarker);
                if (start == -1) {
                    setSaveStatus("✗ Could not locate array " + arrName + " in source.", false);
                    return;
                }
                int depth = 0, end = -1;
                for (int i = start + startMarker.length() - 1; i < source.length(); i++) {
                    char c = source.charAt(i);
                    if      (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) { end = source.indexOf(';', i) + 1; break; } }
                }
                if (end == -1) { setSaveStatus("✗ Could not find end of array " + arrName, false); return; }
                Files.writeString(file, source.substring(0, start) + newBlock + source.substring(end));
                setSaveStatus("✓ Saved " + arrName + " (" + editedFrames.length
                        + " frame(s)) [Right] to PlayerAnimator.java — restart to apply.", true);
            } catch (Exception ex) {
                setSaveStatus("✗ Save failed: " + ex.getMessage(), false);
            }
            return;
        }

        // All other directions → push to PlayerAnimator's DIR_POSES map and persist to JSON
        PlayerAnimator.setDirectionalPoses(currentState, currentDirection, editedFrames);
        PlayerAnimator.saveDirPoses();
        setSaveStatus("✓ Saved " + currentState.name() + " [" + currentDirection.name() + "] ("
                + editedFrames.length + " frame(s)) to dir_poses.json — takes effect immediately.", true);
    }

    /** Build the replacement source block for one state array. */
    private String buildArrayBlock(String arrName, double[][] frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("private static final double[][] ").append(arrName).append(" = {\n");
        for (int f = 0; f < frames.length; f++) {
            sb.append("        // Frame ").append(f).append("\n");
            sb.append("        { ");
            double[] pose = frames[f];
            for (int i = 0; i < pose.length; i++) {
                sb.append(fmt(pose[i]));
                if (i < pose.length - 1) {
                    sb.append(",");
                    if ((i + 1) % 6 == 0) sb.append("\n          ");
                    else sb.append(" ");
                }
            }
            sb.append(" }");
            if (f < frames.length - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("    };");
        return sb.toString();
    }

    private static String arrayNameFor(PlayerAnimator.State s) {
        return s == PlayerAnimator.State.KNOCKED_DOWN ? "KNOCKED" : s.name();
    }

    private void setSaveStatus(String msg, boolean ok) {
        saveStatusLabel.setText(msg);
        saveStatusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: "
                + (ok ? "#50c050" : "#e94560") + ";");
    }

    // ── Backup / Restore ─────────────────────────────────────────────────────

    /** Serialise all current poses (RIGHT/base direction) to a timestamped JSON backup file. */
    private void backupAll() {
        try {
            Files.createDirectories(BACKUP_DIR);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            String ts = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            root.put("timestamp", ts);
            ObjectNode statesNode = mapper.createObjectNode();
            for (PlayerAnimator.State s : PlayerAnimator.State.values()) {
                double[][] frames = dirPoses.get(PlayerAnimator.Direction.RIGHT)[s.ordinal()];
                ArrayNode framesNode = mapper.createArrayNode();
                for (double[] frame : frames) {
                    ArrayNode fn = mapper.createArrayNode();
                    for (double v : frame) fn.add(v);
                    framesNode.add(fn);
                }
                statesNode.set(s.name(), framesNode);
            }
            root.set("states", statesNode);
            String stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = BACKUP_DIR.resolve("backup_" + stamp + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
            // Keep only the 20 most recent backups
            pruneBackups(20);
            setSaveStatus("📦 Backup saved: " + out.getFileName(), true);
        } catch (Exception ex) {
            setSaveStatus("✗ Backup failed: " + ex.getMessage(), false);
        }
    }

    /** Show a list of available backups and restore the one the user selects. */
    private void restoreBackup() {
        if (!Files.isDirectory(BACKUP_DIR)) {
            setSaveStatus("✗ No backups found.", false); return;
        }
        List<Path> files;
        try {
            files = Files.list(BACKUP_DIR)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception ex) { setSaveStatus("✗ Could not read backups: " + ex.getMessage(), false); return; }

        if (files.isEmpty()) { setSaveStatus("✗ No backups found.", false); return; }

        // Build readable labels from file content timestamps
        List<String> labels = new ArrayList<>();
        for (Path p : files) {
            try {
                ObjectMapper m = new ObjectMapper();
                JsonNode n = m.readTree(p.toFile());
                String ts = n.has("timestamp") ? n.get("timestamp").asText() : p.getFileName().toString();
                labels.add(ts + "  [" + p.getFileName() + "]");
            } catch (Exception ignored) { labels.add(p.getFileName().toString()); }
        }

        ChoiceDialog<String> dlg = new ChoiceDialog<>(labels.get(0), labels);
        dlg.setTitle("Restore Backup");
        dlg.setHeaderText("Select a backup to restore.\nThis will overwrite current in-memory poses\nand write to PlayerAnimator.java.");
        dlg.setContentText("Backup:");
        dlg.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
        dlg.showAndWait().ifPresent(chosen -> {
            int idx = labels.indexOf(chosen);
            if (idx < 0) return;
            Path chosen_file = files.get(idx);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Restore '" + chosen_file.getFileName() + "'?\nThis will overwrite PlayerAnimator.java.",
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText("Confirm Restore");
            confirm.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
            confirm.showAndWait().ifPresent(r -> {
                if (r != ButtonType.YES) return;
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(chosen_file.toFile());
                    JsonNode statesNode = root.get("states");
                    for (PlayerAnimator.State s : PlayerAnimator.State.values()) {
                        if (!statesNode.has(s.name())) continue;
                        JsonNode framesNode = statesNode.get(s.name());
                        double[][] frames = new double[framesNode.size()][];
                        for (int f = 0; f < framesNode.size(); f++) {
                            JsonNode fn = framesNode.get(f);
                            double[] pose = new double[fn.size()];
                            for (int j = 0; j < fn.size(); j++) pose[j] = fn.get(j).asDouble();
                            frames[f] = pose;
                        }
                        dirPoses.get(PlayerAnimator.Direction.RIGHT)[s.ordinal()] = frames;
                    }
                    // Clamp current frame if needed
                    if (currentFrame >= frames().length) currentFrame = frames().length - 1;
                    refreshFrameLabel(); redraw(); refreshCode();
                    // Also write to PlayerAnimator.java
                    saveToPlayerAnimator();
                    setSaveStatus("✓ Restored from: " + chosen_file.getFileName(), true);
                } catch (Exception ex) {
                    setSaveStatus("✗ Restore failed: " + ex.getMessage(), false);
                }
            });
        });
    }

    /** Keep only the N most recent backups, delete older ones. */
    private void pruneBackups(int keep) throws Exception {
        List<Path> all = Files.list(BACKUP_DIR)
                .filter(p -> p.getFileName().toString().startsWith("backup_") && p.toString().endsWith(".json"))
                .sorted(Comparator.reverseOrder())
                .collect(java.util.stream.Collectors.toList());
        for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }

    // ── State management (add / rename / remove) ──────────────────────────────

    private void addState(ComboBox<PlayerAnimator.State> stateBox) {
        TextInputDialog dlg = inputDialog("Add State", "New state name (e.g. ATTACK01):", "NEWSTATE");
        dlg.showAndWait().ifPresent(raw -> {
            String name = raw.trim().toUpperCase();
            if (!name.matches("[A-Z][A-Z0-9_]*")) {
                setSaveStatus("✗ Invalid name — must start with a letter, uppercase A-Z/0-9/_ only.", false);
                return;
            }
            for (PlayerAnimator.State s : PlayerAnimator.State.values()) {
                if (s.name().equals(name)) { setSaveStatus("✗ State '" + name + "' already exists.", false); return; }
            }
            Path file = animatorFile();
            if (file == null) return;
            try {
                String src = Files.readString(file);

                // 1. Add to enum before KNOCKED_DOWN
                src = src.replace(", KNOCKED_DOWN }", ", " + name + ", KNOCKED_DOWN }");

                // 2. New array (copy of IDLE frame 0) inserted before KNOCKED array
                double[][] seed = { dirPoses.get(PlayerAnimator.Direction.RIGHT)[PlayerAnimator.State.IDLE.ordinal()][0].clone() };
                String newArr = buildArrayBlock(name, seed) + "\n\n    ";
                src = src.replace("private static final double[][] KNOCKED = {",
                                  newArr + "private static final double[][] KNOCKED = {");

                // 3. Switch cases — add before KNOCKED_DOWN in each switch
                src = addCaseBefore(src, "KNOCKED_DOWN -> KNOCKED;",    name + "     -> " + name + ";");
                src = addCaseBefore(src, "KNOCKED_DOWN -> KNOCKED.length;", name + "     -> " + name + ".length;");
                src = addCaseBefore(src, "KNOCKED_DOWN -> KNOCKED[f];", name + "     -> " + name + "[f];");

                Files.writeString(file, src);
                setSaveStatus("✓ State '" + name + "' added — restart client to use it.", true);
            } catch (Exception ex) { setSaveStatus("✗ Add failed: " + ex.getMessage(), false); }
        });
    }

    private void renameState(ComboBox<PlayerAnimator.State> stateBox) {
        PlayerAnimator.State sel = stateBox.getValue();
        if (sel == null) return;
        String old = sel.name();
        String oldArr = arrayNameFor(sel);
        TextInputDialog dlg = inputDialog("Rename State", "New name for '" + old + "':", old);
        dlg.showAndWait().ifPresent(raw -> {
            String name = raw.trim().toUpperCase();
            if (name.equals(old)) return;
            if (!name.matches("[A-Z][A-Z0-9_]*")) { setSaveStatus("✗ Invalid name.", false); return; }
            Path file = animatorFile();
            if (file == null) return;
            try {
                String src = Files.readString(file);
                // Enum — replace exact token (surrounded by , or space+})
                src = src.replaceAll("\\b" + old + "\\b", name);
                // Array variable name
                src = src.replace("double[][] " + name + " = {",  // already renamed above if same
                                  "double[][] " + name + " = {");
                if (!oldArr.equals(old)) {
                    // special mapping (e.g. KNOCKED_DOWN → KNOCKED) — rename array separately
                    src = src.replace("double[][] " + oldArr + " = {", "double[][] " + name + " = {");
                    src = src.replace("-> " + oldArr + ";",        "-> " + name + ";");
                    src = src.replace("-> " + oldArr + ".length;", "-> " + name + ".length;");
                    src = src.replace("-> " + oldArr + "[f];",     "-> " + name + "[f];");
                }
                Files.writeString(file, src);
                setSaveStatus("✓ Renamed '" + old + "' → '" + name + "' — restart to apply.", true);
            } catch (Exception ex) { setSaveStatus("✗ Rename failed: " + ex.getMessage(), false); }
        });
    }

    private void removeState(ComboBox<PlayerAnimator.State> stateBox) {
        PlayerAnimator.State sel = stateBox.getValue();
        if (sel == null) return;
        if (PlayerAnimator.State.values().length <= 1) {
            setSaveStatus("✗ Cannot remove the last state.", false); return;
        }
        String name = sel.name();
        String arrName = arrayNameFor(sel);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove state '" + name + "' and all its pose data?",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Confirm Remove");
        confirm.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
        confirm.showAndWait().ifPresent(r -> {
            if (r != ButtonType.YES) return;
            Path file = animatorFile();
            if (file == null) return;
            try {
                String src = Files.readString(file);

                // Remove from enum (handles , NAME , or , NAME } or { NAME ,)
                src = src.replaceAll(",\\s*" + name + "(?=\\s*[,}])", "");

                // Remove array block
                String startMarker = "private static final double[][] " + arrName + " = {";
                int start = src.indexOf(startMarker);
                if (start != -1) {
                    int depth = 0, end = -1;
                    for (int i = start + startMarker.length() - 1; i < src.length(); i++) {
                        char c = src.charAt(i);
                        if (c == '{') depth++;
                        else if (c == '}' && --depth == 0) { end = src.indexOf(';', i) + 1; break; }
                    }
                    if (end != -1) {
                        // Trim any leading blank lines before the marker
                        int ws = start;
                        while (ws > 0 && src.charAt(ws - 1) != '\n') ws--;
                        src = src.substring(0, ws) + src.substring(end).replaceFirst("^\n", "");
                    }
                }

                // Remove switch cases
                src = src.replaceAll("[ \\t]*case " + name + "[^;]+;\\s*\n", "");
                // Remove State.NAME references in resolve()
                src = src.replaceAll("\\|\\|\\s*state == State\\." + name, "");
                src = src.replaceAll("state == State\\." + name + "\\s*\\|\\|\\s*", "");

                Files.writeString(file, src);
                setSaveStatus("✓ State '" + name + "' removed — restart to apply.", true);
            } catch (Exception ex) { setSaveStatus("✗ Remove failed: " + ex.getMessage(), false); }
        });
    }

    /** Insert a new switch case line immediately before an existing case line. */
    private static String addCaseBefore(String src, String existingCase, String newCase) {
        int idx = src.indexOf("case " + existingCase);
        if (idx == -1) return src;
        // Find start of the line
        int lineStart = src.lastIndexOf('\n', idx) + 1;
        String indent = src.substring(lineStart, idx);
        return src.substring(0, lineStart) + indent + "case " + newCase + "\n" + src.substring(lineStart);
    }

    private Path animatorFile() {
        Path p = Paths.get("client/src/main/java/com/game/client/ui/PlayerAnimator.java");
        if (!Files.exists(p)) { setSaveStatus("✗ PlayerAnimator.java not found.", false); return null; }
        return p;
    }

    private static TextInputDialog inputDialog(String title, String header, String defaultVal) {
        TextInputDialog dlg = new TextInputDialog(defaultVal);
        dlg.setTitle(title);
        dlg.setHeaderText(header);
        dlg.setContentText("Name:");
        dlg.getDialogPane().setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: #e0e0e0;");
        dlg.getDialogPane().lookup(".content-text") ;
        return dlg;
    }

    private static Button smBtn(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white;" +
                   "-fx-background-radius: 3; -fx-font-size: 11; -fx-padding: 3 8 3 8;" +
                   "-fx-font-weight: bold;");
        return b;
    }

    /** Draws quadruped pose using editor SCALE (gc translated to feet origin). */
    private static void drawPoseQuadScaled(GraphicsContext gc, double[] p, boolean[] vis) {
        double s = SCALE;
        double hx   = p[0]*s,  hy   = -p[1]*s;
        double jx   = p[2]*s,  jy   = -p[3]*s;
        double nkx  = p[4]*s,  nky  = -p[5]*s;
        double fsx  = p[6]*s,  fsy  = -p[7]*s;
        double mbx  = p[8]*s,  mby  = -p[9]*s;
        double rpx  = p[10]*s, rpy  = -p[11]*s;
        double tbx  = p[12]*s, tby  = -p[13]*s;
        double ttx  = p[14]*s, tty  = -p[15]*s;
        double flux = p[16]*s, fluy = -p[17]*s;
        double flkx = p[18]*s, flky = -p[19]*s;
        double flpx = p[20]*s, flpy = -p[21]*s;
        double frux = p[22]*s, fruy = -p[23]*s;
        double frkx = p[24]*s, frky = -p[25]*s;
        double frpx = p[26]*s, frpy = -p[27]*s;
        double blhx = p[28]*s, blhy = -p[29]*s;
        double blkx = p[30]*s, blky = -p[31]*s;
        double blfx = p[32]*s, blfy = -p[33]*s;
        double brhx = p[34]*s, brhy = -p[35]*s;
        double brkx = p[36]*s, brky = -p[37]*s;
        double brfx = p[38]*s, brfy = -p[39]*s;

        if (vis[0]) {  // head + spine
            double hr = HEAD_R * 0.9;
            gc.fillOval(hx - hr * 1.3, hy - hr, hr * 2.6, hr * 2);
            gc.strokeLine(hx, hy, jx, jy);
            gc.strokeLine(hx, hy, nkx, nky);
            gc.strokeLine(nkx, nky, fsx, fsy);
            gc.strokeLine(fsx, fsy, mbx, mby);
            gc.strokeLine(mbx, mby, rpx, rpy);
        }
        if (vis[5]) { gc.strokeLine(rpx, rpy, tbx, tby); gc.strokeLine(tbx, tby, ttx, tty); }  // tail
        if (vis[1]) { gc.strokeLine(fsx, fsy, flux, fluy); gc.strokeLine(flux, fluy, flkx, flky); gc.strokeLine(flkx, flky, flpx, flpy); }  // FL
        if (vis[2]) { gc.strokeLine(fsx, fsy, frux, fruy); gc.strokeLine(frux, fruy, frkx, frky); gc.strokeLine(frkx, frky, frpx, frpy); }  // FR
        if (vis[3]) { gc.strokeLine(rpx, rpy, blhx, blhy); gc.strokeLine(blhx, blhy, blkx, blky); gc.strokeLine(blkx, blky, blfx, blfy); }  // BL
        if (vis[4]) { gc.strokeLine(rpx, rpy, brhx, brhy); gc.strokeLine(brhx, brhy, brkx, brky); gc.strokeLine(brkx, brky, brfx, brfy); }  // BR
    }

    private HBox buildLegendFor(MobCategory cat) {
        return cat == MobCategory.QUADRUPED ? buildLegendQuad() : buildLegendWithToggles();
    }

    private HBox buildLegendQuad() {
        String[] parts  = { "Body/Spine", "Front-Left", "Front-Right", "Back-Left", "Back-Right", "Tail" };
        Color[]  colors = {
            Color.web("#e94560"), Color.web("#53c0f0"), Color.web("#50c050"),
            Color.web("#f0a030"), Color.web("#bd10e0"), Color.web("#ffe033")
        };
        int[]    groups = { 0, 1, 2, 3, 4, 5 };
        HBox box = new HBox(12);
        box.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < parts.length; i++) {
            final int g = groups[i];
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5, colors[i]);
            CheckBox cb = new CheckBox(parts[i]);
            cb.setSelected(groupVisible[g]);
            cb.setStyle(
                "-fx-text-fill: #9090b0; -fx-font-size: 11;" +
                "-fx-mark-color: " + toHex(colors[i]) + ";" +
                "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
            cb.selectedProperty().addListener((obs, o, n) -> { groupVisible[g] = n; redraw(); });
            HBox entry = new HBox(5, dot, cb);
            entry.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(entry);
        }
        return box;
    }

    private HBox buildLegendWithToggles() {
        String[] parts = {
            "Head/Spine", "Left Arm",   "Right Arm",
            "Left Leg",   "Right Leg",
            "1H Weapon",  "Shield",     "2H Weapon"
        };
        Color[] colors = {
            Color.web("#e94560"), Color.web("#53c0f0"), Color.web("#50c050"),
            Color.web("#f0a030"), Color.web("#bd10e0"),
            Color.web("#ffe033"), Color.web("#00ddcc"), Color.web("#ff7700")
        };
        HBox box = new HBox(12);
        box.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < parts.length; i++) {
            final int idx = i;
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5, colors[i]);
            CheckBox cb = new CheckBox(parts[i]);
            cb.setSelected(true);
            cb.setStyle(
                "-fx-text-fill: #9090b0;" +
                "-fx-font-size: 11;" +
                "-fx-mark-color: " + toHex(colors[i]) + ";" +
                "-fx-focus-color: transparent;" +
                "-fx-faint-focus-color: transparent;");
            cb.selectedProperty().addListener((obs, o, n) -> {
                groupVisible[idx] = n;
                redraw();
            });
            HBox entry = new HBox(5, dot, cb);
            entry.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(entry);
        }
        return box;
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
            (int)(c.getRed() * 255), (int)(c.getGreen() * 255), (int)(c.getBlue() * 255));
    }
}
