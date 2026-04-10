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
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Sprite Character Editor — define named sprites (Heroes, NPCs) with assigned animation states.
 *
 * Each sprite has a name, body tint, and a subset of PlayerAnimator.State values it can use.
 * The Combat Preview runs a full turn-based fight between two selected sprites.
 *
 * Saved to: client/src/main/resources/graphics/sprites/sprite-characters.json
 */
public class SpriteCharacterPanel {

    private static final Path SAVE_FILE =
            Paths.get("client/src/main/resources/graphics/sprites/sprite-characters.json");

    // ── Combat constants ──────────────────────────────────────────────────────
    private static final int  MAX_HP              = 100;
    private static final long ATTACK_INTERVAL_MS  = 2_200; // ms between attacks per fighter
    private static final long HIT_DELAY_MS        = 500;   // ms after attack starts → damage lands
    private static final long STUN_MS             = 650;   // ms target shows GOTHIT01
    private static final long KO_RESET_MS         = 20_000; // ms to show KO before resetting (fallback after kip-up)
    private static final long KIP_UP_DELAY_MS    = 15_000; // ms after KO: lie on ground for 15 s then kip-up
    private static final long KIP_UP_DURATION_MS =   900; // ms for kip-up animation to complete
    private static final double SPRITE_SCALE      = 2.0;

    // ── Sprite data model ─────────────────────────────────────────────────────

    static class SpriteChar {
        String name;
        boolean isNpc;
        Color   tint;
        final List<PlayerAnimator.State> states = new ArrayList<>();

        SpriteChar(String name, boolean isNpc, Color tint) {
            this.name  = name;
            this.isNpc = isNpc;
            this.tint  = tint;
        }
    }

    // ── Floating damage label particles ───────────────────────────────────────

    private static class DamageNumber {
        static final long LIFE_NS = 1_400_000_000L;
        static final double RISE  = 60.0;

        final double x, baseY;
        final String text;
        final Color  color;
        final long   birthNs;

        DamageNumber(double x, double y, int dmg, String label, Color color, long now) {
            this.x       = x;
            this.baseY   = y;
            this.text    = dmg + (label.isEmpty() ? "" : "  " + label);
            this.color   = color;
            this.birthNs = now;
        }

        boolean dead(long now)     { return now - birthNs >= LIFE_NS; }
        double  alpha(long now)    { return 1.0 - (double)(now - birthNs) / LIFE_NS; }
        double  currentY(long now) { return baseY - RISE * ((double)(now - birthNs) / LIFE_NS); }
    }

    // ── Combat fighter state machine ──────────────────────────────────────────

    private static class Fighter {
        SpriteChar     sc;
        final PlayerAnimator anim = new PlayerAnimator();
        final boolean  facingLeft;    // right-side fighter faces left

        int  hp          = MAX_HP;
        long nextAttkMs  = 0;         // wall-clock ms when next attack fires
        PlayerAnimator.State pendingAttack = null;
        boolean hitPending     = false;
        long    hitTimeMs      = 0;
        long    stunEndMs      = 0;      // in stun (showing GOTHIT01)
        boolean kipUpTriggered = false;  // kip-up already fired this KO

        Fighter(SpriteChar sc, boolean facingLeft, long nowMs) {
            this.sc         = sc;
            this.facingLeft = facingLeft;
            anim.setFacingRight(!facingLeft);
            // Stagger starting attacks so they don't fire simultaneously
            nextAttkMs = nowMs + (facingLeft ? 1_000 : 500);
        }

        boolean isStunned(long nowMs) { return nowMs < stunEndMs; }
        boolean isKO()                { return hp <= 0; }

        /** Attack states this fighter has enabled. */
        List<PlayerAnimator.State> attackStates() {
            return sc.states.stream().filter(Fighter::isAttackState).collect(Collectors.toList());
        }

        boolean hasState(PlayerAnimator.State s) { return sc.states.contains(s); }

        static boolean isAttackState(PlayerAnimator.State s) {
            return switch (s) {
                case PUNCH, CROSS, HOOK, UPPERCUT, HAYMAKER,
                     HEAD_KICK, LOW_KICK, BODY_KICK,
                     SPINNING_BACK_KICK, SIDE_KICK, SHOOT -> true;
                default -> false;
            };
        }

        /** Idle animation (prefer IDLE, fall back to first state). */
        PlayerAnimator.State idleState() {
            if (sc.states.contains(PlayerAnimator.State.IDLE)) return PlayerAnimator.State.IDLE;
            return sc.states.isEmpty() ? PlayerAnimator.State.IDLE : sc.states.get(0);
        }
    }

    // ── Panel state ───────────────────────────────────────────────────────────

    private final List<SpriteChar>  sprites    = new ArrayList<>();
    private SpriteChar              selected   = null;

    // Combat engine
    private Fighter                 leftFighter  = null;
    private Fighter                 rightFighter = null;
    private String                  resolvedLeftName  = null;
    private String                  resolvedRightName = null;
    private long                    koTimeMs     = 0;
    private String                  koText       = "";
    private Fighter                 koFighter    = null; // the fighter who was KO'd
    private boolean                 healthResetDone = false;
    private final List<DamageNumber> dmgNumbers  = new ArrayList<>();
    private final Random             rng         = new Random();

    // UI refs
    private ListView<String>   spriteList;
    private TextField          nameField;
    private ColorPicker        colorPicker;
    private RadioButton        heroRb, npcRb;
    private ToggleGroup        typeGroup;
    private List<CheckBox>     stateChecks;
    private Label              statusLabel;
    private Canvas             previewCanvas;
    private AnimationTimer     previewTimer;
    private ComboBox<String>   fightLeftCombo;
    private ComboBox<String>   fightRightCombo;
    private boolean            paused              = false;
    private boolean            suppressComboEvents = false; // guard against programmatic setValue()
    private boolean            fightFaceEachOther  = true;
    private long               attackIntervalMs    = 2_200; // adjustable via speed slider
    private long               frozenNs            = 0;     // sim time used while paused
    private static final long  STEP_NS             = 50_000_000L; // 50 ms per step

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        load();
        ensureDefaults();

        // ── Left column: sprite roster ────────────────────────────────────────
        Label listTitle = lbl("Sprites", 13, true);

        spriteList = new ListView<>();
        spriteList.setPrefWidth(190);
        spriteList.setPrefHeight(240);
        spriteList.setMaxHeight(400);
        spriteList.setStyle(
                "-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a; -fx-border-radius: 4;" +
                "-fx-control-inner-background: #0f0f1e;");
        spriteList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12;" +
                             "-fx-background-color: " + (isSelected() ? "#3a3a6a" : "transparent") + ";");
                }
            }
        });
        refreshList();
        spriteList.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            if (i >= 0 && i < sprites.size()) loadIntoForm(sprites.get(i));
        });

        Button addHeroBtn = btn("+ Hero", "#1e3a5f");
        Button addNpcBtn  = btn("+ NPC",  "#3a1e5f");
        Button delBtn     = btn("Delete", "#7b241c");
        addHeroBtn.setTooltip(new Tooltip("Add a new Hero sprite character"));
        addNpcBtn.setTooltip(new Tooltip("Add a new NPC sprite character"));
        delBtn.setTooltip(new Tooltip("Delete the selected sprite character"));
        addHeroBtn.setOnAction(e -> addNew(false));
        addNpcBtn.setOnAction(e  -> addNew(true));
        delBtn.setOnAction(e     -> deleteSelected());

        HBox listBtns = new HBox(4, addHeroBtn, addNpcBtn, delBtn);
        VBox leftCol  = vbox(8, listTitle, spriteList, listBtns);
        leftCol.setPrefWidth(200);

        // ── Centre column: configuration ──────────────────────────────────────
        Label cfgTitle = lbl("Configuration", 13, true);

        Label nameLbl = lbl("Name:", 11, false);
        nameField = new TextField();
        nameField.setPromptText("Sprite name…");
        nameField.setStyle(
                "-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5;");
        nameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null) { selected.name = n; refreshList(); }
        });
        VBox nameRow = new VBox(4, nameLbl, nameField);

        Label typeLbl = lbl("Type:", 11, false);
        typeGroup = new ToggleGroup();
        heroRb = new RadioButton("Hero");
        npcRb  = new RadioButton("NPC");
        heroRb.setStyle("-fx-text-fill: #53c0f0;");
        npcRb.setStyle("-fx-text-fill: #e94560;");
        heroRb.setToggleGroup(typeGroup);
        npcRb.setToggleGroup(typeGroup);
        typeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (selected != null && n != null) selected.isNpc = (n == npcRb);
        });
        HBox typeRow = new HBox(10, typeLbl, heroRb, npcRb);
        typeRow.setAlignment(Pos.CENTER_LEFT);

        Label tintLbl = lbl("Tint:", 11, false);
        colorPicker = new ColorPicker(Color.web("#e0e0ff"));
        colorPicker.setStyle("-fx-color-label-visible: false;");
        colorPicker.setOnAction(e -> { if (selected != null) selected.tint = colorPicker.getValue(); });
        HBox tintRow = new HBox(10, tintLbl, colorPicker);
        tintRow.setAlignment(Pos.CENTER_LEFT);

        Label statesLbl = lbl("Animation States:", 11, true);
        stateChecks = new ArrayList<>();
        FlowPane statesPane = new FlowPane(6, 4);
        statesPane.setStyle(
                "-fx-background-color: #0f0f1e; -fx-padding: 8;" +
                "-fx-border-color: #3a3a6a; -fx-border-radius: 4;");
        for (PlayerAnimator.State s : PlayerAnimator.State.values()) {
            CheckBox cb = new CheckBox(s.name());
            cb.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 10;");
            cb.setOnAction(e -> syncStates());
            stateChecks.add(cb);
            statesPane.getChildren().add(cb);
        }

        Button allBtn  = btn("All",    "#1e3a5f");
        Button noneBtn = btn("None",   "#3a1e2e");
        Button saveBtn = btn("💾 Save", "#1e5f3a");
        allBtn.setOnAction(e  -> { stateChecks.forEach(c -> c.setSelected(true));  syncStates(); });
        noneBtn.setOnAction(e -> { stateChecks.forEach(c -> c.setSelected(false)); syncStates(); });
        saveBtn.setOnAction(e -> save());

        ScrollPane statesScroll = new ScrollPane(statesPane);
        statesScroll.setFitToWidth(true);
        statesScroll.setStyle("-fx-background: #0f0f1e; -fx-background-color: #0f0f1e;");
        statesScroll.setPrefHeight(180);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        VBox cfgCol = vbox(8,
                cfgTitle, nameRow, typeRow, tintRow,
                statesLbl, new HBox(6, allBtn, noneBtn), statesScroll,
                saveBtn, statusLabel);
        cfgCol.setPrefWidth(320);

        // ── Right column: combat preview ──────────────────────────────────────
        Label previewTitle = lbl("Combat Preview", 13, true);
        Button pauseBtn    = new Button("⏸ Pause");
        Button stopBtn     = new Button("⏹ Stop");
        Button stepBackBtn = new Button("◀ Step");
        Button stepFwdBtn  = new Button("▶ Step");
        String btnBase = "-fx-text-fill: white; -fx-background-radius: 4; -fx-font-size: 11; -fx-padding: 3 12 3 12;";
        pauseBtn.setStyle("-fx-background-color: #1e3a5f;" + btnBase);
        stopBtn.setStyle("-fx-background-color: #7b241c;" + btnBase);
        stepBackBtn.setStyle("-fx-background-color: #3a3a5f;" + btnBase);
        stepFwdBtn.setStyle("-fx-background-color: #3a3a5f;" + btnBase);
        stepBackBtn.setDisable(true);
        stepFwdBtn.setDisable(true);
        stepBackBtn.setTooltip(new Tooltip("Step back 50 ms (pause first)"));
        stepFwdBtn.setTooltip(new Tooltip("Step forward 50 ms (pause first)"));

        pauseBtn.setOnAction(e -> {
            paused = !paused;
            pauseBtn.setText(paused ? "▶ Play" : "⏸ Pause");
            pauseBtn.setStyle("-fx-background-color: " + (paused ? "#1e5f3a" : "#1e3a5f") + ";" + btnBase);
            stepBackBtn.setDisable(!paused);
            stepFwdBtn.setDisable(!paused);
        });
        stopBtn.setOnAction(e -> {
            paused = true;
            pauseBtn.setText("▶ Play");
            pauseBtn.setStyle("-fx-background-color: #1e5f3a;" + btnBase);
            stepBackBtn.setDisable(false);
            stepFwdBtn.setDisable(false);
            resetCombat();
        });
        stepBackBtn.setOnAction(e -> { if (paused && frozenNs > STEP_NS) frozenNs -= STEP_NS; });
        stepFwdBtn.setOnAction(e  -> { if (paused) frozenNs += STEP_NS; });

        CheckBox faceCheck = new CheckBox("Fight Each Other");
        faceCheck.setSelected(fightFaceEachOther);
        faceCheck.setStyle("-fx-text-fill: #c8c8e0; -fx-font-size: 11;");
        faceCheck.setTooltip(new Tooltip("Keep both fighters facing each other at all times"));
        faceCheck.setOnAction(e -> fightFaceEachOther = faceCheck.isSelected());

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox previewTitleRow = new HBox(8, previewTitle, faceCheck, titleSpacer, stepBackBtn, stepFwdBtn, pauseBtn, stopBtn);
        previewTitleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Canvas covers only the combat area (top 78%). Floor Region sits below it.
        // This avoids any canvas-transparency tricks — the CSS Region is simply stacked
        // beneath the canvas in a VBox, and is always visible.
        int combatH = (int) Math.round(760 * 0.78);   // 593 px
        int floorH  = 760 - combatH;                   // 167 px
        previewCanvas = new Canvas(840, combatH);

        Region floorStrip = new Region();
        floorStrip.getStyleClass().add("combat-floor");
        floorStrip.setMinSize(840, floorH);
        floorStrip.setMaxSize(840, floorH);
        floorStrip.setPrefSize(840, floorH);

        StackPane combatArea = new StackPane(previewCanvas);
        combatArea.setStyle("-fx-background-color: #0f0f1e;");
        combatArea.setMinSize(840, combatH);
        combatArea.setMaxSize(840, combatH);

        VBox innerBox = new VBox(0, combatArea, floorStrip);
        innerBox.setMinSize(840, 760);
        innerBox.setPrefSize(840, 760);

        StackPane canvasBox = new StackPane(innerBox);
        canvasBox.setStyle("-fx-background-color: #0f0f1e;");
        HBox.setHgrow(canvasBox, Priority.ALWAYS);
        VBox.setVgrow(canvasBox, Priority.ALWAYS);

        // Fighter selector dropdowns
        Label f1Lbl = lbl("Fighter 1 (left):", 10, true);
        fightLeftCombo = new ComboBox<>();
        fightLeftCombo.getStyleClass().add("combo-dark");
        fightLeftCombo.setMaxWidth(Double.MAX_VALUE);
        fightLeftCombo.setOnAction(e -> { if (!suppressComboEvents) resetCombat(); });

        Label f2Lbl = lbl("Fighter 2 (right):", 10, true);
        fightRightCombo = new ComboBox<>();
        fightRightCombo.getStyleClass().add("combo-dark");
        fightRightCombo.setMaxWidth(Double.MAX_VALUE);
        fightRightCombo.setOnAction(e -> { if (!suppressComboEvents) resetCombat(); });

        Button resetBtn = btn("⚔ Reset Fight", "#2e1a5f");
        resetBtn.setMaxWidth(Double.MAX_VALUE);
        resetBtn.setOnAction(e -> resetCombat());

        Label selectorHint = new Label("Select any two sprites\nto fight each other.");
        selectorHint.setStyle("-fx-text-fill: #606080; -fx-font-size: 9;");

        // Speed slider — maps 0→100 to attack interval 4000 ms (slow) → 300 ms (fast)
        Label speedLbl = lbl("Fight Speed", 10, true);
        Slider speedSlider = new Slider(0, 100, 50);
        speedSlider.setShowTickMarks(false);
        speedSlider.setMaxWidth(Double.MAX_VALUE);
        speedSlider.setStyle("-fx-control-inner-background: #0f0f1e;");
        Label speedValueLbl = new Label("Normal");
        speedValueLbl.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 9;");

        // Initialize from current attackIntervalMs (default 2200 → ~50 on 0–100 scale)
        speedSlider.setValue(100.0 * (4000 - attackIntervalMs) / (4000 - 300));
        speedSlider.valueProperty().addListener((obs, oldV, newV) -> {
            double t = newV.doubleValue() / 100.0;
            attackIntervalMs = Math.round(4000 - t * (4000 - 300));
            if (t < 0.25)      speedValueLbl.setText("Very Slow");
            else if (t < 0.45) speedValueLbl.setText("Slow");
            else if (t < 0.60) speedValueLbl.setText("Normal");
            else if (t < 0.80) speedValueLbl.setText("Fast");
            else               speedValueLbl.setText("Very Fast");
        });
        HBox speedRow = new HBox(6, speedSlider, speedValueLbl);
        speedRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(speedSlider, Priority.ALWAYS);

        VBox selectorCol = new VBox(6, f1Lbl, fightLeftCombo, f2Lbl, fightRightCombo, resetBtn,
                speedLbl, speedRow, selectorHint);
        selectorCol.setPadding(new Insets(4));
        selectorCol.setPrefWidth(220);
        selectorCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 4;");

        HBox previewRow = new HBox(6, canvasBox, selectorCol);
        VBox.setVgrow(previewRow, Priority.ALWAYS);

        Label hint = new Label(
                "Each fighter attacks on its own timer. Damage lands ~500 ms into the animation. " +
                "HP bars shown above each fighter. KO → 3 s pause → rematch.");
        hint.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 10;");
        hint.setWrapText(true);

        VBox previewCol = vbox(8, previewTitleRow, previewRow, hint);
        HBox.setHgrow(previewCol, Priority.ALWAYS);

        // ── Layout root ───────────────────────────────────────────────────────
        HBox root = new HBox(8, leftCol, cfgCol, previewCol);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");

        syncFightCombos();
        startPreview();

        if (!sprites.isEmpty()) spriteList.getSelectionModel().select(0);

        return root;
    }

    // ── Roster actions ────────────────────────────────────────────────────────

    private void addNew(boolean isNpc) {
        Color tint  = isNpc ? Color.web("#e94560") : Color.web("#53c0f0");
        String name = isNpc ? "New NPC" : "New Hero";
        SpriteChar sc = new SpriteChar(name, isNpc, tint);
        sc.states.add(PlayerAnimator.State.IDLE);
        sprites.add(sc);
        refreshList();
        spriteList.getSelectionModel().select(sprites.size() - 1);
    }

    private void deleteSelected() {
        if (selected == null) return;
        sprites.remove(selected);
        selected = null;
        clearForm();
        refreshList();
        if (!sprites.isEmpty()) spriteList.getSelectionModel().select(0);
    }

    private void loadIntoForm(SpriteChar sc) {
        selected = sc;
        nameField.setText(sc.name);
        colorPicker.setValue(sc.tint);
        (sc.isNpc ? npcRb : heroRb).setSelected(true);
        for (CheckBox cb : stateChecks) {
            try { cb.setSelected(sc.states.contains(PlayerAnimator.State.valueOf(cb.getText()))); }
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
        // Fighter.sc points to the same SpriteChar, so state changes take effect immediately.
    }

    private void refreshList() {
        String selName = selected != null ? selected.name : null;
        spriteList.getItems().clear();
        for (SpriteChar sc : sprites)
            spriteList.getItems().add((sc.isNpc ? "👾 " : "🧍 ") + sc.name);
        if (selName != null) {
            for (int i = 0; i < sprites.size(); i++) {
                if (sprites.get(i).name.equals(selName)) {
                    spriteList.getSelectionModel().select(i);
                    break;
                }
            }
        }
        syncFightCombos();
    }

    private void syncFightCombos() {
        if (fightLeftCombo == null || fightRightCombo == null) return;
        String prevL = fightLeftCombo.getValue();
        String prevR = fightRightCombo.getValue();
        fightLeftCombo.getItems().clear();
        fightRightCombo.getItems().clear();
        for (SpriteChar sc : sprites) {
            fightLeftCombo.getItems().add(sc.name);
            fightRightCombo.getItems().add(sc.name);
        }
        suppressComboEvents = true;
        if (prevL != null && fightLeftCombo.getItems().contains(prevL))
            fightLeftCombo.setValue(prevL);
        else if (!sprites.isEmpty())
            fightLeftCombo.setValue(sprites.get(0).name);

        if (prevR != null && fightRightCombo.getItems().contains(prevR))
            fightRightCombo.setValue(prevR);
        else if (sprites.size() > 1)
            fightRightCombo.setValue(sprites.get(1).name);
        else if (!sprites.isEmpty())
            fightRightCombo.setValue(sprites.get(0).name);
        suppressComboEvents = false;
    }

    // ── Combat engine ─────────────────────────────────────────────────────────

    /** Tears down fighters and rebuilds them from combo selection. */
    private void resetCombat() {
        leftFighter        = null;
        rightFighter       = null;
        resolvedLeftName   = null;
        resolvedRightName  = null;
        koTimeMs           = 0;
        koText             = "";
        koFighter          = null;
        healthResetDone    = false;
        frozenNs           = 0;
        dmgNumbers.clear();
    }

    /** Ensures fighters exist for the currently selected combo names; rebuilds on change. */
    private void resolveFighters(long nowMs) {
        String lName = fightLeftCombo  != null ? fightLeftCombo.getValue()  : null;
        String rName = fightRightCombo != null ? fightRightCombo.getValue() : null;

        // Fall back
        if (lName == null && !sprites.isEmpty()) lName = sprites.get(0).name;
        if (rName == null && sprites.size() > 1)  rName = sprites.get(1).name;

        boolean changed = !java.util.Objects.equals(lName, resolvedLeftName)
                       || !java.util.Objects.equals(rName, resolvedRightName);
        if (changed) {
            resolvedLeftName  = lName;
            resolvedRightName = rName;
            dmgNumbers.clear();
            koTimeMs = 0;
            koText   = "";
            SpriteChar lsc = byName(lName);
            SpriteChar rsc = byName(rName);
            leftFighter  = lsc != null ? new Fighter(lsc, false, nowMs) : null;
            rightFighter = rsc != null ? new Fighter(rsc, true,  nowMs) : null;
        }
    }

    /** Advance combat logic for one fighter; opponent is the target. */
    private void updateFighter(Fighter f, Fighter opponent, long nowMs, long nowNs,
                               double attackerCx, double targetCx, double floorY) {
        if (f.isKO() || opponent == null) return;

        // If stunned (reacting to a hit), hold the current pose
        if (f.isStunned(nowMs)) return;

        // Return to idle after stun
        if (f.anim.getCurrentState() == PlayerAnimator.State.GOTHIT01
                || f.anim.getCurrentState() == PlayerAnimator.State.GOTHIT02
                || f.anim.getCurrentState() == PlayerAnimator.State.GOTHIT03
                || f.anim.getCurrentState() == PlayerAnimator.State.KNOCKED_DOWN) {
            f.anim.forceState(f.idleState(), nowMs);
        }

        // Process pending damage
        if (f.hitPending && nowMs >= f.hitTimeMs) {
            f.hitPending = false;
            int dmg = damageFor(f.pendingAttack);
            if (!opponent.isKO()) {
                opponent.hp = Math.max(0, opponent.hp - dmg);
                String label = attackLabel(f.pendingAttack);
                // Blue number on the attacker (scored the hit)
                dmgNumbers.add(new DamageNumber(
                        attackerCx + rng.nextInt(20) - 10,
                        floorY - 70 - rng.nextInt(15),
                        dmg, label, Color.web("#ffdd00"), nowNs));
                // Red number on the receiver (took the hit)
                dmgNumbers.add(new DamageNumber(
                        targetCx + rng.nextInt(20) - 10,
                        floorY - 85 - rng.nextInt(20),
                        dmg, label, Color.web("#ff3344"), nowNs));
                // Put opponent in stun
                if (!opponent.isKO()) {
                    // KNOCKED_DOWN only triggers below 10 HP; otherwise pick hit zone
                    PlayerAnimator.State hitState;
                    if (opponent.hp < 10 && opponent.hasState(PlayerAnimator.State.KNOCKED_DOWN)) {
                        hitState = PlayerAnimator.State.KNOCKED_DOWN;
                    } else {
                        hitState = hitStateFor(f.pendingAttack, opponent);
                    }
                    if (hitState != null) {
                        opponent.anim.forceState(hitState, nowMs);
                        opponent.stunEndMs = nowMs + STUN_MS;
                    }
                } else {
                    // KO! — play KNOCKED_DOWN (hold on last frame), then kip-up
                    boolean hasKnocked = opponent.hasState(PlayerAnimator.State.KNOCKED_DOWN);
                    PlayerAnimator.State deadState = hasKnocked
                            ? PlayerAnimator.State.KNOCKED_DOWN : opponent.idleState();
                    opponent.anim.forceState(deadState, nowMs);
                    if (hasKnocked) opponent.anim.setHoldLastFrame(true); // hold lying flat after play-through
                    koTimeMs        = nowMs;
                    koText          = f.sc.name + " wins!";
                    koFighter       = opponent;
                    healthResetDone = false;
                    // Winner relaxes — cancel any pending attack and return to idle
                    f.hitPending    = false;
                    f.anim.forceState(f.idleState(), nowMs);
                }
            }
        }

        // Launch next attack
        if (!f.hitPending && !opponent.isKO() && nowMs >= f.nextAttkMs) {
            List<PlayerAnimator.State> attacks = f.attackStates();
            if (!attacks.isEmpty()) {
                f.pendingAttack = attacks.get(rng.nextInt(attacks.size()));
                f.anim.forceState(f.pendingAttack, nowMs);
                f.hitPending  = true;
                f.hitTimeMs   = nowMs + HIT_DELAY_MS;
                f.nextAttkMs  = nowMs + attackIntervalMs;
            }
        }
    }

    // ── Preview render ────────────────────────────────────────────────────────

    private void startPreview() {
        previewTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (paused) {
                    if (frozenNs == 0) frozenNs = now;
                    drawPreview(frozenNs);
                } else {
                    frozenNs = now;
                    drawPreview(now);
                }
            }
        };
        previewTimer.start();
    }

    private void drawPreview(long nowNs) {
        long nowMs = nowNs / 1_000_000L;

        GraphicsContext gc = previewCanvas.getGraphicsContext2D();
        double w      = previewCanvas.getWidth();
        double h      = previewCanvas.getHeight();
        // Canvas covers only the combat area — its bottom edge IS the floor line.
        double floorY = h;

        // Background
        gc.setFill(Color.web("#0f0f1e"));
        gc.fillRect(0, 0, w, h);

        // Resolve fighters
        resolveFighters(nowMs);
        if (leftFighter == null && rightFighter == null) {
            gc.setFill(Color.web("#606080"));
            gc.setFont(javafx.scene.text.Font.font("System", 13));
            gc.fillText("Select two sprites to fight.", w / 2 - 90, h / 2);
            return;
        }

        // Fighter canvas positions — close enough to exchange blows
        double leftCx  = w * 0.45;
        double rightCx = w * 0.55;

        // KO pause → rematch (freeze timers while paused)
        if (paused && koTimeMs > 0) koTimeMs = nowMs - Math.min(nowMs - koTimeMs, KO_RESET_MS - 100);
        if (koTimeMs > 0 && nowMs - koTimeMs > KO_RESET_MS) {
            // Rematch
            long stagger = 0;
            if (leftFighter  != null) { leftFighter.hp  = MAX_HP; leftFighter.hitPending  = false; leftFighter.kipUpTriggered = false; leftFighter.nextAttkMs  = nowMs + (stagger += 400); leftFighter.anim.forceState(leftFighter.idleState(), nowMs); }
            if (rightFighter != null) { rightFighter.hp = MAX_HP; rightFighter.hitPending = false; rightFighter.kipUpTriggered = false; rightFighter.nextAttkMs = nowMs + (stagger += 600); rightFighter.anim.forceState(rightFighter.idleState(), nowMs); }
            koTimeMs  = 0;
            koText    = "";
            koFighter = null;
            dmgNumbers.clear();
        }

        // Update combat (only when not paused or in KO pause)
        if (!paused && koTimeMs == 0) {
            updateFighter(leftFighter,  rightFighter, nowMs, nowNs, leftCx,  rightCx, floorY);
            updateFighter(rightFighter, leftFighter,  nowMs, nowNs, rightCx, leftCx,  floorY);
        }

        // ── HP bars ───────────────────────────────────────────────────────────
        double barW = 110, barH = 10;
        if (leftFighter != null) {
            drawHpBar(gc, leftCx - barW / 2, floorY - 190, barW, barH,
                    leftFighter.hp, leftFighter.sc.tint, leftFighter.sc.name);
        }
        if (rightFighter != null) {
            drawHpBar(gc, rightCx - barW / 2, floorY - 190, barW, barH,
                    rightFighter.hp, rightFighter.sc.tint, rightFighter.sc.name);
        }

        // ── VS label ─────────────────────────────────────────────────────────
        gc.setFill(Color.web("#f0a030"));
        gc.setFont(javafx.scene.text.Font.font("System",
                javafx.scene.text.FontWeight.BOLD, 22));
        gc.fillText("VS", w / 2 - 14, floorY - 210);

        // ── Left fighter ──────────────────────────────────────────────────────
        if (leftFighter != null) {
            if (fightFaceEachOther) leftFighter.anim.setFacingRight(true);
            shadow(gc, leftCx, floorY);
            leftFighter.anim.draw(gc, leftCx, floorY, leftFighter.sc.tint, SPRITE_SCALE, nowMs);
            stateTag(gc, leftFighter.anim.getCurrentState().name(), leftCx, floorY, leftFighter.sc.tint);
        }

        // ── Right fighter (faces left) ────────────────────────────────────────
        if (rightFighter != null) {
            if (fightFaceEachOther) rightFighter.anim.setFacingRight(false);
            shadow(gc, rightCx, floorY);
            rightFighter.anim.draw(gc, rightCx, floorY, rightFighter.sc.tint, SPRITE_SCALE, nowMs);
            stateTag(gc, rightFighter.anim.getCurrentState().name(), rightCx, floorY, rightFighter.sc.tint);
        }

        // ── Damage numbers ────────────────────────────────────────────────────
        dmgNumbers.removeIf(d -> d.dead(nowNs));
        gc.setFont(javafx.scene.text.Font.font("System",
                javafx.scene.text.FontWeight.BOLD, 16));
        for (DamageNumber d : dmgNumbers) {
            double alpha = d.alpha(nowNs);
            double dy    = d.currentY(nowNs);
            gc.setFill(d.color.deriveColor(0, 1, 1.2, alpha));
            gc.fillText(d.text, d.x - d.text.length() * 4.2, dy);
        }

        // ── Kip-up recovery during KO pause ──────────────────────────────────
        if (koTimeMs > 0 && koFighter != null) {
            long elapsed = nowMs - koTimeMs;
            PlayerAnimator.State cs = koFighter.anim.getCurrentState();
            // Any fighter with KNOCKED_DOWN can kip-up — add KIP_UP implicitly if needed
            boolean canKipUp = koFighter.hasState(PlayerAnimator.State.KIP_UP)
                             || koFighter.hasState(PlayerAnimator.State.KNOCKED_DOWN);
            if (!koFighter.kipUpTriggered && elapsed >= KIP_UP_DELAY_MS
                    && cs == PlayerAnimator.State.KNOCKED_DOWN) {
                koFighter.anim.setHoldLastFrame(false);
                koFighter.anim.forceState(PlayerAnimator.State.KIP_UP, nowMs);
                koFighter.kipUpTriggered = true;
                // Push nextAttkMs past the kip-up so updateFighter can't interrupt the animation
                koFighter.nextAttkMs = nowMs + KIP_UP_DURATION_MS + 2000;
            }
            // Reset both fighters to full health when the loser is ready to recover
            if (!healthResetDone && elapsed >= KIP_UP_DELAY_MS
                    && (koFighter.kipUpTriggered || !canKipUp)) {
                if (leftFighter  != null) leftFighter.hp  = MAX_HP;
                if (rightFighter != null) rightFighter.hp = MAX_HP;
                healthResetDone = true;
            }
            // Once the kip-up animation has run its course, resume combat
            if (koFighter.kipUpTriggered
                    && cs == PlayerAnimator.State.KIP_UP
                    && elapsed >= KIP_UP_DELAY_MS + KIP_UP_DURATION_MS) {
                koFighter.anim.forceState(koFighter.idleState(), nowMs);
                koFighter.nextAttkMs = nowMs + 1200;
                koFighter.hitPending = false;
                // Clear the KO state so both fighters resume immediately
                koTimeMs  = 0;
                koText    = "";
                koFighter = null;
            }
        }

        // ── KO banner ────────────────────────────────────────────────────────
        if (koTimeMs > 0) {
            gc.setFill(Color.color(0, 0, 0, 0.5));
            gc.fillRect(0, floorY - 270, w, 60);
            gc.setFill(Color.web("#f0a030"));
            gc.setFont(javafx.scene.text.Font.font("System",
                    javafx.scene.text.FontWeight.BOLD, 28));
            gc.fillText("K  O !", w / 2 - 36, floorY - 248);
            gc.setFill(Color.web("#e0e0e0"));
            gc.setFont(javafx.scene.text.Font.font("System",
                    javafx.scene.text.FontWeight.BOLD, 14));
            gc.fillText(koText, w / 2 - koText.length() * 4.5, floorY - 226);
        }
    }

    private void drawHpBar(GraphicsContext gc, double x, double y,
                           double w, double h, int hp, Color tint, String name) {
        // Background
        gc.setFill(Color.color(0.1, 0.1, 0.15, 0.8));
        gc.fillRoundRect(x - 1, y - 1, w + 2, h + 2, 4, 4);
        // HP fill
        double pct = Math.max(0, hp / (double) MAX_HP);
        Color barColor = pct > 0.5 ? Color.web("#50c050")
                       : pct > 0.25 ? Color.web("#f0a030")
                       : Color.web("#e94560");
        gc.setFill(barColor);
        gc.fillRoundRect(x, y, w * pct, h, 4, 4);
        // Border
        gc.setStroke(tint.deriveColor(0, 1, 0.7, 1));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, h, 4, 4);
        // HP text
        gc.setFill(Color.web("#e0e0e0"));
        gc.setFont(javafx.scene.text.Font.font("System", 9));
        gc.fillText(name + "  " + hp + " HP", x, y - 3);
    }

    private void shadow(GraphicsContext gc, double cx, double floorY) {
        gc.setFill(Color.color(0, 0, 0, 0.35));
        gc.fillOval(cx - 16, floorY - 3, 32, 8);
    }

    private void stateTag(GraphicsContext gc, String stateName, double cx, double floorY, Color tint) {
        gc.setFill(tint.deriveColor(0, 1.0, 1.5, 1.0));
        gc.setFont(javafx.scene.text.Font.font("System", 9));
        gc.fillText(stateName, cx - stateName.length() * 2.5, floorY - 6);
    }

    // ── Combat helpers ────────────────────────────────────────────────────────

    /** Returns the appropriate GOTHIT state based on which body zone the attack targets. */
    private static PlayerAnimator.State hitStateFor(PlayerAnimator.State attack, Fighter target) {
        PlayerAnimator.State zone = switch (attack) {
            // Head hits
            case UPPERCUT, HEAD_KICK, HAYMAKER            -> PlayerAnimator.State.GOTHIT03;
            // Body/gut hits
            case PUNCH, CROSS, HOOK, BODY_KICK, SHOOT     -> PlayerAnimator.State.GOTHIT02;
            // Leg hits
            case LOW_KICK, SPINNING_BACK_KICK, SIDE_KICK  -> PlayerAnimator.State.GOTHIT01;
            default                                        -> PlayerAnimator.State.GOTHIT01;
        };
        // Fall back if the target doesn't have the preferred zone state
        if (target.hasState(zone)) return zone;
        if (target.hasState(PlayerAnimator.State.GOTHIT01)) return PlayerAnimator.State.GOTHIT01;
        return null;
    }

    private int damageFor(PlayerAnimator.State s) {
        if (s == null) return 0;
        return switch (s) {
            case PUNCH, CROSS, HOOK          -> rng.nextInt(8)  + 5;   //  5–12
            case UPPERCUT                    -> rng.nextInt(10) + 10;  // 10–19
            case HAYMAKER                    -> rng.nextInt(12) + 18;  // 18–29
            case HEAD_KICK, BODY_KICK        -> rng.nextInt(10) + 10;  // 10–19
            case LOW_KICK                    -> rng.nextInt(8)  + 7;   //  7–14
            case SPINNING_BACK_KICK,
                 SIDE_KICK                   -> rng.nextInt(12) + 14;  // 14–25
            case SHOOT                       -> rng.nextInt(15) + 12;  // 12–26
            default                          -> 0;
        };
    }

    private static String attackLabel(PlayerAnimator.State s) {
        if (s == null) return "";
        return switch (s) {
            case PUNCH     -> "Jab";
            case CROSS     -> "Cross";
            case HOOK      -> "Hook";
            case UPPERCUT  -> "Uppercut";
            case HAYMAKER  -> "Haymaker";
            case HEAD_KICK -> "Head Kick";
            case LOW_KICK  -> "Low Kick";
            case BODY_KICK -> "Body Kick";
            case SPINNING_BACK_KICK -> "Spin Kick";
            case SIDE_KICK -> "Side Kick";
            case SHOOT     -> "Shot";
            default        -> s.name();
        };
    }

    private SpriteChar byName(String name) {
        if (name == null) return null;
        return sprites.stream().filter(s -> s.name.equals(name)).findFirst().orElse(null);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void save() {
        try {
            Files.createDirectories(SAVE_FILE.getParent());
            ObjectMapper om = new ObjectMapper();
            ArrayNode arr = om.createArrayNode();
            for (SpriteChar sc : sprites) {
                ObjectNode n = om.createObjectNode();
                n.put("name",  sc.name);
                n.put("isNpc", sc.isNpc);
                n.put("tint",  hexOf(sc.tint));
                ArrayNode sa = n.putArray("states");
                for (PlayerAnimator.State s : sc.states) sa.add(s.name());
                arr.add(n);
            }
            Files.writeString(SAVE_FILE, om.writerWithDefaultPrettyPrinter().writeValueAsString(arr));
            status("✓ Saved " + sprites.size() + " sprite(s).", true);
        } catch (Exception e) {
            status("✗ Save failed: " + e.getMessage(), false);
        }
    }

    private void load() {
        sprites.clear();
        if (!Files.exists(SAVE_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode n : om.readTree(SAVE_FILE.toFile())) {
                SpriteChar sc = new SpriteChar(
                        n.path("name").asText("Unnamed"),
                        n.path("isNpc").asBoolean(false),
                        parseColor(n.path("tint").asText("#e0e0ff")));
                for (JsonNode sn : n.path("states")) {
                    try { sc.states.add(PlayerAnimator.State.valueOf(sn.asText())); }
                    catch (IllegalArgumentException ignored) {}
                }
                sprites.add(sc);
            }
        } catch (Exception ignored) {}
    }

    private void ensureDefaults() {
        if (sprites.stream().noneMatch(s -> !s.isNpc)) {
            SpriteChar hero = new SpriteChar("Hero Sprite", false, Color.web("#53c0f0"));
            for (PlayerAnimator.State s : new PlayerAnimator.State[]{
                    PlayerAnimator.State.IDLE, PlayerAnimator.State.RUN,
                    PlayerAnimator.State.JUMP, PlayerAnimator.State.FALL,
                    PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS,
                    PlayerAnimator.State.HOOK, PlayerAnimator.State.UPPERCUT,
                    PlayerAnimator.State.HAYMAKER, PlayerAnimator.State.HEAD_KICK,
                    PlayerAnimator.State.LOW_KICK, PlayerAnimator.State.BODY_KICK,
                    PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02, PlayerAnimator.State.GOTHIT03,
                    PlayerAnimator.State.KNOCKED_DOWN, PlayerAnimator.State.KIP_UP })
                hero.states.add(s);
            sprites.add(0, hero);
        }
        if (sprites.stream().noneMatch(s -> s.isNpc)) {
            SpriteChar npc = new SpriteChar("Default NPC", true, Color.web("#e94560"));
            for (PlayerAnimator.State s : new PlayerAnimator.State[]{
                    PlayerAnimator.State.IDLE,
                    PlayerAnimator.State.PUNCH, PlayerAnimator.State.CROSS,
                    PlayerAnimator.State.HEAD_KICK, PlayerAnimator.State.LOW_KICK,
                    PlayerAnimator.State.GOTHIT01, PlayerAnimator.State.GOTHIT02, PlayerAnimator.State.GOTHIT03,
                    PlayerAnimator.State.KNOCKED_DOWN, PlayerAnimator.State.KIP_UP })
                npc.states.add(s);
            sprites.add(npc);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private Label lbl(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: " + size + ";" +
                   (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private Button btn(String text, String bg) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white;" +
                   "-fx-background-radius: 4; -fx-font-size: 11; -fx-padding: 4 10 4 10;");
        return b;
    }

    private VBox vbox(int spacing, Node... children) {
        VBox v = new VBox(spacing, children);
        v.setPadding(new Insets(8));
        v.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return v;
    }

    private void status(String msg, boolean ok) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: " + (ok ? "#50c050" : "#e94560") + "; -fx-font-size: 11;");
    }

    private static String hexOf(Color c) {
        return String.format("#%02x%02x%02x",
                (int)(c.getRed() * 255), (int)(c.getGreen() * 255), (int)(c.getBlue() * 255));
    }

    private static Color parseColor(String hex) {
        try { return Color.web(hex); } catch (Exception e) { return Color.web("#e0e0ff"); }
    }
}
