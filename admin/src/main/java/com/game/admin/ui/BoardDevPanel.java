package com.game.admin.ui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class BoardDevPanel {

    // ── Furnishing catalogue ──────────────────────────────────────────────────

    static final class Furnishing {
        final String id; final String label; final boolean canSprinkle;
        Furnishing(String id, String label, boolean canSprinkle) {
            this.id = id; this.label = label; this.canSprinkle = canSprinkle;
        }
    }

    static final List<Furnishing> FURNISHINGS = List.of(
        new Furnishing("table",     "Table",     true),
        new Furnishing("chair",     "Chair",     true),
        new Furnishing("barrel",    "Barrel",    true),
        new Furnishing("chest",     "Chest",     false),
        new Furnishing("torch",     "Torch",     true),
        new Furnishing("bookshelf", "Bookshelf", false),
        new Furnishing("bed",       "Bed",       false),
        new Furnishing("rug",       "Rug",       true)
    );

    static final String[] POSITIONS = {"N", "E", "S", "W", "Center"};

    // ── Texture catalogues ────────────────────────────────────────────────────

    static final String[] WALL_TEXTURES  = {"Stone", "Brick", "Wood", "Ice", "Cave", "Marble"};
    static final String[] FLOOR_TEXTURES = {"Stone", "Wood Planks", "Dirt", "Marble", "Grass", "Ice"};
    static final String[] WALL_TYPES     = {"Default", "Mine", "Castle", "Hut", "Tunnel", "Dungeon", "Temple"};

    // ── Tile definitions ──────────────────────────────────────────────────────

    private static final String[][] TILE_DEFS = {
        {"WALL",        "■  WALL",        "#2e2e2e"},
        {"FLOOR",       "□  FLOOR",       "#706850"},
        {"DOOR_CLOSED", "▣  DOOR CLOSED", "#7a3c10"},
        {"DOOR_OPEN",   "▢  DOOR OPEN",   "#c05818"},
        {"VOID",        "   VOID",        "#070710"},
    };

    // ── Data model ────────────────────────────────────────────────────────────

    public static class TileItem {
        public String itemId   = "";
        public String position = "Center";
    }

    public static class Board {
        public String   id     = UUID.randomUUID().toString();
        public String   name   = "New Board";
        public int      width  = 14;
        public int      depth  = 14;
        public int      spawnX = 1;
        public int      spawnZ = 1;
        public String[] tiles  = new String[0];

        // Legacy board-level texture fields — migrated to per-tile maps on load, then nulled.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String wallTexture;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String floorTexture;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, List<TileItem>> tileItems    = new LinkedHashMap<>();

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, String>         wallTypes    = new LinkedHashMap<>();  // style: Mine/Castle/etc.

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, String>         wallTextures = new LinkedHashMap<>();  // per-tile wall texture

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, String>         floorTextures= new LinkedHashMap<>();  // per-tile floor texture

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

        public String tileKey(int x, int z) { return x + "," + z; }

        public List<TileItem> getTileItems(int x, int z) {
            List<TileItem> list = tileItems.get(tileKey(x, z));
            return list != null ? list : Collections.emptyList();
        }

        public void setTileItems(int x, int z, List<TileItem> items) {
            String key = tileKey(x, z);
            if (items == null || items.isEmpty()) tileItems.remove(key);
            else tileItems.put(key, new ArrayList<>(items));
        }

        public String getWallType(int x, int z) {
            String v = wallTypes.get(tileKey(x, z));
            return v != null ? v : "Default";
        }

        public void setWallType(int x, int z, String type) {
            String key = tileKey(x, z);
            if (type == null || type.equals("Default")) wallTypes.remove(key);
            else wallTypes.put(key, type);
        }

        public String getWallTexture(int x, int z) {
            String v = wallTextures.get(tileKey(x, z));
            return v != null ? v : "Stone";
        }

        public void setWallTexture(int x, int z, String tex) {
            String key = tileKey(x, z);
            if (tex == null || tex.equals("Stone")) wallTextures.remove(key);
            else wallTextures.put(key, tex);
        }

        public String getFloorTexture(int x, int z) {
            String v = floorTextures.get(tileKey(x, z));
            return v != null ? v : "Stone";
        }

        public void setFloorTexture(int x, int z, String tex) {
            String key = tileKey(x, z);
            if (tex == null || tex.equals("Stone")) floorTextures.remove(key);
            else floorTextures.put(key, tex);
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
            tileItems.entrySet().removeIf(e -> {
                String[] p = e.getKey().split(",");
                return Integer.parseInt(p[0]) >= nw || Integer.parseInt(p[1]) >= nd;
            });
            wallTypes.entrySet().removeIf(e -> {
                String[] p = e.getKey().split(",");
                return Integer.parseInt(p[0]) >= nw || Integer.parseInt(p[1]) >= nd;
            });
            wallTextures.entrySet().removeIf(e -> {
                String[] p = e.getKey().split(",");
                return Integer.parseInt(p[0]) >= nw || Integer.parseInt(p[1]) >= nd;
            });
            floorTextures.entrySet().removeIf(e -> {
                String[] p = e.getKey().split(",");
                return Integer.parseInt(p[0]) >= nw || Integer.parseInt(p[1]) >= nd;
            });
            width = nw; depth = nd; tiles = next;
        }

        public void initDefault() {
            tiles = new String[width * depth];
            for (int z = 0; z < depth; z++)
                for (int x = 0; x < width; x++)
                    tiles[z * width + x] =
                        (x == 0 || z == 0 || x == width-1 || z == depth-1) ? "WALL" : "FLOOR";
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private static final Path         DATA_FILE = Paths.get("admin", "src", "main", "resources", "boards.json");
    private static final ObjectMapper MAPPER    = new ObjectMapper();

    private static final int[] DX = { 0,  1,  0, -1};
    private static final int[] DZ = {-1,  0,  1,  0};

    private final List<Board>           boards      = new ArrayList<>();
    private final ObservableList<Board> displayList = FXCollections.observableArrayList();
    private final ListView<Board>       listView    = new ListView<>(displayList);

    private Board   current;
    private String  activeTile    = "WALL";
    private int     tileSize      = 24;
    private boolean formLoading   = false;
    private int     selectedTileX = -1;
    private int     selectedTileZ = -1;

    private BoardTestWindow activeTest = null;
    private int[]           testPlayer = null;

    private Canvas           canvas;
    private TextField        nameField;
    private Spinner<Integer> widthSpinner, depthSpinner, spawnXSpinner, spawnZSpinner;
    private Label            statusLabel;
    private final ToggleGroup paletteGroup = new ToggleGroup();

    private Label  selectedTileLabel;
    private VBox   itemsBox;
    private Button previewBtn;

    private final Map<String, CheckBox> sprinkleCbs = new LinkedHashMap<>();
    private Spinner<Integer> densitySpinner;

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

        // ── Center: canvas ────────────────────────────────────────────────────
        canvas = new Canvas(480, 480);
        setupCanvasEvents();

        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setStyle("-fx-background: #0a0a18; -fx-background-color: #0a0a18;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        Button zoomIn    = smallBtn("+");
        Button zoomOut   = smallBtn("−");
        Button fillW     = btn("Fill WALL",     "#3a2020");
        Button fillF     = btn("Fill FLOOR",    "#203a20");
        Button testBoard = btn("▶  Test Board", "#2a4a8a");
        zoomIn   .setOnAction(e -> zoom(+4));
        zoomOut  .setOnAction(e -> zoom(-4));
        fillW    .setOnAction(e -> fillAll("WALL"));
        fillF    .setOnAction(e -> fillAll("FLOOR"));
        testBoard.setOnAction(e -> {
            if (current == null) return;
            if (activeTest != null) activeTest.close();
            activeTest = new BoardTestWindow(current,
                pos -> { testPlayer = pos; drawAll(); },
                ()  -> { testPlayer = null; activeTest = null; drawAll(); });
            activeTest.show();
        });

        Region tSpacer = new Region();
        HBox.setHgrow(tSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(6, lbl("Zoom:", 11, false), zoomOut, zoomIn, tSpacer, fillW, fillF, testBoard);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(5, 10, 5, 10));
        toolbar.setStyle("-fx-background-color: #0f0f1e;");

        Label edTitle = lbl("Board Editor", 12, true);
        edTitle.setPadding(new Insets(8, 10, 4, 10));

        VBox centerCol = new VBox(edTitle, toolbar, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(centerCol, Priority.ALWAYS);
        centerCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");

        // ── Right: properties + palette + items + sprinkle ────────────────────
        nameField      = field();
        widthSpinner   = intSpinner(1, 200, 14);
        depthSpinner   = intSpinner(1, 200, 14);
        spawnXSpinner  = intSpinner(0, 199, 1);
        spawnZSpinner  = intSpinner(0, 199, 1);

        nameField.textProperty().addListener((o, ov, nv) -> {
            if (!formLoading && current != null) { current.name = nv; refreshList(); save(); }
        });
        widthSpinner .valueProperty().addListener((o, ov, nv) -> applyProps());
        depthSpinner .valueProperty().addListener((o, ov, nv) -> applyProps());
        spawnXSpinner.valueProperty().addListener((o, ov, nv) -> applyProps());
        spawnZSpinner.valueProperty().addListener((o, ov, nv) -> applyProps());

        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(6);
        form.getColumnConstraints().addAll(new ColumnConstraints(65), new ColumnConstraints(110));
        form.add(lbl("Name",         11, false), 0, 0); form.add(nameField,      1, 0);
        form.add(lbl("Width",        11, false), 0, 1); form.add(widthSpinner,   1, 1);
        form.add(lbl("Depth",        11, false), 0, 2); form.add(depthSpinner,   1, 2);
        form.add(lbl("Spawn X",      11, false), 0, 3); form.add(spawnXSpinner,  1, 3);
        form.add(lbl("Spawn Z",      11, false), 0, 4); form.add(spawnZSpinner,  1, 4);

        VBox propsSection = section("Properties", form);

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

        // Tile contents editor
        selectedTileLabel = new Label("Click a tile to edit contents");
        selectedTileLabel.setStyle("-fx-text-fill: #707090; -fx-font-size: 10;");
        itemsBox = new VBox(5);
        previewBtn = btn("Preview Tile", "#4a3a6a");
        previewBtn.setDisable(true);
        previewBtn.setOnAction(e -> openTilePreview());
        VBox itemsSection = section("Tile Contents", selectedTileLabel, itemsBox, previewBtn);

        // Sprinkle
        VBox sprinkleCbBox = new VBox(4);
        for (Furnishing f : FURNISHINGS) {
            if (!f.canSprinkle) continue;
            CheckBox cb = new CheckBox(f.label);
            cb.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 11;");
            sprinkleCbs.put(f.id, cb);
            sprinkleCbBox.getChildren().add(cb);
        }
        densitySpinner = intSpinner(1, 100, 20);
        HBox densityRow = new HBox(6, lbl("Density %:", 11, false), densitySpinner);
        densityRow.setAlignment(Pos.CENTER_LEFT);
        Button sprinkleBtn = btn("Sprinkle Items", "#3a3a8a");
        sprinkleBtn.setOnAction(e -> sprinkle());
        VBox sprinkleSection = section("Sprinkle", sprinkleCbBox, densityRow, sprinkleBtn);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #50c050; -fx-font-size: 10;");

        VBox rightContent = new VBox(10, propsSection, paletteSection, itemsSection, sprinkleSection, statusLabel);
        rightContent.setPadding(new Insets(10));

        ScrollPane rightScroll = new ScrollPane(rightContent);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.setStyle("-fx-background: #16213e; -fx-background-color: #16213e;");
        rightScroll.setPrefWidth(230);
        rightScroll.setMaxWidth(230);

        // ── Root ──────────────────────────────────────────────────────────────
        HBox content = new HBox(10, leftCol, centerCol, rightScroll);
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(leftCol,    Priority.ALWAYS);
        VBox.setVgrow(centerCol,  Priority.ALWAYS);
        VBox.setVgrow(rightScroll, Priority.ALWAYS);

        VBox root = new VBox(content);
        VBox.setVgrow(content, Priority.ALWAYS);

        refreshList();
        if (!boards.isEmpty()) listView.getSelectionModel().selectFirst();
        return root;
    }

    // ── Canvas ────────────────────────────────────────────────────────────────

    private void setupCanvasEvents() {
        canvas.setOnMousePressed(e -> {
            int tx = (int)(e.getX() / tileSize);
            int tz = (int)(e.getY() / tileSize);
            paintAt(tx, tz);
            selectTile(tx, tz);
        });
        canvas.setOnMouseDragged(e -> paintAt((int)(e.getX()/tileSize), (int)(e.getY()/tileSize)));
        canvas.setOnMouseReleased(e -> save());
    }

    private void paintAt(int x, int z) {
        if (current == null || x < 0 || z < 0 || x >= current.width || z >= current.depth) return;
        if (!activeTile.equals("FLOOR") && !activeTile.equals("DOOR_OPEN"))
            current.setTileItems(x, z, null);
        current.setTile(x, z, activeTile);
        drawAll();
    }

    private void selectTile(int x, int z) {
        if (current == null || x < 0 || z < 0 || x >= current.width || z >= current.depth) return;
        selectedTileX = x;
        selectedTileZ = z;
        rebuildItemsPanel(x, z);
    }

    private void drawAll() {
        if (current == null) return;
        canvas.setWidth (current.width  * tileSize);
        canvas.setHeight(current.depth * tileSize);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#070710"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (int z = 0; z < current.depth; z++)
            for (int x = 0; x < current.width; x++)
                drawTileAt(gc, x, z);
        drawSpawnMarker(gc);
        if (testPlayer != null) drawTestPlayerArrow(gc, testPlayer[0], testPlayer[1], testPlayer[2]);
    }

    private void drawTileAt(GraphicsContext gc, int x, int z) {
        double px = x * tileSize, pz = z * tileSize;
        gc.setFill(tileColor(current.tileAt(x, z)));
        gc.fillRect(px + 1, pz + 1, tileSize - 2, tileSize - 2);
        gc.setStroke(Color.web("#1a1a3a"));
        gc.setLineWidth(1);
        gc.strokeRect(px, pz, tileSize, tileSize);

        if (!current.getTileItems(x, z).isEmpty() && tileSize >= 10) {
            double r = Math.max(2, tileSize * 0.13);
            gc.setFill(Color.web("#ffffff", 0.75));
            gc.fillOval(px + tileSize - r * 2 - 2, pz + 2, r * 2, r * 2);
        }
        if (x == selectedTileX && z == selectedTileZ) {
            gc.setStroke(Color.web("#ffffff", 0.85));
            gc.setLineWidth(1.5);
            gc.strokeRect(px + 1, pz + 1, tileSize - 2, tileSize - 2);
        }
    }

    private void drawSpawnMarker(GraphicsContext gc) {
        if (current == null) return;
        int x = current.spawnX, z = current.spawnZ;
        if (x < 0 || z < 0 || x >= current.width || z >= current.depth) return;
        double px = x * tileSize + 1, pz = z * tileSize + 1, s = tileSize - 2;
        gc.setFill(tileColor(current.tileAt(x, z)));
        gc.fillRect(px, pz, s, s);
        double ms = Math.max(4, s * 0.55);
        gc.setFill(Color.web("#00ff88", 0.75));
        gc.fillOval(px + (s - ms) / 2, pz + (s - ms) / 2, ms, ms);
        gc.setStroke(Color.web("#1a1a3a"));
        gc.setLineWidth(1);
        gc.strokeRect(x * tileSize, z * tileSize, tileSize, tileSize);
    }

    private void drawTestPlayerArrow(GraphicsContext gc, int px, int pz, int facing) {
        double cx = px * tileSize + tileSize / 2.0;
        double cz = pz * tileSize + tileSize / 2.0;
        double r  = tileSize * 0.38;
        double fx  = cx + DX[facing] * r, fz = cz + DZ[facing] * r;
        int    lf  = (facing + 3) % 4, rt = (facing + 1) % 4;
        double s   = r * 0.6;
        double blx = cx - DX[facing] * r * 0.5 + DX[lf] * s;
        double blz = cz - DZ[facing] * r * 0.5 + DZ[lf] * s;
        double brx = cx - DX[facing] * r * 0.5 + DX[rt] * s;
        double brz = cz - DZ[facing] * r * 0.5 + DZ[rt] * s;
        gc.setFill(Color.web("#e94560", 0.9));
        gc.fillPolygon(new double[]{fx, blx, brx}, new double[]{fz, blz, brz}, 3);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1.5);
        gc.strokePolygon(new double[]{fx, blx, brx}, new double[]{fz, blz, brz}, 3);
    }

    private static Color tileColor(String tile) {
        return switch (tile) {
            case "FLOOR"       -> Color.web("#706850");
            case "DOOR_CLOSED" -> Color.web("#7a3c10");
            case "DOOR_OPEN"   -> Color.web("#c05818");
            case "VOID"        -> Color.web("#070710");
            default            -> Color.web("#2e2e2e");
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
        if (!tile.equals("FLOOR") && !tile.equals("DOOR_OPEN")) current.tileItems.clear();
        drawAll();
        save();
    }

    // ── Tile contents editor ──────────────────────────────────────────────────

    private void rebuildItemsPanel(int x, int z) {
        itemsBox.getChildren().clear();
        if (current == null) { selectedTileLabel.setText("Click a tile to edit contents"); return; }

        String tileType = current.tileAt(x, z);
        selectedTileLabel.setText("Tile (" + x + ", " + z + ")  —  " + tileType);

        if (tileType.equals("WALL")) {
            ComboBox<String> styleBox = combo(WALL_TYPES,    current.getWallType(x, z));
            ComboBox<String> texBox   = combo(WALL_TEXTURES, current.getWallTexture(x, z));
            styleBox.valueProperty().addListener((o, ov, nv) -> { current.setWallType(x, z, nv);    save(); });
            texBox  .valueProperty().addListener((o, ov, nv) -> { current.setWallTexture(x, z, nv); save(); });
            itemsBox.getChildren().addAll(
                row("Style:", styleBox),
                row("Texture:", texBox));
            if (previewBtn != null) previewBtn.setDisable(true);
            return;
        }

        boolean canHaveItems = tileType.equals("FLOOR") || tileType.equals("DOOR_OPEN");
        if (!canHaveItems) {
            Label note = new Label(tileType + " tiles have no contents.");
            note.setStyle("-fx-text-fill: #505068; -fx-font-size: 10;");
            itemsBox.getChildren().add(note);
            if (previewBtn != null) previewBtn.setDisable(true);
            return;
        }
        if (previewBtn != null) previewBtn.setDisable(false);

        ComboBox<String> floorTexBox = combo(FLOOR_TEXTURES, current.getFloorTexture(x, z));
        floorTexBox.valueProperty().addListener((o, ov, nv) -> { current.setFloorTexture(x, z, nv); save(); });
        itemsBox.getChildren().add(row("Texture:", floorTexBox));

        List<TileItem> existing = new ArrayList<>(current.getTileItems(x, z));

        for (Furnishing f : FURNISHINGS) {
            TileItem match = existing.stream()
                    .filter(i -> i.itemId.equals(f.id)).findFirst().orElse(null);
            boolean present = match != null;
            String  pos     = match != null ? match.position : "Center";

            CheckBox cb = new CheckBox(f.label);
            cb.setSelected(present);
            cb.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 11;");

            ComboBox<String> posBox = new ComboBox<>(FXCollections.observableArrayList(POSITIONS));
            posBox.setValue(pos);
            posBox.setDisable(!present);
            posBox.setPrefWidth(75);
            posBox.setStyle("-fx-font-size: 10;");

            cb.selectedProperty().addListener((o, ov, nv) -> {
                posBox.setDisable(!nv);
                updateTileItem(x, z, f.id, nv ? posBox.getValue() : null);
            });
            posBox.valueProperty().addListener((o, ov, nv) -> {
                if (cb.isSelected()) updateTileItem(x, z, f.id, nv);
            });

            HBox row = new HBox(8, cb, posBox);
            row.setAlignment(Pos.CENTER_LEFT);
            itemsBox.getChildren().add(row);
        }
    }

    private void updateTileItem(int x, int z, String itemId, String position) {
        List<TileItem> items = new ArrayList<>(current.getTileItems(x, z));
        items.removeIf(i -> i.itemId.equals(itemId));
        if (position != null) {
            TileItem item = new TileItem();
            item.itemId = itemId; item.position = position;
            items.add(item);
        }
        current.setTileItems(x, z, items);
        drawAll();
        save();
    }

    // ── Tile preview ──────────────────────────────────────────────────────────

    private void openTilePreview() {
        if (current == null || selectedTileX < 0 || selectedTileZ < 0) return;
        int tx = selectedTileX, tz = selectedTileZ;

        // Build a 3×3 mini-board centered on the selected tile
        // Build a 5×5 room: border = WALL, interior = FLOOR, center (2,2) has items.
        // Always force walls so the preview is always an enclosed room.
        int MS = 5, C = 2;

        // Find the nearest wall texture from the real board to use for preview walls.
        String wallTex = "Stone";
        outer:
        for (int r = 1; r <= 5; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++)
                    if ((Math.abs(dx) == r || Math.abs(dz) == r)
                            && current.tileAt(tx+dx, tz+dz).equals("WALL")) {
                        wallTex = current.getWallTexture(tx+dx, tz+dz);
                        break outer;
                    }

        String floorTex = current.getFloorTexture(tx, tz);

        Board mini = new Board();
        mini.name   = "Preview (" + tx + "," + tz + ")";
        mini.width  = MS; mini.depth = MS;
        mini.spawnX = C;  mini.spawnZ = C;
        mini.tiles  = new String[MS * MS];
        for (int mz = 0; mz < MS; mz++) {
            for (int mx = 0; mx < MS; mx++) {
                boolean border = mx == 0 || mx == MS-1 || mz == 0 || mz == MS-1;
                if (border) {
                    mini.tiles[mz * MS + mx] = "WALL";
                    mini.setWallTexture(mx, mz, wallTex);
                } else {
                    mini.tiles[mz * MS + mx] = "FLOOR";
                    mini.setFloorTexture(mx, mz, floorTex);
                }
            }
        }
        mini.setTileItems(C, C, current.getTileItems(tx, tz));

        Board3DViewWindow win = new Board3DViewWindow(mini);
        win.show();
        win.getStage().setTitle("Preview (" + tx + "," + tz + ") — A/D turn  PgUp/PgDn pitch");

        int[] facing = {0};
        win.positionPreviewCamera(C, C, facing[0]);

        win.getStage().getScene().setOnKeyPressed((KeyEvent e) -> {
            switch (e.getCode()) {
                case A, LEFT  -> facing[0] = (facing[0] + 1) % 4;
                case D, RIGHT -> facing[0] = (facing[0] + 3) % 4;
                case PAGE_UP   -> { win.adjustPitch(-5); return; }
                case PAGE_DOWN -> { win.adjustPitch(+5); return; }
                default -> { return; }
            }
            win.positionPreviewCamera(C, C, facing[0]);
        });
    }

    // ── Sprinkle ──────────────────────────────────────────────────────────────

    private void sprinkle() {
        if (current == null) return;
        current.ensureSize();
        List<String> ids = sprinkleCbs.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey).collect(Collectors.toList());
        if (ids.isEmpty()) return;

        List<int[]> floors = new ArrayList<>();
        for (int z = 0; z < current.depth; z++)
            for (int x = 0; x < current.width; x++)
                if (current.tileAt(x, z).equals("FLOOR")) floors.add(new int[]{x, z});
        Collections.shuffle(floors);

        int max = Math.max(1, floors.size() * densitySpinner.getValue() / 100);
        Random rng = new Random();
        int placed = 0;
        for (int[] tile : floors) {
            if (placed >= max) break;
            int tx = tile[0], tz = tile[1];
            String id = ids.get(rng.nextInt(ids.size()));
            List<TileItem> items = new ArrayList<>(current.getTileItems(tx, tz));
            if (items.stream().anyMatch(i -> i.itemId.equals(id))) continue;
            TileItem item = new TileItem();
            item.itemId = id; item.position = bestPosition(tx, tz);
            items.add(item);
            current.setTileItems(tx, tz, items);
            placed++;
        }
        drawAll(); save();
    }

    private String bestPosition(int x, int z) {
        int[][] dirs = {{0,-1},{1,0},{0,1},{-1,0}};
        String[] names = {"N","E","S","W"};
        for (int d = 0; d < 4; d++)
            if (current.tileAt(x + dirs[d][0], z + dirs[d][1]).equals("WALL")) return names[d];
        return "Center";
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void addNew() {
        Board b = new Board(); b.initDefault();
        boards.add(b); refreshList();
        listView.getSelectionModel().select(b); save();
    }

    private void deleteSelected() {
        Board sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        boards.remove(sel); current = null; refreshList(); save();
        if (!boards.isEmpty()) listView.getSelectionModel().selectFirst();
    }

    private void duplicate() {
        Board sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Board copy = new Board();
        copy.id = UUID.randomUUID().toString(); copy.name = sel.name + " Copy";
        copy.width = sel.width; copy.depth = sel.depth;
        copy.spawnX = sel.spawnX; copy.spawnZ = sel.spawnZ;
        copy.tiles = Arrays.copyOf(sel.tiles, sel.tiles.length);
        sel.tileItems.forEach((k, v) -> copy.tileItems.put(k, new ArrayList<>(v)));
        copy.wallTypes    .putAll(sel.wallTypes);
        copy.wallTextures .putAll(sel.wallTextures);
        copy.floorTextures.putAll(sel.floorTextures);
        boards.add(copy); refreshList();
        listView.getSelectionModel().select(copy); save();
    }

    private void selectBoard(Board b) {
        if (activeTest != null) { activeTest.close(); activeTest = null; testPlayer = null; }
        selectedTileX = -1; selectedTileZ = -1;
        current = b;
        formLoading = true;
        nameField    .setText(b.name);
        widthSpinner .getValueFactory().setValue(b.width);
        depthSpinner .getValueFactory().setValue(b.depth);
        spawnXSpinner.getValueFactory().setValue(b.spawnX);
        spawnZSpinner.getValueFactory().setValue(b.spawnZ);
        formLoading = false;
        b.ensureSize();
        if (selectedTileLabel != null) {
            selectedTileLabel.setText("Click a tile to edit contents");
            itemsBox.getChildren().clear();
        }
        drawAll();
    }

    private void applyProps() {
        if (formLoading || current == null) return;
        int nw = widthSpinner.getValue(), nd = depthSpinner.getValue();
        int sx = Math.min(spawnXSpinner.getValue(), nw - 1);
        int sz = Math.min(spawnZSpinner.getValue(), nd - 1);
        if (nw != current.width || nd != current.depth) current.resize(nw, nd);
        current.spawnX = sx; current.spawnZ = sz;
        refreshList(); drawAll(); save();
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
                boards.forEach(this::migrateLegacyTiles);
            } catch (Exception e) {
                System.err.println("[BoardDevPanel] load failed: " + e.getMessage());
            }
        }
    }

    /** Convert legacy data: old TABLE/CHAIR tile types and board-level textures. */
    private void migrateLegacyTiles(Board b) {
        if (b.tiles == null) return;
        int[][] dirs = {{0,-1},{1,0},{0,1},{-1,0}};
        String[] posNames = {"N","E","S","W"};
        for (int z = 0; z < b.depth; z++) {
            for (int x = 0; x < b.width; x++) {
                String tile = b.tileAt(x, z);
                String legacyId = switch (tile) {
                    case "TABLE" -> "table";
                    case "CHAIR" -> "chair";
                    default -> null;
                };
                if (legacyId == null) continue;
                b.setTile(x, z, "FLOOR");
                String pos = "Center";
                for (int d = 0; d < 4; d++)
                    if (b.tileAt(x + dirs[d][0], z + dirs[d][1]).equals("WALL"))
                        { pos = posNames[d]; break; }
                TileItem item = new TileItem();
                item.itemId = legacyId; item.position = pos;
                b.setTileItems(x, z, List.of(item));
            }
        }
        // Migrate old board-level wall texture to all WALL tiles that have no texture set.
        if (b.wallTexture != null && !b.wallTexture.equals("Stone")) {
            for (int z = 0; z < b.depth; z++)
                for (int x = 0; x < b.width; x++)
                    if (b.tileAt(x, z).equals("WALL") && b.wallTextures.get(b.tileKey(x, z)) == null)
                        b.setWallTexture(x, z, b.wallTexture);
        }
        b.wallTexture = null;
        // Migrate old board-level floor texture similarly.
        if (b.floorTexture != null && !b.floorTexture.equals("Stone")) {
            for (int z = 0; z < b.depth; z++)
                for (int x = 0; x < b.width; x++) {
                    String t = b.tileAt(x, z);
                    if ((t.equals("FLOOR") || t.equals("DOOR_OPEN") || t.equals("DOOR_CLOSED"))
                            && b.floorTextures.get(b.tileKey(x, z)) == null)
                        b.setFloorTexture(x, z, b.floorTexture);
                }
        }
        b.floorTexture = null;
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

    private static VBox section(String heading, javafx.scene.Node... children) {
        VBox box = new VBox(6);
        box.getChildren().add(lbl(heading, 11, true));
        box.getChildren().addAll(children);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: #0f0f1e; -fx-background-radius: 3;");
        return box;
    }

    private static String paletteBtnStyle(String bg, boolean selected) {
        return """
                -fx-background-color: %s;
                -fx-text-fill: #e0e0e0; -fx-font-size: 11;
                -fx-background-radius: 3; -fx-padding: 5 8 5 8;
                -fx-border-color: %s; -fx-border-width: 2; -fx-border-radius: 3;
                """.formatted(bg, selected ? "#ffffff" : "transparent");
    }

    private static Label lbl(String text, double size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: " + size
                + (bold ? "; -fx-font-weight: bold;" : ";"));
        return l;
    }

    private static Button btn(String text, String color) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color: %s; -fx-text-fill: white; -fx-font-size: 11; -fx-background-radius: 3; -fx-padding: 5 8 5 8;"
                .formatted(color));
        return b;
    }

    private static Button smallBtn(String text) {
        Button b = new Button(text);
        b.setPrefWidth(28);
        b.setStyle("-fx-background-color: #3a5a8a; -fx-text-fill: white; -fx-font-size: 12; -fx-background-radius: 3; -fx-padding: 3 6 3 6;");
        return b;
    }

    private static TextField field() {
        TextField f = new TextField();
        f.setStyle("-fx-background-color: #16213e; -fx-text-fill: #e0e0e0; -fx-prompt-text-fill: #505070; -fx-border-color: #3a3a6a; -fx-border-radius: 3; -fx-background-radius: 3; -fx-padding: 4 6 4 6;");
        return f;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> s = new Spinner<>(min, max, initial);
        s.setEditable(true); s.setPrefWidth(90);
        return s;
    }

    private static HBox row(String label, javafx.scene.Node control) {
        HBox h = new HBox(8, lbl(label, 11, false), control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static ComboBox<String> combo(String[] items, String initial) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(items));
        c.setValue(initial);
        c.setMaxWidth(Double.MAX_VALUE);
        c.setStyle("-fx-font-size: 11;");
        return c;
    }
}
