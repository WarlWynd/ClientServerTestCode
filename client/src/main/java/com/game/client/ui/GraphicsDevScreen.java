package com.game.client.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.game.client.UDPClient;
import com.game.shared.Packet;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Graphics Developer Tab.
 *
 * Features:
 *   - Browse image files in the graphics resource directories (sprites, backgrounds, ui)
 *   - Import image files from disk into the correct directory
 *   - Preview selected images
 *   - Delete files from the resource directories
 *   - Status bar showing current action
 *
 * Graphics resource root: client/src/main/resources/graphics/
 */
public class GraphicsDevScreen {

    private static final String GRAPHICS_ROOT  = "client/src/main/resources/graphics";
    private static final String SPRITES_DIR    = GRAPHICS_ROOT + "/sprites";
    private static final String BACKGROUNDS_DIR = GRAPHICS_ROOT + "/backgrounds";
    private static final String UI_DIR         = GRAPHICS_ROOT + "/ui";

    private static final String[] SUPPORTED_EXTENSIONS = {
            "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.svg"
    };

    private final Stage     stage;
    private final UDPClient client;

    private GameSettingsPanel gameSettingsPanel;

    private ListView<String> fileList;
    private ImageView        previewImage;
    private Label            previewNameLabel;
    private Label            previewSizeLabel;
    private Label            statusLabel;
    private ToggleGroup      categoryToggle;

    public GraphicsDevScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void setRestartCallback(Consumer<Integer> callback) {
        if (gameSettingsPanel != null) gameSettingsPanel.setRestartCallback(callback);
    }

    public void onPacket(Packet packet) {
        if (gameSettingsPanel != null) gameSettingsPanel.onPacket(packet);
    }

    public Node build() {
        Tab filesTab      = new Tab("📁 Files",            buildFilesView());
        Tab spritesTab    = new Tab("🕹 Sprite Preview",   buildSpritePreview());
        Tab editorTab     = new Tab("✏ Pose Editor",       new SpriteEditorPanel().build());
        Tab mobTab        = new Tab("👾 Mob Manager",      new MobManagerPanel().build());
        Tab lootTab       = new Tab("📦 Loot Tables",     new LootTablePanel().build());
        Tab itemTab       = new Tab("🗡 Item Registry",    new ItemRegistryPanel().build());
        Tab mechanicsTab  = new Tab("🎮 Game Mechanics",   new GameMechanicsPanel().build());
        filesTab.setClosable(false);
        spritesTab.setClosable(false);
        editorTab.setClosable(false);
        mobTab.setClosable(false);
        lootTab.setClosable(false);
        itemTab.setClosable(false);
        mechanicsTab.setClosable(false);

        TabPane inner;
        if (client != null) {
            gameSettingsPanel = new GameSettingsPanel(client);
            Tab gameSettingsTab = new Tab("🎛 Game Settings", gameSettingsPanel.buildView());
            gameSettingsTab.setClosable(false);
            inner = new TabPane(filesTab, spritesTab, editorTab, mobTab, lootTab, itemTab, mechanicsTab, gameSettingsTab);
        } else {
            inner = new TabPane(filesTab, spritesTab, editorTab, mobTab, lootTab, itemTab, mechanicsTab);
        }
        inner.getStyleClass().add("tab-pane-dark");
        inner.setStyle("-fx-tab-min-width: 120;");
        return inner;
    }

    /** Wraps the existing file browser + image preview into a single node. */
    private Node buildFilesView() {
        VBox leftPanel = buildFileBrowser();
        leftPanel.setPrefWidth(260);

        VBox rightPanel = buildPreviewPanel();

        HBox content = new HBox(12, leftPanel, rightPanel);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        content.setPadding(new Insets(12));
        content.setStyle("-fx-background-color: #1a1a2e;");

        statusLabel = new Label("Ready.");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");
        statusLabel.setPadding(new Insets(4, 12, 4, 12));

        VBox root = new VBox(content, statusLabel);
        VBox.setVgrow(content, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1a1a2e;");

        refreshList();
        return root;
    }

    /** Live animated preview of every PlayerAnimator state — 4 direction rows. */
    // States that make sense facing the camera — shown in the Front View row only.

    private Node buildSpritePreview() {
        // ── Category selector ─────────────────────────────────────────────────
        ToggleGroup catGroup = new ToggleGroup();
        ToggleButton humanoidBtn  = previewCatBtn("🧍 Humanoid",  catGroup);
        ToggleButton quadrupedBtn = previewCatBtn("🐾 Quadruped", catGroup);
        humanoidBtn.setSelected(true);

        HBox catBar = new HBox(8, humanoidBtn, quadrupedBtn);
        catBar.setAlignment(Pos.CENTER_LEFT);
        catBar.setPadding(new Insets(8, 14, 8, 14));
        catBar.setStyle("-fx-background-color: #16213e;");

        // ── Per-category grids ────────────────────────────────────────────────
        Node humanoidGrid  = buildSpriteGrid(MobCategory.HUMANOID);
        Node quadrupedGrid = buildSpriteGrid(MobCategory.QUADRUPED);
        quadrupedGrid.setVisible(false);
        quadrupedGrid.setManaged(false);

        StackPane gridHolder = new StackPane(humanoidGrid, quadrupedGrid);

        humanoidBtn.setOnAction(e -> {
            humanoidGrid.setVisible(true);  humanoidGrid.setManaged(true);
            quadrupedGrid.setVisible(false); quadrupedGrid.setManaged(false);
        });
        quadrupedBtn.setOnAction(e -> {
            humanoidGrid.setVisible(false); humanoidGrid.setManaged(false);
            quadrupedGrid.setVisible(true);  quadrupedGrid.setManaged(true);
        });

        // ── Export button ─────────────────────────────────────────────────────
        Path outDir = Paths.get("client/src/main/resources/graphics/sprites");

        Button exportBtn = new Button("⬇ Export 128×128 PNGs");
        exportBtn.setStyle(
                "-fx-background-color: #1e8449; -fx-text-fill: white;" +
                "-fx-background-radius: 4; -fx-font-size: 12; -fx-padding: 6 16 6 16;");
        Label exportStatus = new Label();
        exportStatus.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        exportBtn.setOnAction(e -> runExport(outDir, exportBtn, exportStatus));

        HBox exportRow = new HBox(12, exportBtn, exportStatus);
        exportRow.setAlignment(Pos.CENTER_LEFT);

        Platform.runLater(() -> {
            File spritesDir = outDir.toFile();
            boolean hasFiles = spritesDir.exists()
                    && spritesDir.listFiles(f -> f.getName().endsWith(".png")) != null
                    && spritesDir.listFiles(f -> f.getName().endsWith(".png")).length > 0;
            if (!hasFiles) {
                exportStatus.setText("Auto-exporting sprites…");
                runExport(outDir, exportBtn, exportStatus);
            } else {
                exportStatus.setText("Sprites already exported — click to regenerate.");
            }
        });

        Label desc = new Label("Live preview of all animation states across all 4 viewing directions.");
        desc.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        VBox root = new VBox(0, catBar, gridHolder, desc, exportRow);
        VBox.setMargin(desc,      new Insets(10, 14, 0, 14));
        VBox.setMargin(exportRow, new Insets(4,  14, 14, 14));
        root.setStyle("-fx-background-color: #1a1a2e;");
        return root;
    }

    /** Builds the animated sprite grid for one body-type category. */
    private Node buildSpriteGrid(MobCategory cat) {
        PlayerAnimator.State[]     states = cat.sortedStates().toArray(new PlayerAnimator.State[0]);
        PlayerAnimator.Direction[] dirs   = PlayerAnimator.Direction.values();

        int n = states.length;

        PlayerAnimator[][] animators       = new PlayerAnimator[4][n];
        WeaponRenderer[][]  weaponRenderers = new WeaponRenderer[4][n];
        for (int row = 0; row < 4; row++) {
            PlayerAnimator.Direction dir = dirs[row];
            for (int i = 0; i < n; i++) {
                animators[row][i] = new PlayerAnimator();
                animators[row][i].forceState(states[i]);
                animators[row][i].setForcedDirection(dir);
                if (dir == PlayerAnimator.Direction.LEFT) animators[row][i].setFacingRight(false);

                weaponRenderers[row][i] = new WeaponRenderer();
                if (cat == MobCategory.HUMANOID) {
                    if (states[i] == PlayerAnimator.State.STAFF_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.TWO_HANDED, Weapon.WOODEN_STAFF);
                    else if (states[i] == PlayerAnimator.State.SWORD_1H_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.MAIN_HAND, Weapon.IRON_SWORD);
                    else if (states[i] == PlayerAnimator.State.SWORD_2H_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.TWO_HANDED, Weapon.GREAT_SWORD);
                    else if (states[i] == PlayerAnimator.State.AXE_1H_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.MAIN_HAND, Weapon.HATCHET);
                    else if (states[i] == PlayerAnimator.State.AXE_2H_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.TWO_HANDED, Weapon.GREAT_AXE);
                    else if (states[i] == PlayerAnimator.State.DAGGER_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.MAIN_HAND, Weapon.IRON_DAGGER);
                    else if (states[i] == PlayerAnimator.State.MORNING_STAR_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.MAIN_HAND, Weapon.MORNING_STAR);
                    else if (states[i] == PlayerAnimator.State.BOW_IDLE)
                        weaponRenderers[row][i].equip(EquipSlot.TWO_HANDED, Weapon.LONG_BOW);
                }
            }
        }

        Color[] palette = {
            Color.web("#e0e0ff"), Color.web("#53c0f0"), Color.web("#50c050"),
            Color.web("#f0a030"), Color.web("#e94560"), Color.web("#c8a020"),
            Color.web("#d0e8ff"), Color.web("#c0d8ff"), Color.web("#d09050"),
            Color.web("#c08030"), Color.web("#88aacc"), Color.web("#aaaaaa"),
            Color.web("#44bb88"), Color.web("#bd10e0"), Color.web("#e0e0e0"),
            Color.web("#70b0d0"), Color.web("#d0a060"), Color.web("#a06040"),
            Color.web("#ff8844"), Color.web("#4488ff"), Color.web("#ff4488"),
            Color.web("#ff2266"), Color.web("#ff66aa"), Color.web("#ffaacc"),
            Color.web("#cc0044"), Color.web("#ff6600"), Color.web("#ffaa00"),
            Color.web("#ff8800"), Color.web("#dd4400"), Color.web("#ff9955"),
            Color.web("#88ff44")
        };

        int    colW    = 110;
        int    rowH    = 195;
        int    figH    = 155;
        int    hdrH    = 20;
        int    canvasW = n * colW + 20;
        int    canvasH = 4 * (rowH + hdrH) + 10;

        Canvas canvas = new Canvas(canvasW, canvasH);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        String[] dirLabels = { "⬤  Front View", "◀  Left View", "▶  Right View", "⬛  Back View" };
        Color[]  dirColors = {
            Color.web("#4488ff"), Color.web("#ff8844"),
            Color.web("#44cc44"), Color.web("#aa66ff")
        };

        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) {
                gc.setFill(Color.web("#0f0f1e"));
                gc.fillRect(0, 0, canvasW, canvasH);

                for (int row = 0; row < 4; row++) {
                    int    bandTop = row * (rowH + hdrH);
                    double feetY   = bandTop + hdrH + figH;

                    gc.setFill(dirColors[row].deriveColor(0, 1, 0.25, 1));
                    gc.fillRect(0, bandTop, canvasW, hdrH);
                    gc.setFill(dirColors[row]);
                    gc.setFont(javafx.scene.text.Font.font("System",
                            javafx.scene.text.FontWeight.BOLD, 12));
                    gc.fillText(dirLabels[row], 8, bandTop + 14);

                    gc.setStroke(Color.web("#22224a"));
                    gc.setLineWidth(1);
                    for (int x = 0; x <= canvasW; x += colW)
                        gc.strokeLine(x, bandTop + hdrH, x, bandTop + hdrH + rowH);
                    gc.setStroke(dirColors[row].deriveColor(0, 0.6, 0.5, 0.4));
                    gc.strokeLine(0, feetY, canvasW, feetY);

                    for (int i = 0; i < n; i++) {

                        double cx = 10 + i * colW + colW / 2.0;

                        gc.setFill(Color.color(0, 0, 0, 0.3));
                        gc.fillOval(cx - 16, feetY - 4, 32, 8);

                        Color c = palette[i % palette.length];
                        if (animators[row][i].isOneShotDone())
                            animators[row][i].forceState(states[i]);

                        weaponRenderers[row][i].drawBehindBody(gc, animators[row][i], cx, feetY, c);
                        animators[row][i].draw(gc, cx, feetY, c);
                        weaponRenderers[row][i].draw(gc, animators[row][i], cx, feetY, c);

                        gc.setFill(c.deriveColor(0, 1, 1.3, 1));
                        gc.setFont(javafx.scene.text.Font.font("System",
                                javafx.scene.text.FontWeight.BOLD, 10));
                        String name = states[i].name();
                        gc.fillText(name, cx - name.length() * 2.9, feetY + 22);
                    }
                }
            }
        };
        timer.start();

        StackPane canvasWrap = new StackPane(canvas);
        canvasWrap.setStyle("-fx-background-color: #0f0f1e;");
        canvasWrap.setAlignment(javafx.geometry.Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(canvasWrap);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: #0f0f1e; -fx-background-color: #0f0f1e;");
        scroll.setPrefViewportHeight(Math.min(canvasH + 20, 750));
        return scroll;
    }

    private static ToggleButton previewCatBtn(String text, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.setStyle("-fx-background-color: #16213e; -fx-text-fill: #a0a0c0;" +
                   "-fx-background-radius: 4; -fx-border-color: #3a3a6a;" +
                   "-fx-border-radius: 4; -fx-padding: 5 14 5 14; -fx-font-size: 12;");
        b.selectedProperty().addListener((obs, o, sel) ->
            b.setStyle(b.getStyle()
                .replace("-fx-text-fill: #a0a0c0;", sel ? "-fx-text-fill: #ffffff;" : "-fx-text-fill: #a0a0c0;")
                .replace("-fx-background-color: #16213e;", sel ? "-fx-background-color: #0f3460;" : "-fx-background-color: #16213e;"))
        );
        return b;
    }

    private void runExport(Path outDir, Button exportBtn, Label exportStatus) {
        exportBtn.setDisable(true);
        exportStatus.setText("Exporting…");
        exportStatus.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");
        int[] count = { 0 };
        Exception[] err = { null };
        try {
            count[0] = SpriteExporter.exportAll(outDir);
        } catch (Exception ex) {
            err[0] = ex;
        }
        exportBtn.setDisable(false);
        if (err[0] != null) {
            exportStatus.setText("✗ Export failed: " + err[0].getMessage());
            exportStatus.setStyle("-fx-text-fill: #e94560; -fx-font-size: 11;");
        } else {
            exportStatus.setText("✓ Exported " + count[0] + " files → " + outDir.toAbsolutePath());
            exportStatus.setStyle("-fx-text-fill: #50c050; -fx-font-size: 11;");
            refreshList();
        }
    }

    // ── File Browser ─────────────────────────────────────────────────────────

    private VBox buildFileBrowser() {
        Label title = styledLabel("Image Files", 14, true);

        // Category toggle
        ToggleButton spritesBtn = new ToggleButton("Sprites");
        ToggleButton bgBtn      = new ToggleButton("Backgrounds");
        ToggleButton uiBtn      = new ToggleButton("UI");
        categoryToggle = new ToggleGroup();
        spritesBtn.setToggleGroup(categoryToggle);
        bgBtn.setToggleGroup(categoryToggle);
        uiBtn.setToggleGroup(categoryToggle);
        spritesBtn.setSelected(true);

        ToggleButton[] toggleBtns = { spritesBtn, bgBtn, uiBtn };
        applyToggleStyle(spritesBtn, true);
        applyToggleStyle(bgBtn,      false);
        applyToggleStyle(uiBtn,      false);

        HBox toggleBar = new HBox(4, spritesBtn, bgBtn, uiBtn);

        categoryToggle.selectedToggleProperty().addListener((obs, o, n) -> {
            // Prevent deselection — if user clicks the already-selected button, re-select it
            if (n == null) { categoryToggle.selectToggle(o); return; }
            for (ToggleButton b : toggleBtns) applyToggleStyle(b, b == n);
            refreshList();
        });

        // File list
        fileList = new ListView<>();
        fileList.setStyle("-fx-background-color: #0f0f1e;" +
                          "-fx-border-color: #3a3a6a;" +
                          "-fx-border-radius: 4;" +
                          "-fx-control-inner-background: #0f0f1e;" +
                          "-fx-text-fill: #e0e0e0;");
        fileList.setPrefHeight(300);
        VBox.setVgrow(fileList, Priority.ALWAYS);
        fileList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && !n.startsWith("#")) onFileSelected(n);
        });

        // Action buttons
        Button importBtn  = actionButton("⬆ Import",  "#0f3460");
        Button deleteBtn  = actionButton("🗑 Delete",  "#7b241c");
        Button refreshBtn = actionButton("↻ Refresh", "#1e8449");

        importBtn.setOnAction(e  -> importFile());
        deleteBtn.setOnAction(e  -> deleteSelected());
        refreshBtn.setOnAction(e -> refreshList());

        HBox btnRow = new HBox(4, importBtn, deleteBtn, refreshBtn);

        VBox panel = new VBox(8, title, toggleBar, fileList, btnRow);
        panel.setPadding(new Insets(8));
        panel.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return panel;
    }

    // ── Preview Panel ─────────────────────────────────────────────────────────

    private VBox buildPreviewPanel() {
        Label title = styledLabel("Preview", 14, true);

        previewNameLabel = new Label("No file selected");
        previewNameLabel.setStyle("-fx-text-fill: #e94560; -fx-font-weight: bold;");
        previewNameLabel.setWrapText(true);

        previewSizeLabel = new Label();
        previewSizeLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        previewImage.setFitWidth(400);
        previewImage.setFitHeight(350);
        previewImage.setStyle("-fx-background-color: #0f0f1e;");

        Label spriteNote = new Label(
                "Sprites are white on transparent — the player colour and username are applied at runtime in-game.");
        spriteNote.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 10;");
        spriteNote.setWrapText(true);

        StackPane imagePane = new StackPane(previewImage);
        imagePane.setStyle("-fx-background-color: #2a2a4a; -fx-border-color: #3a3a6a; -fx-border-radius: 4;");
        imagePane.setPadding(new Insets(8));
        VBox.setVgrow(imagePane, Priority.ALWAYS);

        Label dirTitle = styledLabel("Resource Directories", 13, true);
        Label spritesDir  = dirLabel("Sprites:  " + new File(SPRITES_DIR).getAbsolutePath());
        Label bgDir       = dirLabel("Backgrounds: " + new File(BACKGROUNDS_DIR).getAbsolutePath());
        Label uiDir       = dirLabel("UI:       " + new File(UI_DIR).getAbsolutePath());

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #3a3a6a;");

        VBox panel = new VBox(10,
                title,
                previewNameLabel,
                previewSizeLabel,
                spriteNote,
                imagePane,
                sep,
                dirTitle, spritesDir, bgDir, uiDir
        );
        panel.setPadding(new Insets(8));
        panel.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return panel;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void importFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Image File(s)");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files", SUPPORTED_EXTENSIONS));

        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        String destDir = activeDir();
        int ok = 0, fail = 0;
        for (File file : files) {
            try {
                Path dest = Paths.get(destDir, file.getName());
                Files.createDirectories(dest.getParent());
                Files.copy(file.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                ok++;
            } catch (IOException e) {
                fail++;
                status("✗ Import failed: " + file.getName() + " — " + e.getMessage());
            }
        }
        if (ok > 0) status("✓ Imported " + ok + " file(s) → " + destDir
                + (fail > 0 ? " (" + fail + " failed)" : ""));
        refreshList();
    }

    private void onFileSelected(String name) {
        File file = new File(activeDir(), name);
        previewNameLabel.setText(name);
        if (file.exists()) {
            try {
                Image img = new Image(file.toURI().toString());
                previewImage.setImage(img);
                previewSizeLabel.setText((int) img.getWidth() + " × " + (int) img.getHeight() + " px  |  "
                        + (file.length() / 1024) + " KB");
                status("Selected: " + name);
            } catch (Exception e) {
                previewImage.setImage(null);
                previewSizeLabel.setText("Cannot preview this file.");
            }
        }
    }

    private void deleteSelected() {
        String name = fileList.getSelectionModel().getSelectedItem();
        if (name == null || name.startsWith("#")) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + name + "\"?", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Confirm Delete");
        confirm.getDialogPane().setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: #e0e0e0;");
        confirm.showAndWait().ifPresent(r -> {
            if (r == ButtonType.YES) {
                try {
                    Files.deleteIfExists(Paths.get(activeDir(), name));
                    previewImage.setImage(null);
                    previewNameLabel.setText("No file selected");
                    previewSizeLabel.setText("");
                    status("✓ Deleted: " + name);
                    refreshList();
                } catch (IOException e) {
                    status("✗ Delete failed: " + e.getMessage());
                }
            }
        });
    }

    // ── List management ───────────────────────────────────────────────────────

    private void refreshList() {
        fileList.getItems().clear();
        String dir = activeDir();
        File dirFile = new File(dir);
        if (!dirFile.exists()) {
            fileList.getItems().add("# Directory not found");
            fileList.getItems().add("# " + dirFile.getAbsolutePath());
            return;
        }
        File[] files = dirFile.listFiles(f -> f.isFile() && isImageFile(f.getName()));
        if (files == null || files.length == 0) {
            fileList.getItems().add("# No image files yet");
            fileList.getItems().add("# Use Import to add files");
            return;
        }
        Arrays.sort(files);
        for (File f : files) fileList.getItems().add(f.getName());
        status("Refreshed: " + files.length + " file(s) in " + activeCategoryName());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String activeDir() {
        if (categoryToggle.getSelectedToggle() == null) return SPRITES_DIR;
        String text = ((ToggleButton) categoryToggle.getSelectedToggle()).getText();
        return switch (text) {
            case "Backgrounds" -> BACKGROUNDS_DIR;
            case "UI"          -> UI_DIR;
            default            -> SPRITES_DIR;
        };
    }

    private String activeCategoryName() {
        if (categoryToggle.getSelectedToggle() == null) return "Sprites";
        return ((ToggleButton) categoryToggle.getSelectedToggle()).getText();
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".svg");
    }

    private void status(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    // ── Styling helpers ───────────────────────────────────────────────────────

    private Label styledLabel(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0;" +
                   "-fx-font-size: " + size + ";" +
                   (bold ? "-fx-font-weight: bold;" : ""));
        return l;
    }

    private Label dirLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 10;");
        l.setWrapText(true);
        return l;
    }

    private Button actionButton(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + ";" +
                   "-fx-text-fill: white;" +
                   "-fx-background-radius: 4;" +
                   "-fx-font-size: 11;" +
                   "-fx-padding: 5 10 5 10;");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private void applyToggleStyle(ToggleButton b, boolean selected) {
        b.setStyle(selected
                ? "-fx-background-color: #e94560; -fx-text-fill: white; -fx-background-radius: 4; -fx-font-size: 11; -fx-font-weight: bold;"
                : "-fx-background-color: #0f3460; -fx-text-fill: #c8c8e0; -fx-background-radius: 4; -fx-font-size: 11;");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
    }
}
