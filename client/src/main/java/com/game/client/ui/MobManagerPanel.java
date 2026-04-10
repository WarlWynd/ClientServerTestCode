package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Mob Manager — two inner tabs:
 *
 *   Mob Roster   — define mob archetypes (stats, animations, loot table assignment)
 *   Map Spawns   — place/remove mob spawn points on any saved board (CSV)
 *
 * Saves to:
 *   client/src/main/resources/graphics/sprites/mob-definitions.json
 *   client/src/main/resources/graphics/sprites/mob-spawns.json
 */
public class MobManagerPanel {

    private static final Path MOB_FILE   =
            Paths.get("client/src/main/resources/graphics/sprites/mob-definitions.json");
    private static final Path SPAWN_FILE =
            Paths.get("client/src/main/resources/graphics/sprites/mob-spawns.json");
    private static final File BOARDS_DIR =
            new File(System.getProperty("user.home"), ".game/boards");

    // ── Mob category ──────────────────────────────────────────────────────────
    public enum MobCategory { BEAST, UNDEAD, HUMANOID, BOSS }

    // ── Mob archetype ─────────────────────────────────────────────────────────
    public static class MobDef {
        String      name;
        MobCategory category;
        Color       tint;
        int         baseHp;
        int         baseDamage;
        int         speed;
        int         aggroRange;
        String      lootTable = "";
        final List<PlayerAnimator.State> states = new ArrayList<>();

        MobDef(String name, MobCategory category, Color tint) {
            this.name = name; this.category = category; this.tint = tint;
            this.baseHp = 100; this.baseDamage = 10; this.speed = 3; this.aggroRange = 200;
        }
    }

    // ── Spawn point (per board) ───────────────────────────────────────────────
    public static class SpawnPoint {
        String mobName;
        int    col, row;
        int    respawnSec;
        int    maxCount;

        SpawnPoint(String mob, int col, int row, int respawnSec, int maxCount) {
            this.mobName = mob; this.col = col; this.row = row;
            this.respawnSec = respawnSec; this.maxCount = maxCount;
        }
    }

    // ── Panel state ───────────────────────────────────────────────────────────

    // Mob roster
    private final List<MobDef>  mobs     = new ArrayList<>();
    private MobDef              selected = null;

    // Map spawns
    private final Map<String, List<SpawnPoint>> spawns = new LinkedHashMap<>(); // boardName → spawns
    private String              currentBoard = null;
    private BoardTile[][]       boardGrid    = null;
    private int                 boardRows    = 0, boardCols = 0;
    private SpawnPoint          hoveredSpawn = null;

    // Spawn placement state
    private String              placingMob   = null; // mob name currently being placed

    // UI refs — Mob Roster
    private ListView<String>    mobList;
    private TextField           nameField;
    private ComboBox<String>    categoryCombo;
    private ColorPicker         colorPicker;
    private Spinner<Integer>    hpSpinner, dmgSpinner, speedSpinner, aggroSpinner;
    private List<CheckBox>      stateChecks;
    private ComboBox<String>    lootTableCombo;
    private Label               rosterStatus;

    // UI refs — Map Spawns
    private ComboBox<String>    boardCombo;
    private ListView<String>    spawnList;
    private Canvas              mapCanvas;
    private Label               spawnStatus;
    private ComboBox<String>    placeMobCombo;
    private Spinner<Integer>    respawnSpinner, maxCountSpinner;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        loadMobs();
        loadSpawns();
        ensureDefaults();

        Tab rosterTab = new Tab("🐾 Mob Roster",  buildRosterTab());
        Tab spawnTab  = new Tab("🗺 Map Spawns",  buildSpawnTab());
        rosterTab.setClosable(false);
        spawnTab.setClosable(false);

        TabPane inner = new TabPane(rosterTab, spawnTab);
        inner.getStyleClass().add("tab-pane-dark");
        return inner;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MOB ROSTER TAB
    // ══════════════════════════════════════════════════════════════════════════

    private Node buildRosterTab() {
        // Left — mob list
        mobList = new ListView<>();
        mobList.setPrefWidth(190);
        mobList.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                         "-fx-border-radius: 4; -fx-control-inner-background: #0f0f1e;");
        mobList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                if (!empty) setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12;" +
                        "-fx-background-color: " + (isSelected() ? "#3a3a6a" : "transparent") + ";");
            }
        });
        refreshMobList();
        mobList.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            if (i >= 0 && i < mobs.size()) loadIntoForm(mobs.get(i));
        });

        Button addBeastBtn  = btn("+ Beast",    "#1a3a1a");
        Button addUndeadBtn = btn("+ Undead",   "#3a1a3a");
        Button addHumanBtn  = btn("+ Humanoid", "#1a2a3a");
        Button addBossBtn   = btn("+ Boss",     "#3a1a1a");
        Button delBtn       = btn("Delete",     "#7b241c");
        addBeastBtn.setOnAction(e  -> addNew(MobCategory.BEAST));
        addUndeadBtn.setOnAction(e -> addNew(MobCategory.UNDEAD));
        addHumanBtn.setOnAction(e  -> addNew(MobCategory.HUMANOID));
        addBossBtn.setOnAction(e   -> addNew(MobCategory.BOSS));
        delBtn.setOnAction(e       -> deleteSelected());

        VBox leftCol = vbox(8, lbl("Mobs", 13, true), mobList,
                new HBox(4, addBeastBtn, addUndeadBtn),
                new HBox(4, addHumanBtn, addBossBtn),
                new HBox(4, delBtn));
        leftCol.setPrefWidth(200);

        // Centre — config
        nameField = new TextField();
        nameField.setPromptText("Mob name…");
        nameField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                           "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5;");
        nameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null) { selected.name = n; refreshMobList(); }
        });

        categoryCombo = new ComboBox<>();
        categoryCombo.getItems().addAll("BEAST", "UNDEAD", "HUMANOID", "BOSS");
        categoryCombo.getStyleClass().add("combo-dark");
        categoryCombo.setMaxWidth(Double.MAX_VALUE);
        categoryCombo.setOnAction(e -> {
            if (selected != null && categoryCombo.getValue() != null)
                selected.category = MobCategory.valueOf(categoryCombo.getValue());
        });

        colorPicker = new ColorPicker(Color.web("#e94560"));
        colorPicker.setStyle("-fx-color-label-visible: false;");
        colorPicker.setOnAction(e -> { if (selected != null) selected.tint = colorPicker.getValue(); });

        hpSpinner    = intSpinner(1, 9999, 100);
        dmgSpinner   = intSpinner(1, 999,  10);
        speedSpinner = intSpinner(1, 50,   3);
        aggroSpinner = intSpinner(10, 2000, 200);
        hpSpinner.valueProperty().addListener((obs, o, n)    -> { if (selected != null) selected.baseHp     = n; });
        dmgSpinner.valueProperty().addListener((obs, o, n)   -> { if (selected != null) selected.baseDamage = n; });
        speedSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.speed      = n; });
        aggroSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.aggroRange = n; });

        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(8); statsGrid.setVgap(6);
        statsGrid.add(lbl("HP:",         10, false), 0, 0); statsGrid.add(hpSpinner,    1, 0);
        statsGrid.add(lbl("Damage:",     10, false), 0, 1); statsGrid.add(dmgSpinner,   1, 1);
        statsGrid.add(lbl("Speed:",      10, false), 0, 2); statsGrid.add(speedSpinner, 1, 2);
        statsGrid.add(lbl("Aggro Range:",10, false), 0, 3); statsGrid.add(aggroSpinner, 1, 3);
        ColumnConstraints cc0 = new ColumnConstraints(); cc0.setPrefWidth(80);
        ColumnConstraints cc1 = new ColumnConstraints(); cc1.setHgrow(Priority.ALWAYS);
        statsGrid.getColumnConstraints().addAll(cc0, cc1);

        lootTableCombo = new ComboBox<>();
        lootTableCombo.getItems().add("— None —");
        lootTableCombo.getItems().addAll(LootTablePanel.loadTableNames());
        lootTableCombo.setValue("— None —");
        lootTableCombo.getStyleClass().add("combo-dark");
        lootTableCombo.setMaxWidth(Double.MAX_VALUE);
        lootTableCombo.setOnAction(e -> {
            if (selected == null) return;
            String v = lootTableCombo.getValue();
            selected.lootTable = (v == null || v.startsWith("—")) ? "" : v;
        });

        Label statesLbl = lbl("Animation States:", 11, true);
        stateChecks = new ArrayList<>();
        FlowPane statesPane = new FlowPane(6, 4);
        statesPane.setStyle("-fx-background-color: #0f0f1e; -fx-padding: 8;" +
                            "-fx-border-color: #3a3a6a; -fx-border-radius: 4;");
        for (PlayerAnimator.State s : PlayerAnimator.State.values()) {
            CheckBox cb = new CheckBox(s.name());
            cb.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 10;");
            cb.setOnAction(ev -> syncStates());
            stateChecks.add(cb);
            statesPane.getChildren().add(cb);
        }
        ScrollPane statesScroll = new ScrollPane(statesPane);
        statesScroll.setFitToWidth(true);
        statesScroll.setStyle("-fx-background: #0f0f1e; -fx-background-color: #0f0f1e;");
        statesScroll.setPrefHeight(160);

        Button allBtn  = btn("All",    "#1e3a5f");
        Button noneBtn = btn("None",   "#3a1e2e");
        Button saveBtn = btn("💾 Save", "#1e5f3a");
        allBtn.setOnAction(e  -> { stateChecks.forEach(c -> c.setSelected(true));  syncStates(); });
        noneBtn.setOnAction(e -> { stateChecks.forEach(c -> c.setSelected(false)); syncStates(); });
        saveBtn.setOnAction(e -> saveMobs());

        rosterStatus = new Label();
        rosterStatus.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        VBox cfgCol = vbox(8,
                lbl("Configuration", 13, true),
                new VBox(4, lbl("Name:",      11, false), nameField),
                new VBox(4, lbl("Category:",  11, false), categoryCombo),
                new HBox(10, lbl("Tint:",     11, false), colorPicker),
                new VBox(4, lbl("Loot Table:", 11, false), lootTableCombo),
                lbl("Base Stats:", 11, true), statsGrid,
                statesLbl, new HBox(6, allBtn, noneBtn), statesScroll,
                saveBtn, rosterStatus);
        cfgCol.setPrefWidth(340);

        HBox root = new HBox(8, leftCol, cfgCol);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");
        if (!mobs.isEmpty()) mobList.getSelectionModel().select(0);
        return root;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MAP SPAWNS TAB
    // ══════════════════════════════════════════════════════════════════════════

    private static final int TILE_PX = 8; // minimap pixels per tile

    private Node buildSpawnTab() {
        // ── Top controls ──────────────────────────────────────────────────────
        Label boardLbl = lbl("Board:", 11, false);
        boardCombo = new ComboBox<>();
        boardCombo.getStyleClass().add("combo-dark");
        boardCombo.setPrefWidth(220);
        boardCombo.setOnAction(e -> loadBoardForSpawn(boardCombo.getValue()));

        Button refreshBoardsBtn = btn("↻", "#1e3a5f");
        refreshBoardsBtn.setPrefWidth(32);
        refreshBoardsBtn.setOnAction(e -> refreshBoardCombo());
        HBox boardRow = new HBox(6, boardLbl, boardCombo, refreshBoardsBtn);
        boardRow.setAlignment(Pos.CENTER_LEFT);

        // ── Placement controls ────────────────────────────────────────────────
        Label placeLbl    = lbl("Place Mob:", 11, false);
        placeMobCombo = new ComboBox<>();
        placeMobCombo.getStyleClass().add("combo-dark");
        placeMobCombo.setPrefWidth(160);
        syncPlaceMobCombo();

        Label respawnLbl  = lbl("Respawn (s):", 10, false);
        respawnSpinner    = intSpinner(0, 3600, 30);
        respawnSpinner.setPrefWidth(80);

        Label maxCntLbl   = lbl("Max Count:", 10, false);
        maxCountSpinner   = intSpinner(1, 50, 2);
        maxCountSpinner.setPrefWidth(70);

        Label clickHint = new Label("Left-click map to place  •  Right-click spawn to remove");
        clickHint.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");

        HBox placeRow = new HBox(8, placeLbl, placeMobCombo,
                respawnLbl, respawnSpinner, maxCntLbl, maxCountSpinner);
        placeRow.setAlignment(Pos.CENTER_LEFT);

        VBox topBar = new VBox(6, boardRow, placeRow, clickHint);
        topBar.setPadding(new Insets(8));
        topBar.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");

        // ── Map canvas ────────────────────────────────────────────────────────
        mapCanvas = new Canvas(640, 320);
        mapCanvas.setStyle("-fx-cursor: crosshair;");
        StackPane canvasWrap = new StackPane(mapCanvas);
        canvasWrap.setStyle("-fx-background-color: #0a0a18; -fx-border-color: #3a3a6a;");
        ScrollPane mapScroll = new ScrollPane(canvasWrap);
        mapScroll.setStyle("-fx-background: #0a0a18; -fx-background-color: #0a0a18;");
        mapScroll.setFitToWidth(false);
        mapScroll.setFitToHeight(false);
        VBox.setVgrow(mapScroll, Priority.ALWAYS);

        drawMapCanvas(); // draw empty state

        mapCanvas.setOnMouseClicked(e -> {
            if (boardGrid == null) return;
            int col = (int)(e.getX() / TILE_PX);
            int row = (int)(e.getY() / TILE_PX);
            if (col < 0 || col >= boardCols || row < 0 || row >= boardRows) return;

            if (e.getButton() == MouseButton.PRIMARY) {
                placeSpawn(col, row);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                removeSpawn(col, row);
            }
        });

        mapCanvas.setOnMouseMoved(e -> {
            if (boardGrid == null) return;
            int col = (int)(e.getX() / TILE_PX);
            int row = (int)(e.getY() / TILE_PX);
            SpawnPoint found = null;
            if (currentBoard != null) {
                List<SpawnPoint> pts = spawns.getOrDefault(currentBoard, List.of());
                for (SpawnPoint sp : pts) {
                    if (sp.col == col && sp.row == row) { found = sp; break; }
                }
            }
            hoveredSpawn = found;
            drawMapCanvas();
        });

        // ── Spawn list ────────────────────────────────────────────────────────
        spawnList = new ListView<>();
        spawnList.setPrefHeight(140);
        spawnList.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                           "-fx-border-radius: 4; -fx-control-inner-background: #0f0f1e;");
        spawnList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                if (!empty) setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 11;");
            }
        });

        Button clearBoardBtn = btn("Clear Board Spawns", "#7b241c");
        Button saveSpawnBtn  = btn("💾 Save Spawns",      "#1e5f3a");
        clearBoardBtn.setOnAction(e -> {
            if (currentBoard != null) {
                spawns.remove(currentBoard);
                refreshSpawnList();
                drawMapCanvas();
            }
        });
        saveSpawnBtn.setOnAction(e -> saveSpawns());

        spawnStatus = new Label();
        spawnStatus.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        HBox spawnBtns = new HBox(6, clearBoardBtn, saveSpawnBtn);

        VBox bottomBar = new VBox(6,
                lbl("Spawns on this board:", 11, true),
                spawnList,
                spawnBtns,
                spawnStatus);
        bottomBar.setPadding(new Insets(8));
        bottomBar.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");

        VBox root = new VBox(8, topBar, mapScroll, bottomBar);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");

        refreshBoardCombo();
        return root;
    }

    // ── Map spawn logic ───────────────────────────────────────────────────────

    private void refreshBoardCombo() {
        String prev = boardCombo.getValue();
        boardCombo.getItems().clear();
        File[] csvs = BOARDS_DIR.listFiles((d, n) -> n.endsWith(".csv"));
        if (csvs != null) Arrays.sort(csvs);
        if (csvs != null) for (File f : csvs) boardCombo.getItems().add(f.getName());
        if (prev != null && boardCombo.getItems().contains(prev)) boardCombo.setValue(prev);
        else if (!boardCombo.getItems().isEmpty()) boardCombo.setValue(boardCombo.getItems().get(0));
    }

    private void loadBoardForSpawn(String boardFileName) {
        if (boardFileName == null) return;
        currentBoard = boardFileName;
        boardGrid = null;
        boardRows = 0; boardCols = 0;

        File f = new File(BOARDS_DIR, boardFileName);
        if (!f.exists()) { drawMapCanvas(); return; }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String header = br.readLine();
            if (header == null) return;
            String[] hw = header.split(",");
            boardRows = Integer.parseInt(hw[0].trim());
            boardCols = Integer.parseInt(hw[1].trim());
            boardGrid = new BoardTile[boardRows][boardCols];
            for (int r = 0; r < boardRows; r++) {
                String line = br.readLine();
                if (line == null) break;
                String[] cells = line.split(",");
                for (int c = 0; c < boardCols; c++) {
                    String name = (c < cells.length) ? cells[c].trim() : "AIR";
                    try { boardGrid[r][c] = BoardTile.valueOf(name); }
                    catch (IllegalArgumentException ex) { boardGrid[r][c] = BoardTile.AIR; }
                }
            }
            mapCanvas.setWidth(boardCols * TILE_PX);
            mapCanvas.setHeight(boardRows * TILE_PX);
        } catch (Exception ignored) {}

        refreshSpawnList();
        drawMapCanvas();
    }

    private void placeSpawn(int col, int row) {
        String mobName = placeMobCombo.getValue();
        if (mobName == null || mobName.isEmpty()) {
            spawnStatus.setText("Select a mob first.");
            spawnStatus.setStyle("-fx-text-fill: #f0a030; -fx-font-size: 11;");
            return;
        }
        List<SpawnPoint> pts = spawns.computeIfAbsent(currentBoard, k -> new ArrayList<>());
        // One spawn per tile
        pts.removeIf(sp -> sp.col == col && sp.row == row);
        pts.add(new SpawnPoint(mobName, col, row,
                respawnSpinner.getValue(), maxCountSpinner.getValue()));
        refreshSpawnList();
        drawMapCanvas();
    }

    private void removeSpawn(int col, int row) {
        if (currentBoard == null) return;
        List<SpawnPoint> pts = spawns.get(currentBoard);
        if (pts == null) return;
        pts.removeIf(sp -> sp.col == col && sp.row == row);
        refreshSpawnList();
        drawMapCanvas();
    }

    private void refreshSpawnList() {
        spawnList.getItems().clear();
        if (currentBoard == null) return;
        List<SpawnPoint> pts = spawns.getOrDefault(currentBoard, List.of());
        for (SpawnPoint sp : pts)
            spawnList.getItems().add(String.format(
                    "%s  @ col %d  row %d  (respawn %ds, max %d)",
                    sp.mobName, sp.col, sp.row, sp.respawnSec, sp.maxCount));
    }

    private void drawMapCanvas() {
        GraphicsContext gc = mapCanvas.getGraphicsContext2D();
        double w = mapCanvas.getWidth(), h = mapCanvas.getHeight();
        gc.setFill(Color.web("#0a0a18"));
        gc.fillRect(0, 0, w, h);

        if (boardGrid == null) {
            gc.setFill(Color.web("#404060"));
            gc.setFont(javafx.scene.text.Font.font("System", 13));
            gc.fillText("Select a board above.", 20, 30);
            return;
        }

        // Draw tiles
        for (int r = 0; r < boardRows; r++) {
            for (int c = 0; c < boardCols; c++) {
                BoardTile t = boardGrid[r][c];
                if (t == BoardTile.AIR) continue;
                gc.setFill(t.fill);
                gc.fillRect(c * TILE_PX, r * TILE_PX, TILE_PX, TILE_PX);
                gc.setStroke(t.border);
                gc.setLineWidth(0.5);
                gc.strokeRect(c * TILE_PX, r * TILE_PX, TILE_PX, TILE_PX);
            }
        }

        // Draw spawn markers
        List<SpawnPoint> pts = spawns.getOrDefault(currentBoard, List.of());
        for (SpawnPoint sp : pts) {
            Color tint = mobTint(sp.mobName);
            boolean hov = sp == hoveredSpawn;
            double cx = sp.col * TILE_PX + TILE_PX / 2.0;
            double cy = sp.row * TILE_PX + TILE_PX / 2.0;
            double r  = hov ? 6.5 : 5.0;
            gc.setFill(Color.color(0, 0, 0, 0.55));
            gc.fillOval(cx - r + 1, cy - r + 1, r * 2, r * 2); // shadow
            gc.setFill(tint);
            gc.fillOval(cx - r, cy - r, r * 2, r * 2);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(hov ? 1.5 : 1.0);
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            // Initial letter
            gc.setFill(Color.WHITE);
            gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 6));
            String init = sp.mobName.isEmpty() ? "?" : sp.mobName.substring(0, 1).toUpperCase();
            gc.fillText(init, cx - 2.5, cy + 2.5);
        }

        // Hover tooltip
        if (hoveredSpawn != null) {
            String tip = String.format("%s  (respawn %ds, max %d)",
                    hoveredSpawn.mobName, hoveredSpawn.respawnSec, hoveredSpawn.maxCount);
            double tx = hoveredSpawn.col * TILE_PX + TILE_PX;
            double ty = hoveredSpawn.row * TILE_PX - 4;
            gc.setFill(Color.color(0, 0, 0, 0.75));
            gc.fillRoundRect(tx - 2, ty - 12, tip.length() * 5.5 + 6, 14, 4, 4);
            gc.setFill(Color.web("#e0e0e0"));
            gc.setFont(javafx.scene.text.Font.font("System", 10));
            gc.fillText(tip, tx + 1, ty);
        }
    }

    private Color mobTint(String mobName) {
        for (MobDef m : mobs) if (m.name.equals(mobName)) return m.tint;
        return Color.web("#e94560");
    }

    private void syncPlaceMobCombo() {
        if (placeMobCombo == null) return;
        String prev = placeMobCombo.getValue();
        placeMobCombo.getItems().clear();
        mobs.forEach(m -> placeMobCombo.getItems().add(m.name));
        if (prev != null && placeMobCombo.getItems().contains(prev))
            placeMobCombo.setValue(prev);
        else if (!placeMobCombo.getItems().isEmpty())
            placeMobCombo.setValue(placeMobCombo.getItems().get(0));
    }

    // ── Mob Roster CRUD ───────────────────────────────────────────────────────

    private void addNew(MobCategory cat) {
        Color tint = switch (cat) {
            case BEAST    -> Color.web("#50c050");
            case UNDEAD   -> Color.web("#aa66ff");
            case HUMANOID -> Color.web("#53c0f0");
            case BOSS     -> Color.web("#f0a030");
        };
        MobDef mob = new MobDef("New " + cat.name().charAt(0) + cat.name().substring(1).toLowerCase(), cat, tint);
        mob.states.add(PlayerAnimator.State.IDLE);
        mob.states.add(PlayerAnimator.State.RUN);
        mobs.add(mob);
        refreshMobList();
        mobList.getSelectionModel().select(mobs.size() - 1);
        syncPlaceMobCombo();
    }

    private void deleteSelected() {
        if (selected == null) return;
        mobs.remove(selected);
        selected = null;
        clearForm();
        refreshMobList();
        if (!mobs.isEmpty()) mobList.getSelectionModel().select(0);
        syncPlaceMobCombo();
    }

    private void loadIntoForm(MobDef mob) {
        selected = mob;
        nameField.setText(mob.name);
        colorPicker.setValue(mob.tint);
        categoryCombo.setValue(mob.category.name());
        hpSpinner.getValueFactory().setValue(mob.baseHp);
        dmgSpinner.getValueFactory().setValue(mob.baseDamage);
        speedSpinner.getValueFactory().setValue(mob.speed);
        aggroSpinner.getValueFactory().setValue(mob.aggroRange);
        // Loot table combo — refresh names from file each time
        String prev = lootTableCombo.getValue();
        lootTableCombo.getItems().clear();
        lootTableCombo.getItems().add("— None —");
        lootTableCombo.getItems().addAll(LootTablePanel.loadTableNames());
        String lt = (mob.lootTable == null || mob.lootTable.isEmpty()) ? "— None —" : mob.lootTable;
        lootTableCombo.setValue(lootTableCombo.getItems().contains(lt) ? lt : "— None —");
        for (CheckBox cb : stateChecks) {
            try { cb.setSelected(mob.states.contains(PlayerAnimator.State.valueOf(cb.getText()))); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private void clearForm() {
        nameField.setText("");
        stateChecks.forEach(c -> c.setSelected(false));
    }

    private void syncStates() {
        if (selected == null) return;
        selected.states.clear();
        for (CheckBox cb : stateChecks) {
            if (!cb.isSelected()) continue;
            try { selected.states.add(PlayerAnimator.State.valueOf(cb.getText())); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private void refreshMobList() {
        String selName = selected != null ? selected.name : null;
        mobList.getItems().clear();
        for (MobDef mob : mobs) {
            String icon = switch (mob.category) {
                case BEAST    -> "🐺 ";
                case UNDEAD   -> "💀 ";
                case HUMANOID -> "⚔ ";
                case BOSS     -> "👑 ";
            };
            mobList.getItems().add(icon + mob.name);
        }
        if (selName != null)
            for (int i = 0; i < mobs.size(); i++)
                if (mobs.get(i).name.equals(selName)) { mobList.getSelectionModel().select(i); break; }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void saveMobs() {
        try {
            ObjectMapper om = new ObjectMapper();
            ArrayNode root  = om.createArrayNode();
            for (MobDef mob : mobs) {
                ObjectNode node = om.createObjectNode();
                node.put("name",       mob.name);
                node.put("category",   mob.category.name());
                node.put("tint",       toHex(mob.tint));
                node.put("baseHp",     mob.baseHp);
                node.put("baseDamage", mob.baseDamage);
                node.put("speed",      mob.speed);
                node.put("aggroRange", mob.aggroRange);
                node.put("lootTable",  mob.lootTable);
                ArrayNode states = om.createArrayNode();
                mob.states.forEach(s -> states.add(s.name()));
                node.set("states", states);
                root.add(node);
            }
            Files.createDirectories(MOB_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(MOB_FILE.toFile(), root);
            setStatus(rosterStatus, "✓ Saved " + mobs.size() + " mob(s).", true);
        } catch (Exception e) {
            setStatus(rosterStatus, "✗ Save failed: " + e.getMessage(), false);
        }
    }

    private void saveSpawns() {
        try {
            ObjectMapper om  = new ObjectMapper();
            ObjectNode root  = om.createObjectNode();
            for (Map.Entry<String, List<SpawnPoint>> entry : spawns.entrySet()) {
                ArrayNode arr = om.createArrayNode();
                for (SpawnPoint sp : entry.getValue()) {
                    ObjectNode node = om.createObjectNode();
                    node.put("mob",        sp.mobName);
                    node.put("col",        sp.col);
                    node.put("row",        sp.row);
                    node.put("respawnSec", sp.respawnSec);
                    node.put("maxCount",   sp.maxCount);
                    arr.add(node);
                }
                root.set(entry.getKey(), arr);
            }
            Files.createDirectories(SPAWN_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(SPAWN_FILE.toFile(), root);
            int total = spawns.values().stream().mapToInt(List::size).sum();
            setStatus(spawnStatus, "✓ Saved " + total + " spawn point(s) across "
                    + spawns.size() + " board(s).", true);
        } catch (Exception e) {
            setStatus(spawnStatus, "✗ Save failed: " + e.getMessage(), false);
        }
    }

    private void loadMobs() {
        mobs.clear();
        if (!Files.exists(MOB_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode node : om.readTree(MOB_FILE.toFile())) {
                MobCategory cat  = MobCategory.valueOf(node.path("category").asText("BEAST"));
                Color       tint = Color.web(node.path("tint").asText("#e94560"));
                MobDef mob = new MobDef(node.path("name").asText("Mob"), cat, tint);
                mob.baseHp     = node.path("baseHp").asInt(100);
                mob.baseDamage = node.path("baseDamage").asInt(10);
                mob.speed      = node.path("speed").asInt(3);
                mob.aggroRange = node.path("aggroRange").asInt(200);
                mob.lootTable  = node.path("lootTable").asText("");
                for (JsonNode s : node.path("states")) {
                    try { mob.states.add(PlayerAnimator.State.valueOf(s.asText())); }
                    catch (IllegalArgumentException ignored) {}
                }
                mobs.add(mob);
            }
        } catch (Exception ignored) {}
    }

    private void loadSpawns() {
        spawns.clear();
        if (!Files.exists(SPAWN_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(SPAWN_FILE.toFile());
            root.fields().forEachRemaining(entry -> {
                List<SpawnPoint> pts = new ArrayList<>();
                for (JsonNode node : entry.getValue()) {
                    pts.add(new SpawnPoint(
                            node.path("mob").asText(""),
                            node.path("col").asInt(0),
                            node.path("row").asInt(0),
                            node.path("respawnSec").asInt(30),
                            node.path("maxCount").asInt(1)));
                }
                spawns.put(entry.getKey(), pts);
            });
        } catch (Exception ignored) {}
    }

    private void ensureDefaults() {
        if (!mobs.isEmpty()) return;
        MobDef wolf = new MobDef("Wolf", MobCategory.BEAST, Color.web("#888888"));
        wolf.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.KNOCKED_DOWN));
        wolf.baseHp = 60; wolf.baseDamage = 12; wolf.speed = 5; wolf.aggroRange = 300;

        MobDef skeleton = new MobDef("Skeleton", MobCategory.UNDEAD, Color.web("#ccccaa"));
        skeleton.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS,
                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.KNOCKED_DOWN));
        skeleton.baseHp = 50; skeleton.baseDamage = 8; skeleton.speed = 3; skeleton.aggroRange = 250;

        MobDef bandit = new MobDef("Bandit", MobCategory.HUMANOID, Color.web("#c08030"));
        bandit.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS, PlayerAnimator.State.HOOK,
                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02, PlayerAnimator.State.KNOCKED_DOWN));
        bandit.baseHp = 80; bandit.baseDamage = 15; bandit.speed = 4; bandit.aggroRange = 200;

        MobDef boss = new MobDef("Warlord", MobCategory.BOSS, Color.web("#f0a030"));
        boss.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS, PlayerAnimator.State.HOOK,
                PlayerAnimator.State.UPPERCUT, PlayerAnimator.State.HAYMAKER,
                PlayerAnimator.State.HEAD_KICK, PlayerAnimator.State.BODY_KICK,
                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02, PlayerAnimator.State.GOTHIT03,
                PlayerAnimator.State.KNOCKED_DOWN, PlayerAnimator.State.KIP_UP));
        boss.baseHp = 500; boss.baseDamage = 30; boss.speed = 4; boss.aggroRange = 400;
        mobs.addAll(List.of(wolf, skeleton, bandit, boss));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }

    private void setStatus(Label lbl, String msg, boolean ok) {
        if (lbl == null) return;
        lbl.setText(msg);
        lbl.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "#50c050" : "#e94560") + ";");
    }

    private Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> s = new Spinner<>(min, max, initial);
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        s.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;");
        return s;
    }

    private Label lbl(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: " + size + ";" +
                   (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private Button btn(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white;" +
                   "-fx-background-radius: 4; -fx-font-size: 11; -fx-padding: 5 10 5 10;");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private VBox vbox(int spacing, Node... children) {
        VBox v = new VBox(spacing, children);
        v.setPadding(new Insets(8));
        v.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return v;
    }
}
