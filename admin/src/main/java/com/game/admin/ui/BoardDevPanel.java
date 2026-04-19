package com.game.admin.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class BoardDevPanel {

    // [tileKey, label, hexColor]
    private static final String[][] TILE_DEFS = {
        {"WALL",        "■  WALL",        "#2e2e2e"},
        {"FLOOR",       "□  FLOOR",       "#706850"},
        {"DOOR_CLOSED", "▣  DOOR CLOSED", "#7a3c10"},
        {"DOOR_OPEN",   "▢  DOOR OPEN",   "#c05818"},
        {"VOID",        "   VOID",        "#070710"},
    };

    // ── Data model ────────────────────────────────────────────────────────────

    public static class Board {
        public String   id     = UUID.randomUUID().toString();
        public String   name   = "New Board";
        public int      width  = 14;
        public int      depth  = 14;
        public int      spawnX = 1;
        public int      spawnZ = 1;
        public String[] tiles  = new String[0];  // flat [z*width + x]

        public String tileAt(int x, int z) {
            if (x < 0 || z < 0 || x >= width || z >= depth) return "WALL";
            int i = z * width + x;
            return (i < tiles.length && tiles[i] != null) ? tiles[i] : "WALL";
        }

        public void setTile(int x, int z, String t) {
            if (x < 0 || z < 0 || x >= width || z >= depth) return;
            ensureSize();
            tiles[z * width + x] = t;
        }

        public void ensureSize() {
            int n = width * depth;
            if (tiles.length != n) {
                tiles = Arrays.copyOf(tiles, n);
                for (int i = 0; i < n; i++) if (tiles[i] == null) tiles[i] = "WALL";
            }
        }

        public void resize(int nw, int nd) {
            String[] next = new String[nw * nd];
            Arrays.fill(next, "WALL");
            for (int z = 0; z < Math.min(depth, nd); z++)
                for (int x = 0; x < Math.min(width, nw); x++)
                    next[z * nw + x] = tileAt(x, z);
            width = nw; depth = nd; tiles = next;
        }

        /** Outer ring = WALL, interior = FLOOR. */
        public void initDefault() {
            tiles = new String[width * depth];
            for (int z = 0; z < depth; z++)
                for (int x = 0; x < width; x++)
                    tiles[z * width + x] =
                            (x == 0 || z == 0 || x == width - 1 || z == depth - 1) ? "WALL" : "FLOOR";
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private static final Path         DATA_FILE = Paths.get("admin", "src", "main", "resources", "boards.json");
    private static final ObjectMapper MAPPER    = new ObjectMapper();

    private final List<Board>           boards      = new ArrayList<>();
    private final ObservableList<Board> displayList = FXCollections.observableArrayList();
    private final ListView<Board>       listView    = new ListView<>(displayList);

    private Board  current;
    private String activeTile  = "WALL";
    private int    tileSize    = 24;
    private boolean formLoading = false;

    private Canvas           canvas;
    private TextField        nameField;
    private Spinner<Integer> widthSpinner, depthSpinner, spawnXSpinner, spawnZSpinner;
    private Label            statusLabel;
    private final ToggleGroup paletteGroup = new ToggleGroup();

    // ── Build ─────────────────────────────────────────────────────────────────

    public VBox build() {
        load();

        // ── Left: board list ──────────────────────────────────────────────────

        listView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Board b, boolean empty) {
                super.updateItem(b, empty);
                if (empty || b == null) { setText(null); return; }
                setText(b.name + "  [" + b.width + "×" + b.depth + "]");
                setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12; -fx-background-color: transparent;");
            }
        });
        listView.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;");
        listView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, sel) -> { if (sel != null) selectBoard(sel); });
        VBox.setVgrow(listView, Priority.ALWAYS);

        Button addBtn = btn("+ New",     "#50c050");
        Button delBtn = btn("Delete",    "#e94560");
        Button dupBtn = btn("Duplicate", "#3a5a8a");
        addBtn.setOnAction(e -> addNew());
        delBtn.setOnAction(e -> deleteSelected());
        dupBtn.setOnAction(e -> duplicate());

        HBox listBtns = new HBox(4, addBtn, delBtn);
        HBox.setHgrow(addBtn, Priority.ALWAYS);
        HBox.setHgrow(delBtn, Priority.ALWAYS);
        dupBtn.setMaxWidth(Double.MAX_VALUE);

        VBox leftCol = new VBox(6, lbl("Boards", 12, true), listView, listBtns, dupBtn);
        leftCol.setPadding(new Insets(10));
        leftCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");
        leftCol.setPrefWidth(200);

        // ── Center: canvas grid editor ────────────────────────────────────────

        canvas = new Canvas(480, 480);
        setupCanvasEvents();

        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setStyle("-fx-background: #0a0a18; -fx-background-color: #0a0a18;");
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        Button zoomIn  = smallBtn("+");
        Button zoomOut = smallBtn("−");
        Button fillW   = btn("Fill WALL",  "#3a2020");
        Button fillF   = btn("Fill FLOOR", "#203a20");
        zoomIn .setOnAction(e -> zoom(+4));
        zoomOut.setOnAction(e -> zoom(-4));
        fillW  .setOnAction(e -> fillAll("WALL"));
        fillF  .setOnAction(e -> fillAll("FLOOR"));

        Region tSpacer = new Region();
        HBox.setHgrow(tSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(6, lbl("Zoom:", 11, false), zoomOut, zoomIn, tSpacer, fillW, fillF);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(5, 10, 5, 10));
        toolbar.setStyle("-fx-background-color: #0f0f1e;");

        Label edTitle = lbl("Board Editor", 12, true);
        edTitle.setPadding(new Insets(8, 10, 4, 10));

        VBox centerCol = new VBox(edTitle, toolbar, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(centerCol, Priority.ALWAYS);
        centerCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");

        // ── Right: properties + tile palette ──────────────────────────────────

        nameField     = field();
        widthSpinner  = intSpinner(1, 200, 14);
        depthSpinner  = intSpinner(1, 200, 14);
        spawnXSpinner = intSpinner(0, 199, 1);
        spawnZSpinner = intSpinner(0, 199, 1);

        nameField.textProperty().addListener((o, ov, nv) -> {
            if (!formLoading && current != null) { current.name = nv; refreshList(); save(); }
        });
        widthSpinner .valueProperty().addListener((o, ov, nv) -> applyProps());
        depthSpinner .valueProperty().addListener((o, ov, nv) -> applyProps());
        spawnXSpinner.valueProperty().addListener((o, ov, nv) -> applyProps());
        spawnZSpinner.valueProperty().addListener((o, ov, nv) -> applyProps());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        form.getColumnConstraints().addAll(new ColumnConstraints(65), new ColumnConstraints(110));
        form.add(lbl("Name",    11, false), 0, 0); form.add(nameField,     1, 0);
        form.add(lbl("Width",   11, false), 0, 1); form.add(widthSpinner,  1, 1);
        form.add(lbl("Depth",   11, false), 0, 2); form.add(depthSpinner,  1, 2);
        form.add(lbl("Spawn X", 11, false), 0, 3); form.add(spawnXSpinner, 1, 3);
        form.add(lbl("Spawn Z", 11, false), 0, 4); form.add(spawnZSpinner, 1, 4);

        VBox propsSection = new VBox(6, lbl("Properties", 11, true), form);
        propsSection.setPadding(new Insets(8));
        propsSection.setStyle("-fx-background-color: #0f0f1e; -fx-background-radius: 3;");

        VBox paletteSection = new VBox(4, lbl("Tile Palette", 11, true));
        paletteSection.setPadding(new Insets(8));
        paletteSection.setStyle("-fx-background-color: #0f0f1e; -fx-background-radius: 3;");

        for (String[] td : TILE_DEFS) {
            String key = td[0], label = td[1], hex = td[2];
            ToggleButton tb = new ToggleButton(label);
            tb.setToggleGroup(paletteGroup);
            tb.setMaxWidth(Double.MAX_VALUE);
            tb.setUserData(key);
            tb.setStyle(paletteBtnStyle(hex, false));
            tb.selectedProperty().addListener((o, ov, nv) -> {
                tb.setStyle(paletteBtnStyle(hex, nv));
                if (nv) activeTile = key;
            });
            if (key.equals("WALL")) tb.setSelected(true);
            paletteSection.getChildren().add(tb);
        }

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #50c050; -fx-font-size: 10;");

        VBox rightCol = new VBox(10, propsSection, paletteSection, statusLabel);
        rightCol.setPadding(new Insets(10));
        rightCol.setPrefWidth(215);
        rightCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");

        // ── Root ──────────────────────────────────────────────────────────────

        HBox content = new HBox(10, leftCol, centerCol, rightCol);
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(leftCol, Priority.ALWAYS);
        VBox.setVgrow(centerCol, Priority.ALWAYS);

        VBox root = new VBox(content);
        VBox.setVgrow(content, Priority.ALWAYS);

        refreshList();
        if (!boards.isEmpty()) listView.getSelectionModel().selectFirst();
        return root;
    }

    // ── Canvas ────────────────────────────────────────────────────────────────

    private void setupCanvasEvents() {
        canvas.setOnMousePressed(e -> {
            paintAt((int) (e.getX() / tileSize), (int) (e.getY() / tileSize));
        });
        canvas.setOnMouseDragged(e -> {
            paintAt((int) (e.getX() / tileSize), (int) (e.getY() / tileSize));
        });
        canvas.setOnMouseReleased(e -> save());
    }

    private void paintAt(int x, int z) {
        if (current == null || x < 0 || z < 0 || x >= current.width || z >= current.depth) return;
        current.setTile(x, z, activeTile);
        // Redraw the changed tile and refresh the spawn marker (it may shift into/out of this cell)
        drawAll();
    }

    private void drawAll() {
        if (current == null) return;
        canvas.setWidth (current.width * tileSize);
        canvas.setHeight(current.depth * tileSize);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#070710"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        for (int z = 0; z < current.depth; z++)
            for (int x = 0; x < current.width; x++)
                drawTileAt(gc, x, z);

        drawSpawnMarker(gc);
    }

    private void drawTileAt(GraphicsContext gc, int x, int z) {
        double px = x * tileSize;
        double pz = z * tileSize;
        gc.setFill(tileColor(current.tileAt(x, z)));
        gc.fillRect(px + 1, pz + 1, tileSize - 2, tileSize - 2);
        gc.setStroke(Color.web("#1a1a3a"));
        gc.setLineWidth(1);
        gc.strokeRect(px, pz, tileSize, tileSize);
    }

    private void drawSpawnMarker(GraphicsContext gc) {
        if (current == null) return;
        int x = current.spawnX, z = current.spawnZ;
        if (x < 0 || z < 0 || x >= current.width || z >= current.depth) return;
        double px = x * tileSize + 1;
        double pz = z * tileSize + 1;
        double s  = tileSize - 2;
        gc.setFill(tileColor(current.tileAt(x, z)));
        gc.fillRect(px, pz, s, s);
        double ms = Math.max(4, s * 0.55);
        gc.setFill(Color.web("#00ff88", 0.75));
        gc.fillOval(px + (s - ms) / 2, pz + (s - ms) / 2, ms, ms);
        // Grid outline
        gc.setStroke(Color.web("#1a1a3a"));
        gc.setLineWidth(1);
        gc.strokeRect(x * tileSize, z * tileSize, tileSize, tileSize);
    }

    private static Color tileColor(String tile) {
        return switch (tile) {
            case "FLOOR"       -> Color.web("#706850");
            case "DOOR_CLOSED" -> Color.web("#7a3c10");
            case "DOOR_OPEN"   -> Color.web("#c05818");
            case "VOID"        -> Color.web("#070710");
            default            -> Color.web("#2e2e2e");  // WALL
        };
    }

    private void zoom(int delta) {
        tileSize = Math.max(8, Math.min(64, tileSize + delta));
        drawAll();
    }

    private void fillAll(String tile) {
        if (current == null) return;
        current.ensureSize();
        Arrays.fill(current.tiles, tile);
        drawAll();
        save();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void addNew() {
        Board b = new Board();
        b.initDefault();
        boards.add(b);
        refreshList();
        listView.getSelectionModel().select(b);
        save();
    }

    private void deleteSelected() {
        Board sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        boards.remove(sel);
        current = null;
        refreshList();
        save();
        if (!boards.isEmpty()) listView.getSelectionModel().selectFirst();
    }

    private void duplicate() {
        Board sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Board copy  = new Board();
        copy.id     = UUID.randomUUID().toString();
        copy.name   = sel.name + " Copy";
        copy.width  = sel.width;
        copy.depth  = sel.depth;
        copy.spawnX = sel.spawnX;
        copy.spawnZ = sel.spawnZ;
        copy.tiles  = Arrays.copyOf(sel.tiles, sel.tiles.length);
        boards.add(copy);
        refreshList();
        listView.getSelectionModel().select(copy);
        save();
    }

    private void selectBoard(Board b) {
        current = b;
        formLoading = true;
        nameField    .setText(b.name);
        widthSpinner .getValueFactory().setValue(b.width);
        depthSpinner .getValueFactory().setValue(b.depth);
        spawnXSpinner.getValueFactory().setValue(b.spawnX);
        spawnZSpinner.getValueFactory().setValue(b.spawnZ);
        formLoading = false;
        b.ensureSize();
        drawAll();
    }

    private void applyProps() {
        if (formLoading || current == null) return;
        int nw = widthSpinner .getValue();
        int nd = depthSpinner .getValue();
        int sx = Math.min(spawnXSpinner.getValue(), nw - 1);
        int sz = Math.min(spawnZSpinner.getValue(), nd - 1);
        if (nw != current.width || nd != current.depth) current.resize(nw, nd);
        current.spawnX = sx;
        current.spawnZ = sz;
        refreshList();
        drawAll();
        save();
    }

    private void refreshList() {
        Board sel = listView.getSelectionModel().getSelectedItem();
        displayList.setAll(boards);
        if (sel != null) listView.getSelectionModel().select(sel);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        boards.clear();
        if (Files.exists(DATA_FILE)) {
            try (InputStream in = Files.newInputStream(DATA_FILE)) {
                boards.addAll(MAPPER.readValue(in, new TypeReference<List<Board>>() {}));
            } catch (Exception e) {
                System.err.println("[BoardDevPanel] load failed: " + e.getMessage());
            }
        }
    }

    private void save() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(DATA_FILE)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, boards);
            }
            if (statusLabel != null) statusLabel.setText("Saved.");
        } catch (Exception e) {
            if (statusLabel != null) statusLabel.setText("Save failed: " + e.getMessage());
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private static String paletteBtnStyle(String bg, boolean selected) {
        return """
                -fx-background-color: %s;
                -fx-text-fill: #e0e0e0;
                -fx-font-size: 11;
                -fx-background-radius: 3;
                -fx-padding: 5 8 5 8;
                -fx-border-color: %s;
                -fx-border-width: 2;
                -fx-border-radius: 3;
                """.formatted(bg, selected ? "#ffffff" : "transparent");
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

    private static Button smallBtn(String text) {
        Button b = new Button(text);
        b.setPrefWidth(28);
        b.setStyle("""
                -fx-background-color: #3a5a8a;
                -fx-text-fill: white;
                -fx-font-size: 12;
                -fx-background-radius: 3;
                -fx-padding: 3 6 3 6;
                """);
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

    private static Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> s = new Spinner<>(min, max, initial);
        s.setEditable(true);
        s.setPrefWidth(90);
        return s;
    }
}
