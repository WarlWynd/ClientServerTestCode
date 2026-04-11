package com.game.client.ui;

import com.game.client.AppSettings;
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
    private static final Path PORTAL_FILE =
            Paths.get("client/src/main/resources/graphics/sprites/portal-spawns.json");
    private static final File BOARDS_DIR =
            new File(System.getProperty("user.home"), ".game/boards");

    // ── Mob role (gameplay archetype) ─────────────────────────────────────────
    public enum MobRole { BEAST, UNDEAD, HUMANOID, BOSS }

    // ── Mob archetype ─────────────────────────────────────────────────────────
    public static class MobDef {
        String      name;
        MobRole     role;
        MobCategory bodyType = MobCategory.HUMANOID;
        Color       tint;
        int         baseHp;
        int         baseDamage;
        int         armor;      // 0–100: % chance to block + damage reduction
        int         speed;
        int         aggroRange;
        String      lootTable = "";
        double      scale     = 1.0;
        final List<PlayerAnimator.State> states = new ArrayList<>();

        MobDef(String name, MobRole role, Color tint) {
            this.name = name; this.role = role; this.tint = tint;
            this.baseHp = 100; this.baseDamage = 10; this.armor = 0; this.speed = 3; this.aggroRange = 200;
        }
    }

    // ── Portal point (per board) ──────────────────────────────────────────────
    public static class PortalPoint {
        int    col, row;
        String destBoard;
        int    destCol, destRow;

        PortalPoint(int col, int row, String destBoard, int destCol, int destRow) {
            this.col = col; this.row = row;
            this.destBoard = destBoard; this.destCol = destCol; this.destRow = destRow;
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
    private final Map<String, List<SpawnPoint>>  spawns  = new LinkedHashMap<>(); // boardName → spawns

    // Map portals
    private final Map<String, List<PortalPoint>> portals = new LinkedHashMap<>(); // boardName → portals
    private String              currentBoard = null;
    private BoardTile[][]       boardGrid    = null;
    private int                 boardRows    = 0, boardCols = 0;
    private SpawnPoint          hoveredSpawn = null;

    // Spawn placement state
    private String              placingMob   = null; // mob name currently being placed

    // UI refs — Mob Roster
    private ListView<String>          mobList;
    private TextField                 nameField;
    private ComboBox<String>          categoryCombo;
    private ComboBox<MobCategory>     bodyTypeCombo;
    private ColorPicker               colorPicker;
    private Spinner<Integer>    hpSpinner, dmgSpinner, armorSpinner, speedSpinner, aggroSpinner;
    private Spinner<Double>     scaleSpinner;
    private List<CheckBox>      stateChecks;
    private ComboBox<String>    lootTableCombo;
    private Label               rosterStatus;

    // Preview
    private Canvas              previewCanvas;
    private PlayerAnimator      previewAnimator;
    private AnimationTimer      previewTimer;

    // UI refs — Map Spawns
    private ComboBox<String>      boardCombo;
    private TableView<SpawnPoint> spawnList;
    private Canvas                mapCanvas;
    private Label                 spawnStatus;
    private Label                 zoomValLbl;
    private ComboBox<String>      placeMobCombo;
    private Spinner<Integer>      respawnSpinner, maxCountSpinner;

    // UI refs — Portals
    private TableView<PortalPoint> portalList;
    private Label                  portalStatus;

    // UI refs — Practice Fight
    private Canvas              practiceCanvas;
    private AnimationTimer      practiceTimer;
    private ComboBox<String>    practiceLeftCombo, practiceRightCombo;
    private boolean             practiceFaceEachOther = true;
    private PlayerAnimator      practiceLeftAnim  = new PlayerAnimator();
    private PlayerAnimator      practiceRightAnim = new PlayerAnimator();
    private String              practiceResolvedLeft, practiceResolvedRight;
    private boolean             practicePlaying      = false;
    private boolean             practiceLeftAI       = true;
    private boolean             practiceRightAI      = true;
    private double              practiceSpeedMult    = 1.0;
    private float               practiceLeftHp, practiceRightHp;
    private long                practiceLeftHitMs, practiceRightHitMs;
    // [0]=cx, [1]=y, [2]=dmg, [3]=startMs, [4]=isHeal(0/1), [5]=label
    private final List<Object[]> practiceDmgNums     = new ArrayList<>();
    private long                practiceCombatNextMs   = 0;
    private long                practiceLeftStunEndMs  = 0;
    private long                practiceRightStunEndMs = 0;
    private long                practiceLeftKoEndMs    = 0;
    private long                practiceRightKoEndMs   = 0;
    private long                practiceLeftKipEndMs   = 0;  // KIP_UP grace window end
    private long                practiceRightKipEndMs  = 0;
    private int                 practiceCombatTurn   = 0; // 0=left attacks, 1=right attacks
    private Label               practiceActionLabel;
    // Mechanics settings (loaded from game-mechanics.json, editable in panel)
    private Spinner<Integer>    pmIntervalSpinner, pmStunSpinner, pmKoSpinner;
    private List<GameMechanicsPanel.AttackRule> practiceMechanicsRules = new ArrayList<>();

    private static final int    PRACTICE_W     = 420;
    private static final int    PRACTICE_H     = 310;
    private static final double PRACTICE_SCALE = 1.5;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        loadMobs();
        loadSpawns();
        loadPortals();
        ensureDefaults();

        Tab rosterTab = new Tab("🐾 Mob Roster", buildRosterTab());
        rosterTab.setClosable(false);

        TabPane inner = new TabPane(rosterTab);
        inner.getStyleClass().add("tab-pane-dark");
        return inner;
    }

    /** Returns just the Map Spawns content — used by BoardDevScreen. */
    public Node buildSpawnView() {
        loadMobs();
        loadSpawns();
        loadPortals();
        ensureDefaults();
        return buildSpawnTab();
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
        addBeastBtn.setOnAction(e  -> addNew(MobRole.BEAST));
        addUndeadBtn.setOnAction(e -> addNew(MobRole.UNDEAD));
        addHumanBtn.setOnAction(e  -> addNew(MobRole.HUMANOID));
        addBossBtn.setOnAction(e   -> addNew(MobRole.BOSS));
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
                selected.role = MobRole.valueOf(categoryCombo.getValue());
        });

        bodyTypeCombo = new ComboBox<>();
        bodyTypeCombo.getItems().addAll(MobCategory.values());
        bodyTypeCombo.getStyleClass().add("combo-dark");
        bodyTypeCombo.setMaxWidth(Double.MAX_VALUE);
        bodyTypeCombo.setOnAction(e -> {
            if (selected == null || bodyTypeCombo.getValue() == null) return;
            selected.bodyType = bodyTypeCombo.getValue();
            refreshStateChecks(bodyTypeCombo.getValue());
        });

        colorPicker = new ColorPicker(Color.web("#e94560"));
        colorPicker.setStyle("-fx-color-label-visible: false;");
        colorPicker.setOnAction(e -> { if (selected != null) selected.tint = colorPicker.getValue(); });

        hpSpinner    = intSpinner(1, 9999, 100);
        dmgSpinner   = intSpinner(1, 999,  10);
        armorSpinner = intSpinner(0, 100,   0);
        speedSpinner = intSpinner(1, 50,   3);
        aggroSpinner = intSpinner(10, 2000, 200);
        scaleSpinner = new Spinner<>(new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 10.0, 1.0, 0.1));
        scaleSpinner.setEditable(true);
        scaleSpinner.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;");
        for (Spinner<?> sp : new Spinner<?>[]{ hpSpinner, dmgSpinner, armorSpinner, speedSpinner, aggroSpinner, scaleSpinner }) {
            sp.setPrefWidth(80); sp.setMaxWidth(80);
        }
        hpSpinner.valueProperty().addListener((obs, o, n)    -> { if (selected != null) selected.baseHp     = n; });
        dmgSpinner.valueProperty().addListener((obs, o, n)   -> { if (selected != null) selected.baseDamage = n; });
        armorSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.armor      = n; });
        speedSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.speed      = n; });
        aggroSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.aggroRange = n; });
        scaleSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.scale      = n; });

        // Compact 6-column grid: label+spinner pairs side-by-side
        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(6); statsGrid.setVgap(5);
        statsGrid.add(lbl("HP:",     10, false), 0, 0); statsGrid.add(hpSpinner,    1, 0);
        statsGrid.add(lbl("Dmg:",    10, false), 2, 0); statsGrid.add(dmgSpinner,   3, 0);
        statsGrid.add(lbl("Armor %:",10, false), 0, 1); statsGrid.add(armorSpinner, 1, 1);
        statsGrid.add(lbl("Speed:",  10, false), 2, 1); statsGrid.add(speedSpinner, 3, 1);
        statsGrid.add(lbl("Aggro:",  10, false), 0, 2); statsGrid.add(aggroSpinner, 1, 2);
        statsGrid.add(lbl("Scale:",  10, false), 2, 2); statsGrid.add(scaleSpinner, 3, 2);
        for (int c : new int[]{0, 2}) {
            ColumnConstraints lc = new ColumnConstraints(); lc.setPrefWidth(42); statsGrid.getColumnConstraints().add(lc);
            ColumnConstraints sc = new ColumnConstraints(); sc.setPrefWidth(80);  statsGrid.getColumnConstraints().add(sc);
        }

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
        }
        // Populate pane with humanoid states by default
        for (CheckBox cb : stateChecks) {
            try {
                PlayerAnimator.State s = PlayerAnimator.State.valueOf(cb.getText());
                if (MobCategory.HUMANOID.contains(s)) statesPane.getChildren().add(cb);
            } catch (IllegalArgumentException ignored) {}
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

        HBox.setHgrow(categoryCombo, Priority.ALWAYS);
        HBox.setHgrow(bodyTypeCombo, Priority.ALWAYS);
        VBox roleBox     = new VBox(3, lbl("Role:",      10, false), categoryCombo);
        VBox bodyTypeBox = new VBox(3, lbl("Body Type:", 10, false), bodyTypeCombo);
        HBox roleRow     = new HBox(8, roleBox, bodyTypeBox);
        HBox.setHgrow(roleBox,     Priority.ALWAYS);
        HBox.setHgrow(bodyTypeBox, Priority.ALWAYS);

        VBox cfgCol = vbox(8,
                lbl("Configuration", 13, true),
                new VBox(4, lbl("Name:", 11, false), nameField),
                roleRow,
                new HBox(10, lbl("Tint:", 11, false), colorPicker),
                new VBox(4, lbl("Loot Table:", 11, false), lootTableCombo),
                lbl("Base Stats:", 11, true), statsGrid,
                statesLbl, new HBox(6, allBtn, noneBtn), statesScroll,
                saveBtn, rosterStatus);
        cfgCol.setPrefWidth(340);

        // ── Preview ───────────────────────────────────────────────────────────
        previewCanvas = new Canvas(180, 300);
        previewCanvas.setStyle("-fx-background-color: #0a0a18;");
        previewAnimator = buildPreviewAnimator(MobCategory.HUMANOID);

        if (previewTimer != null) previewTimer.stop();
        previewTimer = new AnimationTimer() {
            @Override public void handle(long now) { drawPreview(); }
        };
        previewTimer.start();

        VBox previewCol = new VBox(6,
                lbl("Preview", 12, true),
                previewCanvas);
        previewCol.setPadding(new Insets(8));
        previewCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        previewCol.setAlignment(Pos.TOP_CENTER);

        Node practiceNode = buildPracticeFight();

        HBox root = new HBox(8, leftCol, cfgCol, previewCol, practiceNode);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");
        if (!mobs.isEmpty()) mobList.getSelectionModel().select(0);
        return root;
    }

    private PlayerAnimator buildPreviewAnimator(MobCategory bodyType) {
        PlayerAnimator.State idleState = (bodyType == MobCategory.QUADRUPED)
                ? PlayerAnimator.State.QUAD_IDLE : PlayerAnimator.State.IDLE;
        PlayerAnimator pa = new PlayerAnimator();
        pa.forceState(idleState);
        return pa;
    }

    private void drawPreview() {
        GraphicsContext gc = previewCanvas.getGraphicsContext2D();
        double w = previewCanvas.getWidth();
        double h = previewCanvas.getHeight();
        gc.setFill(javafx.scene.paint.Color.web("#0a0a18"));
        gc.fillRect(0, 0, w, h);

        if (selected == null) return;

        Color tint  = selected.tint  != null ? selected.tint : Color.web("#e0e0e0");
        double scale = selected.scale;

        // Re-sync animator body type if needed
        PlayerAnimator.State needed = (selected.bodyType == MobCategory.QUADRUPED)
                ? PlayerAnimator.State.QUAD_IDLE : PlayerAnimator.State.IDLE;
        if (previewAnimator.getState() != needed) {
            previewAnimator = buildPreviewAnimator(selected.bodyType);
        }

        previewAnimator.draw(gc, w / 2, h * 0.65, tint, scale);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MAP SPAWNS TAB
    // ══════════════════════════════════════════════════════════════════════════

    private int tilePx = 8; // pixels per tile — changed by zoom controls

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

        // Zoom controls
        Label zoomLbl = new Label("Zoom:");
        zoomLbl.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");
        Button zoomOutBtn = new Button("−");
        Button zoomInBtn  = new Button("+");
        zoomValLbl = new Label(tilePx + "px");
        zoomValLbl.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 11; -fx-min-width: 30;");
        String zBtnStyle = "-fx-background-color: #1e3a5f; -fx-text-fill: white; -fx-font-size: 13;" +
                           "-fx-background-radius: 4; -fx-padding: 1 8 1 8;";
        zoomOutBtn.setStyle(zBtnStyle);
        zoomInBtn.setStyle(zBtnStyle);
        zoomOutBtn.setOnAction(e -> {
            if (tilePx > 2) { tilePx = Math.max(2, tilePx - 2); zoomValLbl.setText(tilePx + "px"); resizeMapCanvas(); drawMapCanvas(); }
        });
        zoomInBtn.setOnAction(e -> {
            if (tilePx < 48) { tilePx = Math.min(48, tilePx + 2); zoomValLbl.setText(tilePx + "px"); resizeMapCanvas(); drawMapCanvas(); }
        });
        HBox zoomRow = new HBox(4, zoomLbl, zoomOutBtn, zoomValLbl, zoomInBtn);
        zoomRow.setAlignment(Pos.CENTER_LEFT);

        HBox placeRow = new HBox(8, placeLbl, placeMobCombo,
                respawnLbl, respawnSpinner, maxCntLbl, maxCountSpinner);
        placeRow.setAlignment(Pos.CENTER_LEFT);

        HBox placeZoomRow = new HBox(16, placeRow, new javafx.scene.layout.Region(), zoomRow);
        HBox.setHgrow(placeRow, Priority.ALWAYS);
        placeZoomRow.setAlignment(Pos.CENTER_LEFT);

        VBox topBar = new VBox(6, boardRow, placeZoomRow, clickHint);
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
        mapScroll.setPrefHeight(340);
        mapScroll.setMaxHeight(340);

        drawMapCanvas(); // draw empty state

        mapCanvas.setOnMouseClicked(e -> {
            if (boardGrid == null) return;
            int col = (int)(e.getX() / tilePx);
            int row = (int)(e.getY() / tilePx);
            if (col < 0 || col >= boardCols || row < 0 || row >= boardRows) return;

            if (e.getButton() == MouseButton.PRIMARY) {
                placeSpawn(col, row);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                removeSpawn(col, row);
            }
        });

        mapCanvas.setOnMouseMoved(e -> {
            if (boardGrid == null) return;
            int col = (int)(e.getX() / tilePx);
            int row = (int)(e.getY() / tilePx);
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

        mapCanvas.setOnScroll(e -> {
            if (!e.isControlDown()) return;
            e.consume(); // prevent ScrollPane from scrolling
            int delta = e.getDeltaY() > 0 ? 2 : -2;
            int next  = Math.max(2, Math.min(48, tilePx + delta));
            if (next == tilePx) return;
            tilePx = next;
            zoomValLbl.setText(tilePx + "px");
            resizeMapCanvas();
            drawMapCanvas();
        });

        // ── Spawn table (grid) ────────────────────────────────────────────────
        spawnList = new TableView<>();
        spawnList.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                           "-fx-control-inner-background: #0f0f1e; -fx-table-header-border-color: #3a3a6a;");
        spawnList.setPrefHeight(150);
        spawnList.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<SpawnPoint, String> colMob  = new TableColumn<>("Mob");
        TableColumn<SpawnPoint, Number> colCol  = new TableColumn<>("Col");
        TableColumn<SpawnPoint, Number> colRow  = new TableColumn<>("Row");
        TableColumn<SpawnPoint, Number> colResp = new TableColumn<>("Resp(s)");
        TableColumn<SpawnPoint, Number> colMax  = new TableColumn<>("Max");

        colMob.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().mobName));
        colCol.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().col));
        colRow.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().row));
        colResp.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().respawnSec));
        colMax.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().maxCount));

        String colStyle = "-fx-text-fill: #c8c8e0; -fx-font-size: 10; -fx-alignment: CENTER;";
        for (TableColumn<?,?> c : List.of(colMob, colCol, colRow, colResp, colMax)) c.setStyle(colStyle);
        colMob.setPrefWidth(80); colCol.setPrefWidth(36); colRow.setPrefWidth(36);
        colResp.setPrefWidth(46); colMax.setPrefWidth(36);

        spawnList.getColumns().addAll(colMob, colCol, colRow, colResp, colMax);

        Button delRowBtn     = btn("🗑 Delete Selected", "#7b241c");
        Button clearBoardBtn = btn("Clear All",           "#5a1a1a");
        Button saveSpawnBtn  = btn("💾 Save Spawns",       "#1e5f3a");
        delRowBtn.setOnAction(e -> {
            SpawnPoint sel = spawnList.getSelectionModel().getSelectedItem();
            if (sel != null && currentBoard != null) {
                List<SpawnPoint> pts = spawns.get(currentBoard);
                if (pts != null) { pts.remove(sel); refreshSpawnList(); drawMapCanvas(); }
            }
        });
        clearBoardBtn.setOnAction(e -> {
            if (currentBoard != null) { spawns.remove(currentBoard); refreshSpawnList(); drawMapCanvas(); }
        });
        saveSpawnBtn.setOnAction(e -> saveSpawns());

        spawnStatus = new Label();
        spawnStatus.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 10;");

        VBox bottomBar = new VBox(5,
                lbl("Spawns on this board:", 11, true),
                spawnList,
                new HBox(4, delRowBtn, clearBoardBtn, saveSpawnBtn),
                spawnStatus);
        bottomBar.setPadding(new Insets(8));
        bottomBar.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");
        bottomBar.setMaxWidth(340);

        HBox bottomRow = new HBox(8, bottomBar, buildPortalPanel());

        VBox root = new VBox(8, topBar, mapScroll, bottomRow);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");

        refreshBoardCombo();
        return root;
    }

    // ── Map spawn logic ───────────────────────────────────────────────────────

    private void resizeMapCanvas() {
        if (boardGrid == null) return;
        mapCanvas.setWidth(boardCols * tilePx);
        mapCanvas.setHeight(boardRows * tilePx);
    }

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
            mapCanvas.setWidth(boardCols * tilePx);
            mapCanvas.setHeight(boardRows * tilePx);
        } catch (Exception ignored) {}

        refreshSpawnList();
        refreshPortalList();
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
        spawnList.getItems().addAll(spawns.getOrDefault(currentBoard, List.of()));
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
                gc.fillRect(c * tilePx, r * tilePx, tilePx, tilePx);
                gc.setStroke(t.border);
                gc.setLineWidth(0.5);
                gc.strokeRect(c * tilePx, r * tilePx, tilePx, tilePx);
            }
        }

        // Draw spawn markers
        List<SpawnPoint> pts = spawns.getOrDefault(currentBoard, List.of());
        for (SpawnPoint sp : pts) {
            Color tint = mobTint(sp.mobName);
            boolean hov = sp == hoveredSpawn;
            double cx = sp.col * tilePx + tilePx / 2.0;
            double cy = sp.row * tilePx + tilePx / 2.0;
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
            double tx = hoveredSpawn.col * tilePx + tilePx;
            double ty = hoveredSpawn.row * tilePx - 4;
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

    private void addNew(MobRole role) {
        Color tint = switch (role) {
            case BEAST    -> Color.web("#50c050");
            case UNDEAD   -> Color.web("#aa66ff");
            case HUMANOID -> Color.web("#53c0f0");
            case BOSS     -> Color.web("#f0a030");
        };
        MobDef mob = new MobDef("New " + role.name().charAt(0) + role.name().substring(1).toLowerCase(), role, tint);
        mob.states.add(PlayerAnimator.State.IDLE);
        mob.states.add(PlayerAnimator.State.RUN);
        mobs.add(mob);
        refreshMobList();
        mobList.getSelectionModel().select(mobs.size() - 1);
        syncPlaceMobCombo();
        syncPracticeCombos();
    }

    private void deleteSelected() {
        if (selected == null) return;
        mobs.remove(selected);
        selected = null;
        clearForm();
        refreshMobList();
        if (!mobs.isEmpty()) mobList.getSelectionModel().select(0);
        syncPlaceMobCombo();
        syncPracticeCombos();
    }

    private void loadIntoForm(MobDef mob) {
        selected = mob;
        nameField.setText(mob.name);
        colorPicker.setValue(mob.tint);
        categoryCombo.setValue(mob.role.name());
        bodyTypeCombo.setValue(mob.bodyType);
        refreshStateChecks(mob.bodyType);
        hpSpinner.getValueFactory().setValue(mob.baseHp);
        scaleSpinner.getValueFactory().setValue(mob.scale);
        dmgSpinner.getValueFactory().setValue(mob.baseDamage);
        armorSpinner.getValueFactory().setValue(mob.armor);
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
        MobCategory bodyType = selected.bodyType != null ? selected.bodyType : MobCategory.HUMANOID;
        selected.states.clear();
        for (CheckBox cb : stateChecks) {
            if (!cb.isSelected()) continue;
            try {
                PlayerAnimator.State s = PlayerAnimator.State.valueOf(cb.getText());
                if (bodyType.contains(s)) selected.states.add(s);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void refreshMobList() {
        String selName = selected != null ? selected.name : null;
        mobList.getItems().clear();
        for (MobDef mob : mobs) {
            String icon = switch (mob.role) {
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
                node.put("role",       mob.role.name());
                node.put("bodyType",   mob.bodyType.name());
                node.put("scale",      mob.scale);
                node.put("tint",       toHex(mob.tint));
                node.put("baseHp",     mob.baseHp);
                node.put("baseDamage", mob.baseDamage);
                node.put("armor",      mob.armor);
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
                MobRole     role = MobRole.valueOf(node.path("role").asText(
                        node.path("category").asText("BEAST")));  // "category" fallback for old files
                Color       tint = Color.web(node.path("tint").asText("#e94560"));
                MobDef mob = new MobDef(node.path("name").asText("Mob"), role, tint);
                try { mob.bodyType = MobCategory.valueOf(node.path("bodyType").asText("HUMANOID")); }
                catch (IllegalArgumentException ignored) {}
                mob.scale = node.path("scale").asDouble(1.0);
                mob.baseHp     = node.path("baseHp").asInt(100);
                mob.baseDamage = node.path("baseDamage").asInt(10);
                mob.armor      = node.path("armor").asInt(0);
                mob.speed      = node.path("speed").asInt(3);
                mob.aggroRange = node.path("aggroRange").asInt(200);
                mob.lootTable  = node.path("lootTable").asText("");
                for (JsonNode s : node.path("states")) {
                    try {
                        PlayerAnimator.State st = PlayerAnimator.State.valueOf(s.asText());
                        if (mob.bodyType.contains(st)) mob.states.add(st);
                    } catch (IllegalArgumentException ignored) {}
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

    // ── Portal panel ──────────────────────────────────────────────────────────

    private Node buildPortalPanel() {
        // ── Form row — new portal entry ──────────────────────────────────────
        Spinner<Integer> pColSpin = new Spinner<>(0, 999, 0);
        Spinner<Integer> pRowSpin = new Spinner<>(0, 999, 0);
        pColSpin.setPrefWidth(60); pRowSpin.setPrefWidth(60);
        pColSpin.setEditable(true); pRowSpin.setEditable(true);
        stylePortalSpinner(pColSpin); stylePortalSpinner(pRowSpin);

        TextField destBoardField = new TextField();
        destBoardField.setPromptText("dest board (.csv)");
        destBoardField.setPrefWidth(130);
        destBoardField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 3;");

        Spinner<Integer> dColSpin = new Spinner<>(0, 999, 0);
        Spinner<Integer> dRowSpin = new Spinner<>(0, 999, 0);
        dColSpin.setPrefWidth(60); dRowSpin.setPrefWidth(60);
        dColSpin.setEditable(true); dRowSpin.setEditable(true);
        stylePortalSpinner(dColSpin); stylePortalSpinner(dRowSpin);

        Button addPortalBtn = btn("+ Add Portal", "#1a3a4a");
        addPortalBtn.setMaxWidth(Double.MAX_VALUE);
        addPortalBtn.setOnAction(e -> {
            if (currentBoard == null) return;
            String dest = destBoardField.getText().trim();
            if (dest.isEmpty()) return;
            portals.computeIfAbsent(currentBoard, k -> new ArrayList<>())
                   .add(new PortalPoint(pColSpin.getValue(), pRowSpin.getValue(),
                                        dest, dColSpin.getValue(), dRowSpin.getValue()));
            refreshPortalList();
        });

        Label fromLbl = new Label("From:");
        fromLbl.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 10;");
        Label toLbl = new Label("To:");
        toLbl.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 10;");
        Label colLbl1 = new Label("Col");
        colLbl1.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 10;");
        Label rowLbl1 = new Label("Row");
        rowLbl1.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 10;");
        Label colLbl2 = new Label("Col");
        colLbl2.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 10;");
        Label rowLbl2 = new Label("Row");
        rowLbl2.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 10;");

        HBox fromRow = new HBox(4, fromLbl,
                new VBox(1, colLbl1, pColSpin),
                new VBox(1, rowLbl1, pRowSpin));
        fromRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox toRow = new HBox(4, toLbl, destBoardField,
                new VBox(1, colLbl2, dColSpin),
                new VBox(1, rowLbl2, dRowSpin));
        toRow.setAlignment(Pos.BOTTOM_LEFT);

        // ── Portal table ─────────────────────────────────────────────────────
        portalList = new TableView<>();
        portalList.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                "-fx-control-inner-background: #0f0f1e; -fx-table-header-border-color: #3a3a6a;");
        portalList.setPrefHeight(150);
        portalList.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<PortalPoint, Number> pCol  = new TableColumn<>("Col");
        TableColumn<PortalPoint, Number> pRow  = new TableColumn<>("Row");
        TableColumn<PortalPoint, String> pDest = new TableColumn<>("Dest Board");
        TableColumn<PortalPoint, Number> pDCol = new TableColumn<>("dCol");
        TableColumn<PortalPoint, Number> pDRow = new TableColumn<>("dRow");

        pCol.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().col));
        pRow.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().row));
        pDest.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().destBoard));
        pDCol.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().destCol));
        pDRow.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().destRow));

        String cs = "-fx-text-fill: #c8c8e0; -fx-font-size: 10; -fx-alignment: CENTER;";
        for (TableColumn<?,?> c : List.of(pCol, pRow, pDest, pDCol, pDRow)) c.setStyle(cs);
        pCol.setPrefWidth(36); pRow.setPrefWidth(36); pDest.setPrefWidth(110);
        pDCol.setPrefWidth(36); pDRow.setPrefWidth(36);
        portalList.getColumns().addAll(pCol, pRow, pDest, pDCol, pDRow);

        // ── Buttons ───────────────────────────────────────────────────────────
        Button delPortalBtn   = btn("🗑 Delete Selected", "#7b241c");
        Button clearPortalBtn = btn("Clear All",          "#5a1a1a");
        Button savePortalBtn  = btn("💾 Save Portals",     "#1e5f3a");

        delPortalBtn.setOnAction(e -> {
            PortalPoint sel = portalList.getSelectionModel().getSelectedItem();
            if (sel != null && currentBoard != null) {
                List<PortalPoint> pts = portals.get(currentBoard);
                if (pts != null) { pts.remove(sel); refreshPortalList(); }
            }
        });
        clearPortalBtn.setOnAction(e -> {
            if (currentBoard != null) { portals.remove(currentBoard); refreshPortalList(); }
        });
        savePortalBtn.setOnAction(e -> savePortals());

        portalStatus = new Label();
        portalStatus.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 10;");

        VBox portalBox = new VBox(5,
                lbl("Portals on this board:", 11, true),
                fromRow, toRow, addPortalBtn,
                portalList,
                new HBox(4, delPortalBtn, clearPortalBtn, savePortalBtn),
                portalStatus);
        portalBox.setPadding(new Insets(8));
        portalBox.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");
        portalBox.setMaxWidth(340);
        return portalBox;
    }

    private void refreshPortalList() {
        portalList.getItems().clear();
        if (currentBoard == null) return;
        List<PortalPoint> pts = portals.get(currentBoard);
        if (pts != null) portalList.getItems().addAll(pts);
    }

    private void savePortals() {
        try {
            ObjectMapper om  = new ObjectMapper();
            ObjectNode root  = om.createObjectNode();
            for (Map.Entry<String, List<PortalPoint>> entry : portals.entrySet()) {
                ArrayNode arr = om.createArrayNode();
                for (PortalPoint pp : entry.getValue()) {
                    ObjectNode node = om.createObjectNode();
                    node.put("col",       pp.col);
                    node.put("row",       pp.row);
                    node.put("destBoard", pp.destBoard);
                    node.put("destCol",   pp.destCol);
                    node.put("destRow",   pp.destRow);
                    arr.add(node);
                }
                root.set(entry.getKey(), arr);
            }
            Files.createDirectories(PORTAL_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(PORTAL_FILE.toFile(), root);
            int total = portals.values().stream().mapToInt(List::size).sum();
            setStatus(portalStatus, "✓ Saved " + total + " portal(s) across "
                    + portals.size() + " board(s).", true);
        } catch (Exception e) {
            setStatus(portalStatus, "✗ Save failed: " + e.getMessage(), false);
        }
    }

    private void loadPortals() {
        portals.clear();
        if (!Files.exists(PORTAL_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(PORTAL_FILE.toFile());
            root.fields().forEachRemaining(entry -> {
                List<PortalPoint> pts = new ArrayList<>();
                for (JsonNode node : entry.getValue()) {
                    pts.add(new PortalPoint(
                            node.path("col").asInt(0),
                            node.path("row").asInt(0),
                            node.path("destBoard").asText(""),
                            node.path("destCol").asInt(0),
                            node.path("destRow").asInt(0)));
                }
                portals.put(entry.getKey(), pts);
            });
        } catch (Exception ignored) {}
    }

    private void stylePortalSpinner(Spinner<Integer> s) {
        s.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: #c8c8e8;");
    }

    private void ensureDefaults() {
        if (!mobs.isEmpty()) return;
        MobDef wolf = new MobDef("Wolf", MobRole.BEAST, Color.web("#888888"));
        wolf.bodyType = MobCategory.QUADRUPED;
        wolf.states.addAll(List.of(PlayerAnimator.State.QUAD_IDLE, PlayerAnimator.State.TROT,
                PlayerAnimator.State.GALLOP, PlayerAnimator.State.POUNCE,
                PlayerAnimator.State.BITE, PlayerAnimator.State.QUAD_DEATH));
        wolf.baseHp = 60; wolf.baseDamage = 12; wolf.speed = 5; wolf.aggroRange = 300;

        MobDef skeleton = new MobDef("Skeleton", MobRole.UNDEAD, Color.web("#ccccaa"));
        skeleton.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS,
                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.KNOCKED_DOWN));
        skeleton.baseHp = 50; skeleton.baseDamage = 8; skeleton.speed = 3; skeleton.aggroRange = 250;

        MobDef bandit = new MobDef("Bandit", MobRole.HUMANOID, Color.web("#c08030"));
        bandit.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS, PlayerAnimator.State.HOOK,
                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02, PlayerAnimator.State.KNOCKED_DOWN));
        bandit.baseHp = 80; bandit.baseDamage = 15; bandit.speed = 4; bandit.aggroRange = 200;

        MobDef boss = new MobDef("Warlord", MobRole.BOSS, Color.web("#f0a030"));
        boss.states.addAll(List.of(PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS, PlayerAnimator.State.HOOK,
                PlayerAnimator.State.UPPERCUT, PlayerAnimator.State.HAYMAKER,
                PlayerAnimator.State.HEAD_KICK, PlayerAnimator.State.BODY_KICK,
                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02, PlayerAnimator.State.GOTHIT03,
                PlayerAnimator.State.KNOCKED_DOWN, PlayerAnimator.State.KIP_UP));
        boss.baseHp = 500; boss.baseDamage = 30; boss.speed = 4; boss.aggroRange = 400;
        mobs.addAll(List.of(wolf, skeleton, bandit, boss));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRACTICE FIGHT TAB
    // ══════════════════════════════════════════════════════════════════════════

    public Node buildPracticeFight() {
        // ── Load mechanics from game-mechanics.json ───────────────────────────
        loadPracticeMechanics();

        // ── Fighter selectors ─────────────────────────────────────────────────
        practiceLeftCombo  = new ComboBox<>(); practiceLeftCombo.getStyleClass().add("combo-dark");  practiceLeftCombo.setMaxWidth(Double.MAX_VALUE);
        practiceRightCombo = new ComboBox<>(); practiceRightCombo.getStyleClass().add("combo-dark"); practiceRightCombo.setMaxWidth(Double.MAX_VALUE);
        syncPracticeCombos();
        practiceLeftCombo.setOnAction(e  -> resetPractice());
        practiceRightCombo.setOnAction(e -> resetPractice());

        // ── Play / Pause / Reset ──────────────────────────────────────────────
        Button playPauseBtn = btn("▶ Play", "#1e5f2e");
        playPauseBtn.setMaxWidth(Double.MAX_VALUE);
        playPauseBtn.setOnAction(e -> {
            practicePlaying = !practicePlaying;
            playPauseBtn.setText(practicePlaying ? "⏸ Pause" : "▶ Play");
            playPauseBtn.setStyle("-fx-background-color:" + (practicePlaying ? "#5f1e1e" : "#1e5f2e") +
                    ";-fx-text-fill:white;-fx-background-radius:4;-fx-font-size:11;-fx-padding:5 10 5 10;");
            if (practicePlaying) practiceCombatNextMs = 0;
        });

        Button resetBtn = btn("↺ Reset", "#2e1a5f");
        resetBtn.setMaxWidth(Double.MAX_VALUE);
        resetBtn.setOnAction(e -> {
            practicePlaying = false;
            playPauseBtn.setText("▶ Play");
            playPauseBtn.setStyle("-fx-background-color:#1e5f2e;-fx-text-fill:white;" +
                    "-fx-background-radius:4;-fx-font-size:11;-fx-padding:5 10 5 10;");
            resetPractice();
        });

        CheckBox faceCheck = new CheckBox("Face Each Other");
        faceCheck.setSelected(true);
        faceCheck.setStyle("-fx-text-fill:#c8c8e0;-fx-font-size:11;");
        faceCheck.setOnAction(e -> practiceFaceEachOther = faceCheck.isSelected());

        // ── Speed slider ──────────────────────────────────────────────────────
        Slider speedSlider = new Slider(0.25, 4.0, 1.0);
        speedSlider.setShowTickMarks(true);
        speedSlider.setShowTickLabels(true);
        speedSlider.setMajorTickUnit(1.0);
        speedSlider.setMinorTickCount(3);
        speedSlider.setSnapToTicks(false);
        speedSlider.setMaxWidth(Double.MAX_VALUE);
        speedSlider.valueProperty().addListener((obs, o, v) -> practiceSpeedMult = v.doubleValue());
        Label speedLbl = lbl("Speed: 1.0×", 10, false);
        speedLbl.setStyle("-fx-text-fill:#a0a0c0;");
        speedSlider.valueProperty().addListener((obs, o, v) ->
                speedLbl.setText(String.format("Speed: %.2f×", v.doubleValue())));

        // ── Combat Settings (from game-mechanics.json) ────────────────────────
        int defInterval = practiceMechanicsRules.isEmpty() ? 1200 :
                loadMechanicsTiming("attackIntervalMs", 1200);
        int defStun     = loadMechanicsTiming("stunDurationMs", 800);
        int defKo       = loadMechanicsTiming("koRecoveryMs",   3000);

        pmIntervalSpinner = pmSpinner(100, 10000, defInterval);
        pmStunSpinner     = pmSpinner(0,   5000,  defStun);
        pmKoSpinner       = pmSpinner(500, 30000, defKo);

        // ── Action label ──────────────────────────────────────────────────────
        practiceActionLabel = new Label("Press ▶ Play to simulate a standard fight.");
        practiceActionLabel.setStyle("-fx-text-fill:#a0a0c0;-fx-font-size:10;");
        practiceActionLabel.setWrapText(true);

        // ── Side panel ────────────────────────────────────────────────────────
        Separator sep1 = new Separator(), sep2 = new Separator();

        CheckBox leftAiCheck = new CheckBox("Use Attack AI");
        leftAiCheck.setSelected(true);
        leftAiCheck.setStyle("-fx-text-fill:#c8c8e0;-fx-font-size:11;");
        leftAiCheck.setOnAction(e -> practiceLeftAI = leftAiCheck.isSelected());

        CheckBox rightAiCheck = new CheckBox("Use Attack AI");
        rightAiCheck.setSelected(true);
        rightAiCheck.setStyle("-fx-text-fill:#c8c8e0;-fx-font-size:11;");
        rightAiCheck.setOnAction(e -> practiceRightAI = rightAiCheck.isSelected());

        CheckBox showHpCheck      = combatCheck("Show Health Bar",  AppSettings.isCombatShowHealthBar());
        CheckBox showHitsCheck    = combatCheck("Show Hits",        AppSettings.isCombatShowHits());
        CheckBox showDmgCheck     = combatCheck("Show Damage",      AppSettings.isCombatShowDamage());
        CheckBox showStanceCheck  = combatCheck("Show Stance",      AppSettings.isCombatShowStance());
        CheckBox showVerboseCheck = combatCheck("Show Verbose Hits", AppSettings.isCombatShowVerboseHits());
        showHpCheck.setOnAction(e      -> AppSettings.setCombatShowHealthBar(showHpCheck.isSelected()));
        showHitsCheck.setOnAction(e    -> AppSettings.setCombatShowHits(showHitsCheck.isSelected()));
        showDmgCheck.setOnAction(e     -> AppSettings.setCombatShowDamage(showDmgCheck.isSelected()));
        showStanceCheck.setOnAction(e  -> AppSettings.setCombatShowStance(showStanceCheck.isSelected()));
        showVerboseCheck.setOnAction(e -> AppSettings.setCombatShowVerboseHits(showVerboseCheck.isSelected()));

        HBox mechRow1 = new HBox(12, showHpCheck, showHitsCheck);
        HBox mechRow2 = new HBox(12, showDmgCheck, showStanceCheck);
        mechRow1.setAlignment(Pos.CENTER_LEFT);
        mechRow2.setAlignment(Pos.CENTER_LEFT);

        VBox sidePanel = new VBox(6,
                lbl("Fighter 1 (left):", 10, true),  practiceLeftCombo,  leftAiCheck,
                lbl("Fighter 2 (right):", 10, true), practiceRightCombo, rightAiCheck,
                faceCheck,
                new HBox(4, playPauseBtn, resetBtn),
                sep1,
                speedLbl, speedSlider,
                sep2,
                lbl("Game Mechanics", 11, true),
                mechRow1, mechRow2, showVerboseCheck,
                new Separator(),
                lbl("Preview Settings", 11, true),
                settingRow("Attack Interval (ms):", pmIntervalSpinner),
                settingRow("Stun Duration (ms):",   pmStunSpinner),
                settingRow("KO Recovery (ms):",     pmKoSpinner),
                practiceActionLabel
        );
        sidePanel.setPadding(new Insets(8));
        sidePanel.setPrefWidth(220);
        sidePanel.setStyle("-fx-background-color:#16213e;-fx-background-radius:4;");

        // ── Canvas ────────────────────────────────────────────────────────────
        practiceCanvas = new Canvas(PRACTICE_W, PRACTICE_H);

        Label previewTitle = lbl("Combat Preview", 13, true);
        previewTitle.setStyle("-fx-text-fill:#e0e0e0;-fx-font-size:13;-fx-font-weight:bold;");

        VBox canvasStack = new VBox(4, previewTitle, practiceCanvas);
        canvasStack.setStyle("-fx-background-color:#0f0f1e;-fx-padding:6;");

        HBox arenaRow = new HBox(8, canvasStack, sidePanel);
        arenaRow.setAlignment(Pos.TOP_LEFT);

        VBox root = vbox(8, arenaRow);

        practiceTimer = new AnimationTimer() {
            @Override public void handle(long nowNs) { drawPractice(nowNs); }
        };
        practiceTimer.start();
        return root;
    }

    private static CheckBox combatCheck(String label, boolean initial) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected(initial);
        cb.setStyle("-fx-text-fill:#c8c8e0;-fx-font-size:11;");
        return cb;
    }

    private VBox settingRow(String label, Spinner<Integer> spinner) {
        Label l = lbl(label, 10, false);
        l.setStyle("-fx-text-fill:#a0a0c0;");
        spinner.setMaxWidth(Double.MAX_VALUE);
        return new VBox(2, l, spinner);
    }

    private Spinner<Integer> pmSpinner(int min, int max, int val) {
        Spinner<Integer> s = new Spinner<>(min, max, val);
        s.setEditable(true);
        s.setStyle("-fx-background-color:#0f0f1e;-fx-text-fill:#e0e0e0;");
        return s;
    }

    private void loadPracticeMechanics() {
        practiceMechanicsRules.clear();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(GameMechanicsPanel.SAVE_FILE.toFile());
            com.fasterxml.jackson.databind.JsonNode attacks = root.path("attacks");
            if (attacks.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : attacks) {
                    GameMechanicsPanel.AttackRule r = new GameMechanicsPanel.AttackRule(
                            n.path("state").asText("PUNCH"),
                            n.path("label").asText("Attack"),
                            n.path("minDamage").asInt(5),
                            n.path("maxDamage").asInt(12),
                            n.path("hitZone").asText("BODY"),
                            n.path("stunMs").asInt(0),
                            n.path("stunState").asText("GOTHIT01")
                    );
                    practiceMechanicsRules.add(r);
                }
            }
        } catch (Exception ignored) {}
    }

    private int loadMechanicsTiming(String key, int def) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(GameMechanicsPanel.SAVE_FILE.toFile());
            return root.path("timing").path(key).asInt(def);
        } catch (Exception e) { return def; }
    }

    private void syncPracticeCombos() {
        if (practiceLeftCombo == null || practiceRightCombo == null) return;
        List<String> names = mobs.stream().map(m -> m.name).collect(java.util.stream.Collectors.toList());
        String prevL = practiceLeftCombo.getValue(), prevR = practiceRightCombo.getValue();
        practiceLeftCombo.getItems().setAll(names);
        practiceRightCombo.getItems().setAll(names);
        if (names.contains(prevL)) practiceLeftCombo.setValue(prevL);
        else if (!names.isEmpty()) practiceLeftCombo.setValue(names.get(0));
        if (names.contains(prevR)) practiceRightCombo.setValue(prevR);
        else if (names.size() > 1) practiceRightCombo.setValue(names.get(1));
    }

    private void resetPractice() {
        practiceResolvedLeft   = null;
        practiceResolvedRight  = null;
        practiceLeftAnim       = new PlayerAnimator();
        practiceRightAnim      = new PlayerAnimator();
        practiceCombatNextMs   = 0;
        practiceLeftStunEndMs  = 0;
        practiceRightStunEndMs = 0;
        practiceLeftKoEndMs    = 0;
        practiceRightKoEndMs   = 0;
        practiceLeftKipEndMs   = 0;
        practiceRightKipEndMs  = 0;
        practiceLeftHitMs      = 0;
        practiceRightHitMs     = 0;
        practiceCombatTurn     = 0;
        practiceDmgNums.clear();
        MobDef lm = mobByName(practiceLeftCombo  != null ? practiceLeftCombo.getValue()  : null);
        MobDef rm = mobByName(practiceRightCombo != null ? practiceRightCombo.getValue() : null);
        practiceLeftHp  = lm != null ? lm.baseHp  : 100;
        practiceRightHp = rm != null ? rm.baseHp : 100;
        if (practiceActionLabel != null)
            practiceActionLabel.setText("Press ▶ Play to simulate a standard fight.");
    }

    private void drawPractice(long nowNs) {
        long nowMs = nowNs / 1_000_000L;
        GraphicsContext gc = practiceCanvas.getGraphicsContext2D();
        double w = practiceCanvas.getWidth(), h = practiceCanvas.getHeight();
        gc.setFill(Color.web("#0f0f1e")); gc.fillRect(0, 0, w, h);

        String lName = practiceLeftCombo  != null ? practiceLeftCombo.getValue()  : null;
        String rName = practiceRightCombo != null ? practiceRightCombo.getValue() : null;

        if (!java.util.Objects.equals(lName, practiceResolvedLeft)) {
            practiceResolvedLeft = lName;
            practiceLeftAnim = new PlayerAnimator();
            MobDef init = mobByName(lName);
            practiceLeftHp = init != null ? init.baseHp : 100;
            practiceLeftHitMs = 0;
        }
        if (!java.util.Objects.equals(rName, practiceResolvedRight)) {
            practiceResolvedRight = rName;
            practiceRightAnim = new PlayerAnimator();
            MobDef init = mobByName(rName);
            practiceRightHp = init != null ? init.baseHp : 100;
            practiceRightHitMs = 0;
        }

        MobDef lm = mobByName(lName), rm = mobByName(rName);

        if (lm == null && rm == null) {
            gc.setFill(Color.web("#606080")); gc.setFont(javafx.scene.text.Font.font("System", 11));
            gc.fillText("Select mobs to preview.", w / 2 - 80, h / 2);
            return;
        }

        // ── Combat simulation ─────────────────────────────────────────────────
        if (practicePlaying && lm != null && rm != null) {
            if (practiceCombatNextMs == 0) practiceCombatNextMs = nowMs;

            int koMs = pmKoSpinner != null ? pmKoSpinner.getValue() : 3000;

            // KO recovery — when KO timer expires, play KIP_UP then resume
            if (practiceLeftKoEndMs > 0 && nowMs >= practiceLeftKoEndMs) {
                practiceLeftKoEndMs = 0;
                practiceLeftHp = lm.baseHp;
                practiceLeftAnim.setHoldLastFrame(false);
                boolean leftIsQuad = lm.bodyType == MobCategory.QUADRUPED;
                int kipDuration = leftIsQuad ? 400 : 900;
                practiceLeftAnim.forceState(
                        leftIsQuad ? PlayerAnimator.State.QUAD_IDLE : PlayerAnimator.State.KIP_UP, nowMs);
                practiceLeftKipEndMs  = nowMs + kipDuration; // protect from interruption
                practiceCombatNextMs  = nowMs + kipDuration; // don't attack until kip finishes
                if (practiceActionLabel != null)
                    practiceActionLabel.setText(lm.name + " recovers — fight continues!");
            }
            if (practiceRightKoEndMs > 0 && nowMs >= practiceRightKoEndMs) {
                practiceRightKoEndMs = 0;
                practiceRightHp = rm.baseHp;
                practiceRightAnim.setHoldLastFrame(false);
                boolean rightIsQuad = rm.bodyType == MobCategory.QUADRUPED;
                int kipDuration = rightIsQuad ? 400 : 900;
                practiceRightAnim.forceState(
                        rightIsQuad ? PlayerAnimator.State.QUAD_IDLE : PlayerAnimator.State.KIP_UP, nowMs);
                practiceRightKipEndMs = nowMs + kipDuration;
                practiceCombatNextMs  = nowMs + kipDuration;
                if (practiceActionLabel != null)
                    practiceActionLabel.setText(rm.name + " recovers — fight continues!");
            }

            // Return stun victims to idle when stun expires (skip if KO'd or kipping up)
            if (practiceLeftStunEndMs > 0 && nowMs >= practiceLeftStunEndMs) {
                if (practiceLeftHp > 0 && practiceLeftKoEndMs == 0 && nowMs >= practiceLeftKipEndMs)
                    practiceLeftAnim.forceState(PlayerAnimator.State.IDLE, nowMs);
                practiceLeftStunEndMs = 0;
            }
            if (practiceRightStunEndMs > 0 && nowMs >= practiceRightStunEndMs) {
                if (practiceRightHp > 0 && practiceRightKoEndMs == 0 && nowMs >= practiceRightKipEndMs)
                    practiceRightAnim.forceState(PlayerAnimator.State.IDLE, nowMs);
                practiceRightStunEndMs = 0;
            }
            // Return attacker to idle when one-shot finishes (only if alive, not KO'd, not kipping)
            if (practiceLeftAnim.isOneShotDone()  && practiceLeftStunEndMs  == 0
                    && practiceLeftHp  > 0 && practiceLeftKoEndMs  == 0 && nowMs >= practiceLeftKipEndMs)
                practiceLeftAnim.forceState(PlayerAnimator.State.IDLE, nowMs);
            if (practiceRightAnim.isOneShotDone() && practiceRightStunEndMs == 0
                    && practiceRightHp > 0 && practiceRightKoEndMs == 0 && nowMs >= practiceRightKipEndMs)
                practiceRightAnim.forceState(PlayerAnimator.State.IDLE, nowMs);

            if (nowMs >= practiceCombatNextMs) {
                int interval = pmIntervalSpinner != null ? pmIntervalSpinner.getValue() : 1200;
                boolean thisAI = (practiceCombatTurn == 0) ? practiceLeftAI : practiceRightAI;

                if (thisAI) {
                    MobDef attacker    = (practiceCombatTurn == 0) ? lm   : rm;
                    MobDef defender    = (practiceCombatTurn == 0) ? rm   : lm;
                    PlayerAnimator atkAnim = (practiceCombatTurn == 0) ? practiceLeftAnim  : practiceRightAnim;
                    PlayerAnimator defAnim = (practiceCombatTurn == 0) ? practiceRightAnim : practiceLeftAnim;

                    // Find attack rules whose state is in the attacker's mob states.
                    // Fall back to any attack-looking state on the mob if no rules match.
                    List<GameMechanicsPanel.AttackRule> validRules = practiceMechanicsRules.stream()
                            .filter(r -> {
                                try { return attacker.states.contains(PlayerAnimator.State.valueOf(r.state.get())); }
                                catch (Exception e) { return false; }
                            })
                            .collect(java.util.stream.Collectors.toList());

                    // Fallback: build synthetic rules from mob states when no mechanics file loaded
                    if (validRules.isEmpty()) {
                        List<PlayerAnimator.State> passive = List.of(
                                PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                                PlayerAnimator.State.JUMP, PlayerAnimator.State.FALL,
                                PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02,
                                PlayerAnimator.State.GOTHIT03, PlayerAnimator.State.KNOCKED_DOWN,
                                PlayerAnimator.State.KIP_UP, PlayerAnimator.State.QUAD_IDLE,
                                PlayerAnimator.State.TROT, PlayerAnimator.State.GALLOP,
                                PlayerAnimator.State.QUAD_DEATH);
                        for (PlayerAnimator.State s : attacker.states) {
                            if (!passive.contains(s)) {
                                GameMechanicsPanel.AttackRule r = new GameMechanicsPanel.AttackRule(
                                        s.name(), s.name(), 5, 12, "BODY", 0, "GOTHIT01");
                                validRules.add(r);
                            }
                        }
                    }

                    if (!validRules.isEmpty()) {
                        GameMechanicsPanel.AttackRule rule = validRules.get((int)(Math.random() * validRules.size()));

                        try { atkAnim.forceState(PlayerAnimator.State.valueOf(rule.state.get()), nowMs); }
                        catch (Exception ignored) {}

                        String stunStateName = rule.stunState.get();
                        long defKipEnd = (practiceCombatTurn == 0) ? practiceRightKipEndMs : practiceLeftKipEndMs;
                        if (!"NONE".equals(stunStateName) && nowMs >= defKipEnd) {
                            PlayerAnimator.State stunState = PlayerAnimator.State.GOTHIT01;
                            try { stunState = PlayerAnimator.State.valueOf(stunStateName); }
                            catch (Exception ignored) {}
                            defAnim.forceState(stunState, nowMs);
                        }

                        int globalStun = pmStunSpinner != null ? pmStunSpinner.getValue() : 800;
                        int stunMs     = rule.stunMs.get() > 0 ? rule.stunMs.get() : globalStun;
                        long stunEnd   = nowMs + (long)(stunMs / practiceSpeedMult);
                        if (practiceCombatTurn == 0) { practiceRightStunEndMs = stunEnd; practiceRightHitMs = nowMs; }
                        else                         { practiceLeftStunEndMs  = stunEnd; practiceLeftHitMs  = nowMs; }

                        int dmg = rule.minDamage.get() + (int)(Math.random() *
                                Math.max(1, rule.maxDamage.get() - rule.minDamage.get() + 1));

                        // ── Block check ───────────────────────────────────────
                        boolean blocked = defender.armor > 0
                                && defender.states.contains(PlayerAnimator.State.BLOCK)
                                && (int)(Math.random() * 100) < defender.armor;
                        if (blocked) {
                            dmg = 0;
                            defAnim.forceState(PlayerAnimator.State.BLOCK, nowMs);
                        } else if (defender.armor > 0) {
                            dmg = Math.max(1, dmg - dmg * defender.armor / 200);
                        }

                        // Deduct HP from defender
                        double defCx = (practiceCombatTurn == 0) ? w * 0.65 : w * 0.35;
                        double floorYNow = h - 30;
                        if (!blocked) {
                            if (practiceCombatTurn == 0) practiceRightHp = Math.max(0, practiceRightHp - dmg);
                            else                         practiceLeftHp  = Math.max(0, practiceLeftHp  - dmg);
                        }
                        if (AppSettings.isCombatShowDamage())
                            practiceDmgNums.add(new Object[]{ defCx, floorYNow - 80, (double)dmg, (double)nowMs, 0.0,
                                    blocked ? "BLOCKED" : rule.label.get() });

                        float defHpAfter = (practiceCombatTurn == 0) ? practiceRightHp : practiceLeftHp;
                        if (!blocked && defHpAfter == 0) {
                            // Defender KO'd — play fall animation, hold, then recover after koMs
                            PlayerAnimator.State deathState = defender.bodyType == MobCategory.QUADRUPED
                                    ? PlayerAnimator.State.QUAD_DEATH : PlayerAnimator.State.KNOCKED_DOWN;
                            defAnim.forceState(deathState, nowMs);
                            defAnim.setHoldLastFrame(true);
                            // KO recovery time is always real-world ms — not speed-adjusted
                            long koEndMs = nowMs + koMs;
                            if (practiceCombatTurn == 0) practiceRightKoEndMs = koEndMs;
                            else                         practiceLeftKoEndMs  = koEndMs;
                            practiceCombatNextMs = koEndMs; // pause combat until KO recovery fires
                            if (practiceActionLabel != null)
                                practiceActionLabel.setText("💀 " + defender.name + " is KO'd! " +
                                        attacker.name + " wins the round — recovering in " + koMs + "ms…");
                            practiceCombatTurn = 1 - practiceCombatTurn;
                            return; // skip the normal interval advance below
                        } else {
                            if (practiceActionLabel != null)
                                practiceActionLabel.setText(blocked
                                        ? defender.name + " blocked " + attacker.name + "'s " + rule.label.get() + "!"
                                        : attacker.name + " → " + rule.label.get() + " → " + defender.name + " (" + dmg + " dmg)");
                        }
                    } else {
                        if (practiceActionLabel != null)
                            practiceActionLabel.setText(attacker.name + " has no attack states defined.");
                    }
                }

                practiceCombatNextMs = nowMs + (long)(interval / practiceSpeedMult);
                practiceCombatTurn   = 1 - practiceCombatTurn;
            }
        }

        double leftCx  = practiceFaceEachOther ? w * 0.40 : w * 0.35;
        double rightCx = practiceFaceEachOther ? w * 0.60 : w * 0.65;
        double floorY  = h - 30;

        // ── Floor ─────────────────────────────────────────────────────────────
        gc.setFill(Color.web("#2a2040"));
        gc.fillRect(0, floorY, w, h - floorY);
        gc.setStroke(Color.web("#6a5acd"));
        gc.setLineWidth(2);
        gc.strokeLine(0, floorY, w, floorY);

        if (lm != null) {
            practiceLeftAnim.setFacingRight(practiceFaceEachOther);
            shadow(gc, leftCx, floorY);
            practiceLeftAnim.draw(gc, leftCx, floorY, lm.tint, lm.scale * PRACTICE_SCALE, nowMs);
        }
        if (rm != null) {
            practiceRightAnim.setFacingRight(!practiceFaceEachOther);
            shadow(gc, rightCx, floorY);
            practiceRightAnim.draw(gc, rightCx, floorY, rm.tint, rm.scale * PRACTICE_SCALE, nowMs);
        }
        if (lm != null && rm != null && !practicePlaying) {
            gc.setFill(Color.web("#f0a030"));
            gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 14));
            gc.fillText("VS", w / 2 - 8, floorY - 80);
        }

        // ── Hit Flash ────────────────────────────────────────────────────────
        if (AppSettings.isCombatShowHits()) {
            long HIT_FLASH_MS = 200;
            if (practiceLeftHitMs  > 0 && nowMs - practiceLeftHitMs  < HIT_FLASH_MS) {
                double alpha = 0.45 * (1.0 - (double)(nowMs - practiceLeftHitMs) / HIT_FLASH_MS);
                gc.setFill(Color.color(1, 0.3, 0.3, alpha));
                gc.fillOval(leftCx - 20, floorY - 100, 40, 80);
            }
            if (practiceRightHitMs > 0 && nowMs - practiceRightHitMs < HIT_FLASH_MS) {
                double alpha = 0.45 * (1.0 - (double)(nowMs - practiceRightHitMs) / HIT_FLASH_MS);
                gc.setFill(Color.color(1, 0.3, 0.3, alpha));
                gc.fillOval(rightCx - 20, floorY - 100, 40, 80);
            }
        }

        // ── Per-mob stacked overlay: Stance → Name → HP bar (top to bottom) ──
        gc.setFont(javafx.scene.text.Font.font("System", 9));
        for (int side = 0; side < 2; side++) {
            MobDef mob   = (side == 0) ? lm : rm;
            if (mob == null) continue;
            PlayerAnimator anim = (side == 0) ? practiceLeftAnim : practiceRightAnim;
            double cx    = (side == 0) ? leftCx : rightCx;
            float  curHp = (side == 0) ? practiceLeftHp : practiceRightHp;

            double headTop = floorY - 63.0 * mob.scale * PRACTICE_SCALE;
            double hpBarY  = headTop - 8;
            double nameY   = hpBarY  - 10;
            double stanceY = nameY   - 12;

            // HP bar
            if (AppSettings.isCombatShowHealthBar())
                drawHpBar(gc, cx, hpBarY, curHp, mob.baseHp);

            // Name (action)
            gc.setFill(mob.tint.deriveColor(0, 1.0, 1.5, 1.0));
            gc.setFont(javafx.scene.text.Font.font("System", 9));
            gc.fillText(mob.name, cx - mob.name.length() * 2.5, nameY);

            // Stance (topmost)
            if (AppSettings.isCombatShowStance()) {
                String st = anim.getState().name();
                gc.setFill(Color.web("#88aacc"));
                gc.fillText(st, cx - st.length() * 2.5, stanceY);
            }
        }

        // ── Floating Damage / Heal Numbers ────────────────────────────────────
        if (AppSettings.isCombatShowDamage()) {
            long DMG_LIFE_MS = 900;
            practiceDmgNums.removeIf(d -> nowMs - (long)(double)d[3] > DMG_LIFE_MS);
            gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
            Color dmgColor  = Color.web(GameMechanicsPanel.loadDisplayColor("damageColor", GameMechanicsPanel.DEFAULT_DAMAGE_COLOR));
            Color healColor = Color.web(GameMechanicsPanel.loadDisplayColor("healColor",   GameMechanicsPanel.DEFAULT_HEAL_COLOR));
            boolean verbose = AppSettings.isCombatShowVerboseHits();
            for (Object[] d : practiceDmgNums) {
                double age    = nowMs - (double)d[3];
                double frac   = age / DMG_LIFE_MS;
                double dy     = frac * 30;
                double alpha  = 1.0 - frac;
                boolean isHeal = ((double)d[4]) == 1.0;
                Color base = isHeal ? healColor : dmgColor;
                gc.setFill(Color.color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
                String num  = isHeal ? "+" + (int)(double)d[2] : "-" + (int)(double)d[2];
                String text = verbose ? num + " " + d[5] : num;
                gc.fillText(text, (double)d[0] - 8, (double)d[1] - dy);
            }
        }
    }

    private void drawHpBar(GraphicsContext gc, double cx, double y, float hp, int maxHp) {
        double bw = 60, bh = 6;
        double pct = maxHp > 0 ? Math.max(0, hp / maxHp) : 0;
        Color fill = pct > 0.5 ? Color.web("#44bb44") : pct > 0.25 ? Color.web("#ddaa22") : Color.web("#cc3333");
        gc.setFill(Color.color(0, 0, 0, 0.5));
        gc.fillRoundRect(cx - bw / 2 - 1, y - 1, bw + 2, bh + 2, 4, 4);
        gc.setFill(fill);
        gc.fillRoundRect(cx - bw / 2, y, bw * pct, bh, 3, 3);
        gc.setStroke(Color.color(1, 1, 1, 0.3));
        gc.setLineWidth(1);
        gc.strokeRoundRect(cx - bw / 2, y, bw, bh, 3, 3);
    }

    private MobDef mobByName(String name) {
        if (name == null) return null;
        for (MobDef m : mobs) if (m.name.equals(name)) return m;
        return null;
    }

    private void shadow(GraphicsContext gc, double cx, double floorY) {
        gc.setFill(Color.color(0, 0, 0, 0.35));
        gc.fillOval(cx - 12, floorY - 2, 24, 6);
    }

    private void nameTag(GraphicsContext gc, String name, double cx, double floorY, Color tint, double scale) {
        final double BASE_H = 63.0;
        double tagY = floorY - BASE_H * scale * PRACTICE_SCALE - 8;
        gc.setFill(tint.deriveColor(0, 1.0, 1.5, 1.0));
        gc.setFont(javafx.scene.text.Font.font("System", 9));
        gc.fillText(name, cx - name.length() * 2.5, tagY);
    }

    /** Re-populates the states FlowPane with only the states that belong to the given body type. */
    private void refreshStateChecks(MobCategory bodyType) {
        // Find the FlowPane inside the ScrollPane — walk up from any checkbox
        if (stateChecks.isEmpty()) return;
        // Locate the FlowPane via the first checkbox's parent
        javafx.scene.Parent parent = stateChecks.get(0).getParent();
        if (parent == null) {
            // try finding it from stateChecks that are already in the scene
            for (CheckBox cb : stateChecks) {
                if (cb.getParent() instanceof FlowPane fp) { parent = fp; break; }
            }
        }
        if (!(parent instanceof FlowPane)) return;
        FlowPane pane = (FlowPane) parent;
        pane.getChildren().clear();
        for (CheckBox cb : stateChecks) {
            try {
                PlayerAnimator.State s = PlayerAnimator.State.valueOf(cb.getText());
                if (bodyType.contains(s)) pane.getChildren().add(cb);
            } catch (IllegalArgumentException ignored) {}
        }
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
