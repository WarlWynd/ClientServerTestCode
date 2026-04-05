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

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

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

    private final Stage stage;

    private ListView<String> fileList;
    private ImageView        previewImage;
    private Label            previewNameLabel;
    private Label            previewSizeLabel;
    private Label            statusLabel;
    private ToggleGroup      categoryToggle;

    public GraphicsDevScreen(Stage stage) {
        this.stage = stage;
    }

    public Node build() {
        Tab filesTab   = new Tab("📁 Files",          buildFilesView());
        Tab spritesTab = new Tab("🕹 Sprite Preview", buildSpritePreview());
        filesTab.setClosable(false);
        spritesTab.setClosable(false);

        TabPane inner = new TabPane(filesTab, spritesTab);
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

    /** Live animated preview of every PlayerAnimator state. */
    private Node buildSpritePreview() {
        PlayerAnimator.State[] states = PlayerAnimator.State.values();

        // One animator per state, pinned to that state
        PlayerAnimator[] animators = new PlayerAnimator[states.length];
        for (int i = 0; i < states.length; i++) {
            animators[i] = new PlayerAnimator();
            animators[i].forceState(states[i]);
        }

        // Palette — one colour per state
        Color[] palette = {
            Color.web("#e0e0ff"),  // IDLE      — soft white-blue (local player colour)
            Color.web("#53c0f0"),  // RUN       — info blue
            Color.web("#50c050"),  // JUMP      — success green
            Color.web("#f0a030"),  // FALL      — warning amber
            Color.web("#e94560"),  // HIT       — accent red
            Color.web("#bd10e0")   // KNOCKED   — purple
        };

        // Canvas: 6 figures spread across, enough height for label
        int cols      = states.length;
        int colW      = 110;
        int canvasW   = cols * colW + 20;
        int canvasH   = 220;
        double feetY  = 160; // canvas Y where feet sit

        Canvas canvas = new Canvas(canvasW, canvasH);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Description labels beneath the canvas
        Label desc = new Label(
                "Live preview of all sprite animation states. " +
                "Colours match in-game player colouring.");
        desc.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");
        desc.setWrapText(true);

        // Legend
        StringBuilder legendText = new StringBuilder();
        for (PlayerAnimator.State s : states) legendText.append(s.name()).append("  ");
        Label legend = new Label(legendText.toString().trim());
        legend.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");

        // ── AnimationTimer drives the canvas ─────────────────────────────────
        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) {
                gc.setFill(Color.web("#0f0f1e"));
                gc.fillRect(0, 0, canvasW, canvasH);

                // Grid lines
                gc.setStroke(Color.web("#22224a"));
                gc.setLineWidth(1);
                for (int x = 0; x <= canvasW; x += colW) gc.strokeLine(x, 0, x, canvasH);
                gc.strokeLine(0, feetY, canvasW, feetY);  // floor line

                for (int i = 0; i < states.length; i++) {
                    double cx = 20 + i * colW + colW / 2.0;

                    // Shadow ellipse at feet
                    gc.setFill(Color.color(0, 0, 0, 0.3));
                    gc.fillOval(cx - 16, feetY - 4, 32, 8);

                    // Sprite
                    animators[i].draw(gc, cx, feetY, palette[i % palette.length]);

                    // State name label
                    gc.setFill(palette[i % palette.length].deriveColor(0, 1, 1.3, 1));
                    gc.setFont(javafx.scene.text.Font.font("System",
                            javafx.scene.text.FontWeight.BOLD, 11));
                    String name = states[i].name();
                    gc.fillText(name, cx - name.length() * 3.3, feetY + 24);
                }
            }
        };
        timer.start();

        // Keep the canvas at a fixed size inside a scroll pane
        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.setStyle("-fx-background-color: #0f0f1e;");
        scroll.setPrefViewportHeight(canvasH + 20);

        VBox root = new VBox(12, scroll, desc, legend);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: #1a1a2e;");
        return root;
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
        styleToggleButton(spritesBtn);
        styleToggleButton(bgBtn);
        styleToggleButton(uiBtn);

        HBox toggleBar = new HBox(4, spritesBtn, bgBtn, uiBtn);

        categoryToggle.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) refreshList();
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
        previewSizeLabel.setStyle("-fx-text-fill: #606080; -fx-font-size: 11;");

        previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        previewImage.setFitWidth(400);
        previewImage.setFitHeight(350);
        previewImage.setStyle("-fx-background-color: #0f0f1e;");

        StackPane imagePane = new StackPane(previewImage);
        imagePane.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a; -fx-border-radius: 4;");
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
        chooser.setTitle("Import Image File");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files", SUPPORTED_EXTENSIONS));

        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        String destDir = activeDir();
        try {
            Path dest = Paths.get(destDir, file.getName());
            Files.createDirectories(dest.getParent());
            Files.copy(file.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            status("✓ Imported: " + file.getName() + " → " + destDir);
            refreshList();
        } catch (IOException e) {
            status("✗ Import failed: " + e.getMessage());
        }
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
        l.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
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

    private void styleToggleButton(ToggleButton b) {
        b.setStyle("-fx-background-color: #0f3460;" +
                   "-fx-text-fill: white;" +
                   "-fx-background-radius: 4;" +
                   "-fx-font-size: 11;");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
    }
}
