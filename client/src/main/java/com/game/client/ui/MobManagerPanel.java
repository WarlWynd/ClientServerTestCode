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
        int         speed;
        int         aggroRange;
        String      lootTable = "";
        double      scale     = 1.0;
        final List<PlayerAnimator.State> states = new ArrayList<>();

        MobDef(String name, MobRole role, Color tint) {
            this.name = name; this.role = role; this.tint = tint;
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
    private ListView<String>          mobList;
    private TextField                 nameField;
    private ComboBox<String>          categoryCombo;
    private ComboBox<MobCategory>     bodyTypeCombo;
    private ColorPicker               colorPicker;
    private Spinner<Integer>    hpSpinner, dmgSpinner, speedSpinner, aggroSpinner;
    private Spinner<Double>     scaleSpinner;
    private List<CheckBox>      stateChecks;
    private ComboBox<String>    lootTableCombo;
    private Label               rosterStatus;

    // Preview
    private Canvas              previewCanvas;
    private PlayerAnimator      previewAnimator;
    private AnimationTimer      previewTimer;

    // UI refs — Map Spawns
    private ComboBox<String>    boardCombo;
    private TableView<SpawnPoint> spawnList;
    private Canvas              mapCanvas;
    private Label               spawnStatus;
    private Label               zoomValLbl;
    private ComboBox<String>    placeMobCombo;
    private Spinner<Integer>    respawnSpinner, maxCountSpinner;

    // ── Combat preview inner classes ──────────────────────────────────────────
    private static class DmgNum {
        static final long LIFE_NS = 1_400_000_000L;
        static final double RISE  = 30.0;
        final double x, baseY; final String text; final Color color; final long birthNs;
        DmgNum(double x, double y, int dmg, String label, Color color, long now) {
            this.x=x; this.baseY=y; this.text=dmg+(label.isEmpty()?"":" "+label);
            this.color=color; this.birthNs=now;
        }
        boolean dead(long now) { return now-birthNs>=LIFE_NS; }
        double alpha(long now) { return 1.0-(double)(now-birthNs)/LIFE_NS; }
        double currentY(long now){ return baseY-RISE*((double)(now-birthNs)/LIFE_NS); }
    }

    private static class MobFighter {
        MobDef mob; final PlayerAnimator anim=new PlayerAnimator(); final boolean facingLeft;
        int  hp; int maxHp; long nextAttkMs=0; PlayerAnimator.State pendingAttack=null;
        boolean hitPending=false; long hitTimeMs=0; long stunEndMs=0;
        boolean kipUpTriggered=false;
        MobFighter(MobDef mob, boolean facingLeft, long nowMs) {
            this.mob=mob; this.facingLeft=facingLeft;
            maxHp = Math.max(1, mob.baseHp);
            hp    = maxHp;
            anim.setFacingRight(!facingLeft);
            nextAttkMs=nowMs+(facingLeft?1_000:500);
        }
        boolean isStunned(long nowMs){ return nowMs<stunEndMs; }
        boolean isKO(){ return hp<=0; }
        boolean hasState(PlayerAnimator.State s){ return mob.states.contains(s); }
        List<PlayerAnimator.State> attackStates(){
            return mob.states.stream().filter(MobFighter::isAttack).collect(java.util.stream.Collectors.toList());
        }
        static boolean isAttack(PlayerAnimator.State s){
            return switch(s){
                case PUNCH,CROSS,HOOK,UPPERCUT,HAYMAKER,
                     HEAD_KICK,LOW_KICK,BODY_KICK,
                     SPINNING_BACK_KICK,SIDE_KICK,SHOOT,
                     BITE,POUNCE -> true;
                default -> false;
            };
        }
        PlayerAnimator.State idleState(){
            if(mob.states.contains(PlayerAnimator.State.QUAD_IDLE)) return PlayerAnimator.State.QUAD_IDLE;
            if(mob.states.contains(PlayerAnimator.State.IDLE)) return PlayerAnimator.State.IDLE;
            return mob.states.isEmpty()?PlayerAnimator.State.IDLE:mob.states.get(0);
        }
        PlayerAnimator.State knockedState(){
            if(mob.states.contains(PlayerAnimator.State.KNOCKED_DOWN)) return PlayerAnimator.State.KNOCKED_DOWN;
            if(mob.states.contains(PlayerAnimator.State.QUAD_DEATH))   return PlayerAnimator.State.QUAD_DEATH;
            return null;
        }
    }

    // UI refs — Combat
    private Canvas              combatCanvas;
    private AnimationTimer      combatTimer;
    private ComboBox<String>    combatLeftCombo, combatRightCombo;
    private boolean             combatPaused      = false;
    private long                combatFrozenNs    = 0;
    private boolean             combatFaceEachOther = true;
    private long                combatAttackIntervalMs = 2_200;

    // Combat engine state
    private MobFighter          cLeftFighter, cRightFighter;
    private String              cResolvedLeft, cResolvedRight;
    private long                cKoTimeMs=0; private String cKoText="";
    private MobFighter          cKoFighter=null; private boolean cHealthResetDone=false;
    private long                cKipUpStartMs=0;
    private final List<DmgNum>  cDmgNums = new ArrayList<>();
    private final Random        cRng     = new Random();
    private boolean             suppressCombatComboEvents = false;

    private static final int    COMBAT_TOTAL_H  = 380;
    private static final int    COMBAT_CANVAS_W = 420;
    private static final int    COMBAT_H        = (int)(COMBAT_TOTAL_H * 0.78);
    private static final int    COMBAT_FLOOR_H  = COMBAT_TOTAL_H - COMBAT_H;
    private static final double COMBAT_SCALE    = 1.5;
    private static final long   C_ATTACK_MS     = 2_200;
    private static final long   C_HIT_DELAY_MS  = 500;
    private static final long   C_STUN_MS       = 650;
    private static final long   C_KO_RESET_MS   = 20_000;
    private long                cKipUpDelayMs   = 15_000;
    private static final long   C_KIP_UP_DUR    = 900;
    private static final long   C_STEP_NS       = 50_000_000L;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        loadMobs();
        loadSpawns();
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
        speedSpinner = intSpinner(1, 50,   3);
        aggroSpinner = intSpinner(10, 2000, 200);
        scaleSpinner = new Spinner<>(new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 10.0, 1.0, 0.1));
        scaleSpinner.setEditable(true);
        scaleSpinner.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;");
        for (Spinner<?> sp : new Spinner<?>[]{ hpSpinner, dmgSpinner, speedSpinner, aggroSpinner, scaleSpinner }) {
            sp.setPrefWidth(80); sp.setMaxWidth(80);
        }
        hpSpinner.valueProperty().addListener((obs, o, n)    -> { if (selected != null) selected.baseHp     = n; });
        dmgSpinner.valueProperty().addListener((obs, o, n)   -> { if (selected != null) selected.baseDamage = n; });
        speedSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.speed      = n; });
        aggroSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.aggroRange = n; });
        scaleSpinner.valueProperty().addListener((obs, o, n) -> { if (selected != null) selected.scale      = n; });

        // Compact 6-column grid: label+spinner pairs side-by-side
        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(6); statsGrid.setVgap(5);
        statsGrid.add(lbl("HP:",    10, false), 0, 0); statsGrid.add(hpSpinner,    1, 0);
        statsGrid.add(lbl("Dmg:",   10, false), 2, 0); statsGrid.add(dmgSpinner,   3, 0);
        statsGrid.add(lbl("Speed:", 10, false), 0, 1); statsGrid.add(speedSpinner, 1, 1);
        statsGrid.add(lbl("Aggro:", 10, false), 2, 1); statsGrid.add(aggroSpinner, 3, 1);
        statsGrid.add(lbl("Scale:", 10, false), 0, 2); statsGrid.add(scaleSpinner, 1, 2);
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

        Node combatPanel = buildCombatPanel();

        HBox root = new HBox(8, leftCol, cfgCol, previewCol, combatPanel);
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

        HBox bottomRow = new HBox(bottomBar);

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
        syncCombatCombos();
    }

    private void syncCombatCombos() {
        if (combatLeftCombo == null || combatRightCombo == null) return;
        String prevL = combatLeftCombo.getValue(), prevR = combatRightCombo.getValue();
        suppressCombatComboEvents = true;
        combatLeftCombo.getItems().clear(); combatRightCombo.getItems().clear();
        mobs.forEach(m -> { combatLeftCombo.getItems().add(m.name); combatRightCombo.getItems().add(m.name); });
        combatLeftCombo.setValue(combatLeftCombo.getItems().contains(prevL) ? prevL :
                (!mobs.isEmpty() ? mobs.get(0).name : null));
        combatRightCombo.setValue(combatRightCombo.getItems().contains(prevR) ? prevR :
                (mobs.size() > 1 ? mobs.get(1).name : (!mobs.isEmpty() ? mobs.get(0).name : null)));
        suppressCombatComboEvents = false;
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
        categoryCombo.setValue(mob.role.name());
        bodyTypeCombo.setValue(mob.bodyType);
        refreshStateChecks(mob.bodyType);
        hpSpinner.getValueFactory().setValue(mob.baseHp);
        scaleSpinner.getValueFactory().setValue(mob.scale);
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
    //  COMBAT PREVIEW TAB
    // ══════════════════════════════════════════════════════════════════════════

    private Node buildCombatPanel() {
        String btnBase = "-fx-text-fill:white;-fx-background-radius:4;-fx-font-size:11;-fx-padding:3 10 3 10;";
        Button pauseBtn    = new Button("⏸ Pause");
        Button stopBtn     = new Button("⏹ Stop");
        Button stepBackBtn = new Button("◀");
        Button stepFwdBtn  = new Button("▶");
        pauseBtn.setStyle("-fx-background-color:#1e3a5f;"+btnBase);
        stopBtn.setStyle("-fx-background-color:#7b241c;"+btnBase);
        stepBackBtn.setStyle("-fx-background-color:#3a3a5f;"+btnBase);
        stepFwdBtn.setStyle("-fx-background-color:#3a3a5f;"+btnBase);
        stepBackBtn.setDisable(true); stepFwdBtn.setDisable(true);

        pauseBtn.setOnAction(e -> {
            combatPaused = !combatPaused;
            pauseBtn.setText(combatPaused ? "▶ Play" : "⏸ Pause");
            pauseBtn.setStyle("-fx-background-color:"+(combatPaused?"#1e5f3a":"#1e3a5f")+";"+btnBase);
            stepBackBtn.setDisable(!combatPaused); stepFwdBtn.setDisable(!combatPaused);
        });
        stopBtn.setOnAction(e -> {
            combatPaused = true;
            pauseBtn.setText("▶ Play");
            pauseBtn.setStyle("-fx-background-color:#1e5f3a;"+btnBase);
            stepBackBtn.setDisable(false); stepFwdBtn.setDisable(false);
            resetCombatPreview();
        });
        stepBackBtn.setOnAction(e -> { if (combatPaused && combatFrozenNs > C_STEP_NS) combatFrozenNs -= C_STEP_NS; });
        stepFwdBtn.setOnAction(e  -> { if (combatPaused) combatFrozenNs += C_STEP_NS; });

        CheckBox faceCheck = new CheckBox("Face Each Other");
        faceCheck.setSelected(true);
        faceCheck.setStyle("-fx-text-fill:#c8c8e0;-fx-font-size:11;");
        faceCheck.setOnAction(e -> combatFaceEachOther = faceCheck.isSelected());

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        HBox titleRow = new HBox(6, lbl("Combat Preview", 13, true), faceCheck, sp, stepBackBtn, stepFwdBtn, pauseBtn, stopBtn);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // Canvas + floor strip
        combatCanvas = new Canvas(COMBAT_CANVAS_W, COMBAT_H);
        Region floorStrip = new Region();
        floorStrip.getStyleClass().add("combat-floor");
        floorStrip.setMinSize(COMBAT_CANVAS_W, COMBAT_FLOOR_H);
        floorStrip.setMaxSize(COMBAT_CANVAS_W, COMBAT_FLOOR_H);
        StackPane combatArea = new StackPane(combatCanvas);
        combatArea.setStyle("-fx-background-color:#0f0f1e;");
        combatArea.setMinSize(COMBAT_CANVAS_W, COMBAT_H);
        combatArea.setMaxSize(COMBAT_CANVAS_W, COMBAT_H);
        VBox innerBox = new VBox(0, combatArea, floorStrip);
        innerBox.setMinSize(COMBAT_CANVAS_W, COMBAT_TOTAL_H);
        StackPane canvasBox = new StackPane(innerBox);
        canvasBox.setStyle("-fx-background-color:#0f0f1e;");

        // Fighter selectors
        combatLeftCombo  = new ComboBox<>(); combatLeftCombo.getStyleClass().add("combo-dark");  combatLeftCombo.setMaxWidth(Double.MAX_VALUE);
        combatRightCombo = new ComboBox<>(); combatRightCombo.getStyleClass().add("combo-dark"); combatRightCombo.setMaxWidth(Double.MAX_VALUE);
        combatLeftCombo.setOnAction(e  -> { if (!suppressCombatComboEvents) resetCombatPreview(); });
        combatRightCombo.setOnAction(e -> { if (!suppressCombatComboEvents) resetCombatPreview(); });

        Button resetBtn = btn("⚔ Reset Fight", "#2e1a5f");
        resetBtn.setMaxWidth(Double.MAX_VALUE);
        resetBtn.setOnAction(e -> resetCombatPreview());

        Label speedLbl = lbl("Speed", 10, true);
        Slider speedSlider = new Slider(0, 100, 50);
        speedSlider.setMaxWidth(Double.MAX_VALUE);
        speedSlider.setStyle("-fx-control-inner-background:#0f0f1e;");
        Label speedValLbl = new Label("Normal"); speedValLbl.setStyle("-fx-text-fill:#9090b0;-fx-font-size:9;");
        // Sync initial value from slider position (default 50 → ~2150 ms)
        { double t0 = speedSlider.getValue()/100.0; combatAttackIntervalMs = Math.round(4000 - t0*(4000-300)); }
        speedSlider.valueProperty().addListener((obs, o, n) -> {
            double t = n.doubleValue()/100.0;
            combatAttackIntervalMs = Math.round(4000 - t*(4000-300));
            if(t<0.25) speedValLbl.setText("Slow"); else if(t<0.55) speedValLbl.setText("Normal");
            else if(t<0.80) speedValLbl.setText("Fast"); else speedValLbl.setText("Very Fast");
        });
        HBox speedRow = new HBox(4, speedSlider, speedValLbl); speedRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(speedSlider, Priority.ALWAYS);

        Label fallenLbl = lbl("Fallen Duration (sec)", 10, true);
        Spinner<Integer> fallenSpinner = new Spinner<>(0, 120, (int)(cKipUpDelayMs / 1000));
        fallenSpinner.setEditable(true);
        fallenSpinner.setMaxWidth(Double.MAX_VALUE);
        fallenSpinner.getStyleClass().add("spinner-dark");
        fallenSpinner.valueProperty().addListener((obs, o, n) -> cKipUpDelayMs = n * 1000L);

        VBox selectorCol = new VBox(6,
                lbl("Fighter 1 (left):", 10, true), combatLeftCombo,
                lbl("Fighter 2 (right):", 10, true), combatRightCombo,
                resetBtn, speedLbl, speedRow,
                fallenLbl, fallenSpinner);
        selectorCol.setPadding(new Insets(4));
        selectorCol.setPrefWidth(190);
        selectorCol.setStyle("-fx-background-color:#16213e;-fx-background-radius:4;");

        HBox arenaRow = new HBox(6, canvasBox, selectorCol);
        arenaRow.setAlignment(Pos.TOP_LEFT);

        VBox root = vbox(8, titleRow, arenaRow);

        syncCombatCombos();
        startCombatPreview();
        return root;
    }

    private void startCombatPreview() {
        combatTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (combatPaused) { if (combatFrozenNs==0) combatFrozenNs=now; drawCombat(combatFrozenNs); }
                else              { combatFrozenNs=now; drawCombat(now); }
            }
        };
        combatTimer.start();
    }

    private void resetCombatPreview() {
        cLeftFighter=null; cRightFighter=null; cResolvedLeft=null; cResolvedRight=null;
        cKoTimeMs=0; cKoText=""; cKoFighter=null; cHealthResetDone=false; cKipUpStartMs=0; combatFrozenNs=0; cDmgNums.clear();
    }

    private void resolveCombatFighters(long nowMs) {
        String lName = combatLeftCombo !=null ? combatLeftCombo.getValue()  : null;
        String rName = combatRightCombo!=null ? combatRightCombo.getValue() : null;
        if (lName==null && !mobs.isEmpty()) lName=mobs.get(0).name;
        if (rName==null && mobs.size()>1)   rName=mobs.get(1).name;
        boolean changed = !java.util.Objects.equals(lName,cResolvedLeft)||!java.util.Objects.equals(rName,cResolvedRight);
        if (changed) {
            cResolvedLeft=lName; cResolvedRight=rName; cDmgNums.clear(); cKoTimeMs=0; cKoText=""; cKipUpStartMs=0; cKoFighter=null; cHealthResetDone=false;
            MobDef lm=mobByName(lName), rm=mobByName(rName);
            cLeftFighter  = lm!=null ? new MobFighter(lm, false, nowMs) : null;
            cRightFighter = rm!=null ? new MobFighter(rm, true,  nowMs) : null;
        }
    }

    private MobDef mobByName(String name) {
        if (name==null) return null;
        for (MobDef m : mobs) if (m.name.equals(name)) return m;
        return null;
    }

    private void updateCombatFighter(MobFighter f, MobFighter opp, long nowMs, long nowNs,
                                     double fCx, double oCx, double floorY) {
        if (f.isKO()||opp==null) return;
        if (f.isStunned(nowMs)) return;
        PlayerAnimator.State cs = f.anim.getCurrentState();
        if (cs==PlayerAnimator.State.GOTHIT01||cs==PlayerAnimator.State.GOTHIT02||
            cs==PlayerAnimator.State.GOTHIT03||cs==PlayerAnimator.State.KNOCKED_DOWN)
            f.anim.forceState(f.idleState(), nowMs);
        if (f.hitPending && nowMs>=f.hitTimeMs) {
            f.hitPending=false;
            int dmg=cDamageFor(f.pendingAttack);
            if (!opp.isKO()) {
                opp.hp=Math.max(0,opp.hp-dmg);
                String lbl=cAttackLabel(f.pendingAttack);
                cDmgNums.add(new DmgNum(fCx+cRng.nextInt(20)-10, floorY-35-cRng.nextInt(8), dmg, lbl, Color.web("#ffdd00"), nowNs));
                cDmgNums.add(new DmgNum(oCx+cRng.nextInt(20)-10, floorY-42-cRng.nextInt(10),dmg, lbl, Color.web("#ff3344"), nowNs));
                if (!opp.isKO()) {
                    PlayerAnimator.State hitSt = opp.hp<10 && opp.hasState(PlayerAnimator.State.KNOCKED_DOWN)
                            ? PlayerAnimator.State.KNOCKED_DOWN : cHitStateFor(f.pendingAttack, opp);
                    if (hitSt!=null) { opp.anim.forceState(hitSt,nowMs); opp.stunEndMs=nowMs+C_STUN_MS; }
                } else {
                    PlayerAnimator.State dead = opp.knockedState();
                    if (dead==null) dead=opp.idleState();
                    opp.anim.forceState(dead,nowMs);
                    if (dead==PlayerAnimator.State.KNOCKED_DOWN||dead==PlayerAnimator.State.QUAD_DEATH)
                        opp.anim.setHoldLastFrame(true);
                    cKoTimeMs=nowMs; cKoText=f.mob.name+" wins!"; cKoFighter=opp; cHealthResetDone=false;
                    f.hitPending=false; f.anim.forceState(f.idleState(),nowMs);
                }
            }
        }
        if (!f.hitPending && !opp.isKO() && nowMs>=f.nextAttkMs) {
            List<PlayerAnimator.State> attacks=f.attackStates();
            if (!attacks.isEmpty()) {
                f.pendingAttack=attacks.get(cRng.nextInt(attacks.size()));
                f.anim.forceState(f.pendingAttack,nowMs);
                f.hitPending=true; f.hitTimeMs=nowMs+C_HIT_DELAY_MS; f.nextAttkMs=nowMs+combatAttackIntervalMs;
            }
        }
    }

    private void drawCombat(long nowNs) {
        long nowMs=nowNs/1_000_000L;
        GraphicsContext gc=combatCanvas.getGraphicsContext2D();
        double w=combatCanvas.getWidth(), h=combatCanvas.getHeight(), floorY=h;
        gc.setFill(Color.web("#0f0f1e")); gc.fillRect(0,0,w,h);
        resolveCombatFighters(nowMs);
        if (cLeftFighter==null&&cRightFighter==null) {
            gc.setFill(Color.web("#606080")); gc.setFont(javafx.scene.text.Font.font("System",11));
            gc.fillText("Select two mobs to fight.",w/2-75,h/2); return;
        }
        double leftCx=w*0.40, rightCx=w*0.60;
        if (combatPaused&&cKoTimeMs>0) cKoTimeMs=nowMs-Math.min(nowMs-cKoTimeMs,C_KO_RESET_MS-100);
        if (cKoTimeMs>0&&nowMs-cKoTimeMs>C_KO_RESET_MS) {
            long st=0;
            if(cLeftFighter !=null){cLeftFighter.hp =cLeftFighter.maxHp; cLeftFighter.hitPending =false;cLeftFighter.kipUpTriggered =false;cLeftFighter.nextAttkMs =nowMs+(st+=400);cLeftFighter.anim.forceState(cLeftFighter.idleState(),nowMs);}
            if(cRightFighter!=null){cRightFighter.hp=cRightFighter.maxHp;cRightFighter.hitPending=false;cRightFighter.kipUpTriggered=false;cRightFighter.nextAttkMs=nowMs+(st+=600);cRightFighter.anim.forceState(cRightFighter.idleState(),nowMs);}
            cKoTimeMs=0; cKoText=""; cKoFighter=null; cKipUpStartMs=0; cDmgNums.clear();
        }
        if (!combatPaused&&cKoTimeMs==0) {
            updateCombatFighter(cLeftFighter, cRightFighter,nowMs,nowNs,leftCx, rightCx,floorY);
            updateCombatFighter(cRightFighter,cLeftFighter, nowMs,nowNs,rightCx,leftCx, floorY);
        }
        double barW=90, barH=7;
        // Base sprite height at scale=1.0 is ~63 canvas px; scale bar above head accordingly
        final double BASE_SPRITE_H = 63.0;
        if(cLeftFighter !=null){ double barY=floorY-BASE_SPRITE_H*cLeftFighter.mob.scale *COMBAT_SCALE-10; cDrawHpBar(gc,leftCx -barW/2,barY,barW,barH,cLeftFighter.hp, cLeftFighter.maxHp, cLeftFighter.mob.tint, cLeftFighter.mob.name);}
        if(cRightFighter!=null){ double barY=floorY-BASE_SPRITE_H*cRightFighter.mob.scale*COMBAT_SCALE-10; cDrawHpBar(gc,rightCx-barW/2,barY,barW,barH,cRightFighter.hp,cRightFighter.maxHp,cRightFighter.mob.tint,cRightFighter.mob.name);}
        gc.setFill(Color.web("#f0a030"));
        gc.setFont(javafx.scene.text.Font.font("System",javafx.scene.text.FontWeight.BOLD,14));
        gc.fillText("VS",w/2-8,floorY-105);
        if(cLeftFighter !=null){if(combatFaceEachOther)cLeftFighter.anim.setFacingRight(true);  cShadow(gc,leftCx, floorY);cLeftFighter.anim.draw(gc,leftCx, floorY,cLeftFighter.mob.tint, cLeftFighter.mob.scale *COMBAT_SCALE,nowMs); double lBarY=floorY-BASE_SPRITE_H*cLeftFighter.mob.scale *COMBAT_SCALE-10; cStateTag(gc,cLeftFighter.anim.getCurrentState().name(), leftCx, lBarY,cLeftFighter.mob.tint);}
        if(cRightFighter!=null){if(combatFaceEachOther)cRightFighter.anim.setFacingRight(false); cShadow(gc,rightCx,floorY);cRightFighter.anim.draw(gc,rightCx,floorY,cRightFighter.mob.tint,cRightFighter.mob.scale*COMBAT_SCALE,nowMs); double rBarY=floorY-BASE_SPRITE_H*cRightFighter.mob.scale*COMBAT_SCALE-10; cStateTag(gc,cRightFighter.anim.getCurrentState().name(),rightCx,rBarY,cRightFighter.mob.tint);}
        cDmgNums.removeIf(d->d.dead(nowNs));
        gc.setFont(javafx.scene.text.Font.font("System",javafx.scene.text.FontWeight.BOLD,12));
        for(DmgNum d:cDmgNums){double a=d.alpha(nowNs);gc.setFill(d.color.deriveColor(0,1,1.2,a));gc.fillText(d.text,d.x-d.text.length()*3.5,d.currentY(nowNs));}
        if(cKoTimeMs>0&&cKoFighter!=null){
            long el=nowMs-cKoTimeMs;
            boolean hasKipUp   = cKoFighter.hasState(PlayerAnimator.State.KIP_UP);
            PlayerAnimator.State koSt = cKoFighter.anim.getState();
            boolean isKnockedDown = koSt==PlayerAnimator.State.KNOCKED_DOWN||koSt==PlayerAnimator.State.QUAD_DEATH;
            // Trigger get-up after fallen duration
            if(!cKoFighter.kipUpTriggered && el>=cKipUpDelayMs && isKnockedDown){
                cKoFighter.anim.setHoldLastFrame(false);
                if(hasKipUp){
                    cKoFighter.anim.forceState(PlayerAnimator.State.KIP_UP,nowMs);
                } else {
                    cKoFighter.anim.forceState(cKoFighter.idleState(),nowMs);
                }
                cKoFighter.kipUpTriggered=true; cKipUpStartMs=nowMs; cKoFighter.nextAttkMs=nowMs+C_KIP_UP_DUR+2000;
            }
            if(!cHealthResetDone&&cKoFighter.kipUpTriggered){
                if(cLeftFighter!=null)cLeftFighter.hp=cLeftFighter.maxHp; if(cRightFighter!=null)cRightFighter.hp=cRightFighter.maxHp; cHealthResetDone=true;
            }
            // Clear KO after kip-up animation finishes (time-based, not state-check-based)
            if(cKoFighter.kipUpTriggered){
                long sinceKipUp = nowMs - cKipUpStartMs;
                boolean kipUpDone = hasKipUp ? sinceKipUp >= C_KIP_UP_DUR + 200 : sinceKipUp >= 500;
                if(kipUpDone){
                    if(hasKipUp) cKoFighter.anim.forceState(cKoFighter.idleState(),nowMs);
                    cKoFighter.nextAttkMs=nowMs+1200; cKoFighter.hitPending=false;
                    cKoTimeMs=0; cKoText=""; cKoFighter=null; cKipUpStartMs=0;
                }
            }
        }
        if(cKoTimeMs>0){
            gc.setFill(Color.color(0,0,0,0.5)); gc.fillRect(0,floorY-135,w,30);
            gc.setFill(Color.web("#f0a030")); gc.setFont(javafx.scene.text.Font.font("System",javafx.scene.text.FontWeight.BOLD,16));
            gc.fillText("K  O !",w/2-20,floorY-122);
            gc.setFill(Color.web("#e0e0e0")); gc.setFont(javafx.scene.text.Font.font("System",javafx.scene.text.FontWeight.BOLD,10));
            gc.fillText(cKoText,w/2-cKoText.length()*3.0,floorY-112);
        }
    }

    private void cDrawHpBar(GraphicsContext gc,double x,double y,double w,double h,int hp,int maxHp,Color tint,String name){
        gc.setFill(Color.color(0.1,0.1,0.15,0.8)); gc.fillRoundRect(x-1,y-1,w+2,h+2,4,4);
        double pct=Math.max(0,(double)hp/maxHp);
        gc.setFill(pct>0.5?Color.web("#50c050"):pct>0.25?Color.web("#f0a030"):Color.web("#e94560"));
        gc.fillRoundRect(x,y,w*pct,h,4,4);
        gc.setStroke(tint.deriveColor(0,1,0.7,1)); gc.setLineWidth(1); gc.strokeRoundRect(x,y,w,h,4,4);
        gc.setFill(Color.web("#e0e0e0")); gc.setFont(javafx.scene.text.Font.font("System",8));
        gc.fillText(name+"  "+hp+"/"+maxHp+" HP",x,y-2);
    }
    private void cShadow(GraphicsContext gc,double cx,double floorY){gc.setFill(Color.color(0,0,0,0.35));gc.fillOval(cx-12,floorY-2,24,6);}
    private void cStateTag(GraphicsContext gc,String s,double cx,double barY,Color tint){gc.setFill(tint.deriveColor(0,1.0,1.5,1.0));gc.setFont(javafx.scene.text.Font.font("System",8));gc.fillText(s,cx-s.length()*2.0,barY-14);}

    private static PlayerAnimator.State cHitStateFor(PlayerAnimator.State attack, MobFighter target) {
        PlayerAnimator.State zone = switch(attack) {
            case UPPERCUT,HEAD_KICK,HAYMAKER           -> PlayerAnimator.State.GOTHIT03;
            case PUNCH,CROSS,HOOK,BODY_KICK,SHOOT      -> PlayerAnimator.State.GOTHIT02;
            case BITE,POUNCE                           -> PlayerAnimator.State.GOTHIT01;
            default                                    -> PlayerAnimator.State.GOTHIT01;
        };
        if(target.hasState(zone)) return zone;
        if(target.hasState(PlayerAnimator.State.GOTHIT01)) return PlayerAnimator.State.GOTHIT01;
        return null;
    }

    private int cDamageFor(PlayerAnimator.State s) {
        if(s==null) return 0;
        return switch(s) {
            case PUNCH,CROSS,HOOK          -> cRng.nextInt(8)+5;
            case UPPERCUT                  -> cRng.nextInt(10)+10;
            case HAYMAKER                  -> cRng.nextInt(12)+18;
            case HEAD_KICK,BODY_KICK       -> cRng.nextInt(10)+10;
            case LOW_KICK                  -> cRng.nextInt(8)+7;
            case SPINNING_BACK_KICK,SIDE_KICK -> cRng.nextInt(12)+14;
            case SHOOT                     -> cRng.nextInt(15)+12;
            case BITE                      -> cRng.nextInt(12)+8;
            case POUNCE                    -> cRng.nextInt(15)+10;
            default -> 0;
        };
    }

    private static String cAttackLabel(PlayerAnimator.State s) {
        if(s==null) return "";
        return switch(s) {
            case PUNCH->"Jab"; case CROSS->"Cross"; case HOOK->"Hook";
            case UPPERCUT->"Uppercut"; case HAYMAKER->"Haymaker";
            case HEAD_KICK->"Head Kick"; case LOW_KICK->"Low Kick"; case BODY_KICK->"Body Kick";
            case SPINNING_BACK_KICK->"Spin Kick"; case SIDE_KICK->"Side Kick";
            case SHOOT->"Shot"; case BITE->"Bite"; case POUNCE->"Pounce";
            default->s.name();
        };
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
