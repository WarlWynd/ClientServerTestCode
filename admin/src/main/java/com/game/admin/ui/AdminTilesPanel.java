package com.game.admin.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Tile configuration editor.
 *
 * Each tile has: title, subtitle, icon (text/emoji), hex background color,
 * and an enabled flag. Tiles are stored in admin-tiles.json and shown in a
 * live preview card.
 */
public class AdminTilesPanel {

    // ── Data model ────────────────────────────────────────────────────────────

    public static class AdminTile {
        public String  id       = UUID.randomUUID().toString();
        public String  title    = "New Tile";
        public String  subtitle = "";
        public String  icon     = "★";
        public String  color    = "#2a2a5a";
        public boolean enabled  = true;
        public int     order    = 0;
    }

    private static final Path         DATA_FILE = Paths.get("admin", "src", "main", "resources", "admin-tiles.json");
    private static final ObjectMapper MAPPER    = new ObjectMapper();

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<AdminTile>             tiles       = new ArrayList<>();
    private final ObservableList<AdminTile>   displayList = FXCollections.observableArrayList();
    private final ListView<AdminTile>         listView    = new ListView<>(displayList);

    private AdminTile current;
    private boolean   loading;   // suppress listener side-effects while loading a tile

    // Form fields
    private TextField titleField;
    private TextField subtitleField;
    private TextField iconField;
    private TextField colorField;
    private CheckBox  enabledBox;
    private Label     statusLabel;

    // Preview
    private VBox  previewCard;
    private Label previewIconLabel;
    private Label previewTitleLabel;
    private Label previewSubtitleLabel;

    // ── Build ─────────────────────────────────────────────────────────────────

    public VBox build() {
        load();

        // ── Left: tile list ───────────────────────────────────────────────────
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(AdminTile t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) { setText(null); setStyle(""); return; }
                setText((t.enabled ? "● " : "○ ") + t.icon + "  " + t.title);
                setStyle("-fx-text-fill: " + (t.enabled ? "#e0e0e0" : "#505068")
                       + "; -fx-font-size: 12; -fx-background-color: transparent;");
            }
        });
        listView.setStyle("""
                -fx-background-color: #0f0f1e;
                -fx-border-color: #3a3a6a;
                -fx-border-width: 1;
                """);
        listView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, sel) -> { if (sel != null) loadIntoForm(sel); });
        VBox.setVgrow(listView, Priority.ALWAYS);

        Button addBtn  = btn("+ New Tile", "#50c050");
        Button delBtn  = btn("Delete",     "#e94560");
        Button upBtn   = btn("▲",          "#3a5a8a");
        Button downBtn = btn("▼",          "#3a5a8a");
        upBtn.setMaxWidth(Double.MAX_VALUE);
        downBtn.setMaxWidth(Double.MAX_VALUE);

        addBtn .setOnAction(e -> addNew());
        delBtn .setOnAction(e -> deleteSelected());
        upBtn  .setOnAction(e -> moveSelected(-1));
        downBtn.setOnAction(e -> moveSelected(+1));

        HBox orderRow = new HBox(4, upBtn, downBtn);
        HBox.setHgrow(upBtn,   Priority.ALWAYS);
        HBox.setHgrow(downBtn, Priority.ALWAYS);

        VBox leftCol = section("Tiles", listView, addBtn, delBtn, orderRow);
        leftCol.setPrefWidth(210);

        // ── Center: editor form ───────────────────────────────────────────────
        titleField    = field();
        subtitleField = field();
        iconField     = field();
        colorField    = field();
        enabledBox    = new CheckBox("Enabled");
        enabledBox.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12;");

        titleField.textProperty()   .addListener((o, ov, nv) -> sync(() -> { current.title    = nv; refreshList(); updatePreview(); save(); }));
        subtitleField.textProperty().addListener((o, ov, nv) -> sync(() -> { current.subtitle = nv; refreshList(); updatePreview(); save(); }));
        iconField.textProperty()    .addListener((o, ov, nv) -> sync(() -> { current.icon     = nv; refreshList(); updatePreview(); save(); }));
        colorField.textProperty()   .addListener((o, ov, nv) -> sync(() -> { current.color    = nv;               updatePreview(); save(); }));
        enabledBox.selectedProperty().addListener((o, ov, nv) -> sync(() -> { current.enabled  = nv; refreshList();                save(); }));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.getColumnConstraints().addAll(col(70), col(200));
        int r = 0;
        form.add(lbl("Title",    11, false), 0, r); form.add(titleField,    1, r++);
        form.add(lbl("Subtitle", 11, false), 0, r); form.add(subtitleField, 1, r++);
        form.add(lbl("Icon",     11, false), 0, r); form.add(iconField,     1, r++);
        form.add(lbl("Color",    11, false), 0, r); form.add(colorField,    1, r++);
        form.add(enabledBox,                 1, r);
        form.setStyle("-fx-background-color: transparent;");

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #50c050; -fx-font-size: 10;");

        VBox centerCol = section("Edit Tile", form, statusLabel);
        centerCol.setPrefWidth(320);

        // ── Right: live preview ────────────────────────────────────────────────
        previewIconLabel    = new Label("★");
        previewIconLabel.setFont(Font.font("System", FontWeight.BOLD, 30));
        previewIconLabel.setTextFill(Color.WHITE);

        previewTitleLabel   = new Label("Title");
        previewTitleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        previewTitleLabel.setTextFill(Color.WHITE);

        previewSubtitleLabel = new Label("Subtitle");
        previewSubtitleLabel.setFont(Font.font("System", 11));
        previewSubtitleLabel.setTextFill(Color.web("#a0a0c0"));

        previewCard = new VBox(8, previewIconLabel, previewTitleLabel, previewSubtitleLabel);
        previewCard.setAlignment(Pos.CENTER);
        previewCard.setPadding(new Insets(18));
        previewCard.setPrefSize(160, 120);
        previewCard.setMaxSize(160, 120);
        previewCard.setStyle("-fx-background-color: #2a2a5a; -fx-background-radius: 8;");

        VBox rightCol = section("Preview", previewCard);
        rightCol.setPrefWidth(200);

        // ── Root ──────────────────────────────────────────────────────────────
        HBox content = new HBox(14, leftCol, centerCol, rightCol);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color: #1a1a2e;");
        HBox.setHgrow(centerCol, Priority.ALWAYS);
        VBox.setVgrow(leftCol, Priority.ALWAYS);

        VBox root = new VBox(content);
        VBox.setVgrow(content, Priority.ALWAYS);

        refreshList();
        if (!tiles.isEmpty()) listView.getSelectionModel().selectFirst();

        return root;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void addNew() {
        AdminTile t = new AdminTile();
        t.order = tiles.size();
        tiles.add(t);
        refreshList();
        listView.getSelectionModel().select(t);
        save();
    }

    private void deleteSelected() {
        AdminTile sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        tiles.remove(sel);
        current = null;
        refreshList();
        save();
        if (!tiles.isEmpty()) listView.getSelectionModel().selectFirst();
    }

    private void moveSelected(int delta) {
        AdminTile sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        int idx    = tiles.indexOf(sel);
        int newIdx = idx + delta;
        if (newIdx < 0 || newIdx >= tiles.size()) return;
        tiles.remove(idx);
        tiles.add(newIdx, sel);
        for (int i = 0; i < tiles.size(); i++) tiles.get(i).order = i;
        refreshList();
        listView.getSelectionModel().select(sel);
        save();
    }

    private void loadIntoForm(AdminTile t) {
        loading = true;
        titleField   .setText(nvl(t.title));
        subtitleField.setText(nvl(t.subtitle));
        iconField    .setText(nvl(t.icon));
        colorField   .setText(nvl(t.color, "#2a2a5a"));
        enabledBox   .setSelected(t.enabled);
        loading = false;
        current = t;
        updatePreview();
    }

    private void refreshList() {
        AdminTile sel = listView.getSelectionModel().getSelectedItem();
        tiles.sort(Comparator.comparingInt(t -> t.order));
        displayList.setAll(tiles);
        if (sel != null) listView.getSelectionModel().select(sel);
    }

    private void updatePreview() {
        if (current == null) return;
        previewIconLabel   .setText(nvl(current.icon));
        previewTitleLabel  .setText(nvl(current.title));
        previewSubtitleLabel.setText(nvl(current.subtitle));
        String c = nvl(current.color, "#2a2a5a");
        try { Color.web(c); }   // validate; fall back on bad input
        catch (Exception ex) { c = "#2a2a5a"; }
        previewCard.setStyle("-fx-background-color: " + c + "; -fx-background-radius: 8;");
    }

    // Fires action only when not in the middle of a load
    private void sync(Runnable action) {
        if (!loading && current != null) action.run();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        tiles.clear();
        if (Files.exists(DATA_FILE)) {
            try (InputStream in = Files.newInputStream(DATA_FILE)) {
                tiles.addAll(MAPPER.readValue(in, new TypeReference<List<AdminTile>>() {}));
            } catch (Exception e) {
                System.err.println("[AdminTilesPanel] load failed: " + e.getMessage());
            }
        }
        if (tiles.isEmpty()) createDefaults();
    }

    private void createDefaults() {
        Object[][] defs = {
            {"Players",  "Online users",    "#1a3a6a", "👥"},
            {"Items",    "Item registry",   "#2a1a4a", "🗡"},
            {"Spells",   "Spell library",   "#1a2a4a", "✨"},
            {"Quests",   "Quest tracker",   "#1a4a2a", "📜"},
            {"Server",   "Server status",   "#3a1a1a", "⚙"},
        };
        for (int i = 0; i < defs.length; i++) {
            AdminTile t = new AdminTile();
            t.title    = (String) defs[i][0];
            t.subtitle = (String) defs[i][1];
            t.color    = (String) defs[i][2];
            t.icon     = (String) defs[i][3];
            t.order    = i;
            tiles.add(t);
        }
        save();
    }

    private void save() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(DATA_FILE)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, tiles);
            }
            if (statusLabel != null) statusLabel.setText("Saved.");
        } catch (Exception e) {
            if (statusLabel != null) statusLabel.setText("Save failed.");
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private static VBox section(String heading, javafx.scene.Node... children) {
        Label title = lbl(heading, 12, true);
        VBox box = new VBox(8);
        box.getChildren().add(title);
        box.getChildren().addAll(children);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");
        return box;
    }

    private static Label lbl(String text, double size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: " + size + ";"
                 + (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private static Button btn(String text, String color) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("""
                -fx-background-color: %s;
                -fx-text-fill: white;
                -fx-font-size: 11;
                -fx-background-radius: 3;
                -fx-padding: 5 8 5 8;
                """.formatted(color));
        return b;
    }

    private static TextField field() {
        TextField f = new TextField();
        f.setStyle("""
                -fx-background-color: #16213e;
                -fx-text-fill: #e0e0e0;
                -fx-prompt-text-fill: #505070;
                -fx-border-color: #3a3a6a;
                -fx-border-radius: 3;
                -fx-background-radius: 3;
                -fx-padding: 4 6 4 6;
                """);
        return f;
    }

    private static ColumnConstraints col(double width) {
        ColumnConstraints c = new ColumnConstraints(width);
        return c;
    }

    private static String nvl(String s)               { return s != null ? s : ""; }
    private static String nvl(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }
}
