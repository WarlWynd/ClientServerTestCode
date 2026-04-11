package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import javafx.util.converter.IntegerStringConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Game Mechanics Panel — define combat attack rules as data, not code.
 *
 * Each attack rule binds an animation state to a damage range, hit zone,
 * and stun response. Global timing values control the pace of all combat.
 *
 * Saved to: client/src/main/resources/graphics/sprites/game-mechanics.json
 */
public class GameMechanicsPanel {

    public static final Path SAVE_FILE =
            Paths.get("client/src/main/resources/graphics/sprites/game-mechanics.json");

    // ── Hit zones ─────────────────────────────────────────────────────────────

    public enum HitZone { HEAD, BODY, LEGS, ANY }

    // ── Stun states (which animation plays on the receiver) ───────────────────

    private static final List<String> STUN_STATES = List.of(
            "GOTHIT01", "GOTHIT02", "GOTHIT03", "KNOCKED_DOWN", "NONE"
    );

    // ── Attack states (the states that represent an attack animation) ─────────

    private static final List<String> ATTACK_STATES = Arrays.stream(PlayerAnimator.State.values())
            .map(Enum::name)
            .filter(n -> !List.of(
                "IDLE","RUN","JUMP","FALL","GOTHIT01","GOTHIT02","GOTHIT03",
                "KNOCKED_DOWN","CROUCH","SNEAK","CLIMB","PRONE","ROLL","SWIM",
                "KIP_UP","FRONT_FLIP","CRAWL","QUAD_IDLE","TROT","GALLOP","QUAD_DEATH",
                "STAFF_IDLE","SWORD_1H_IDLE","SWORD_2H_IDLE","AXE_1H_IDLE","AXE_2H_IDLE",
                "DAGGER_IDLE","MORNING_STAR_IDLE","BOW_IDLE"
            ).contains(n))
            .collect(Collectors.toList());

    // ── Data model ────────────────────────────────────────────────────────────

    public static class AttackRule {
        final StringProperty  state      = new SimpleStringProperty("PUNCH");
        final StringProperty  label      = new SimpleStringProperty("Punch");
        final IntegerProperty minDamage  = new SimpleIntegerProperty(5);
        final IntegerProperty maxDamage  = new SimpleIntegerProperty(12);
        final StringProperty  hitZone    = new SimpleStringProperty("BODY");
        final IntegerProperty stunMs     = new SimpleIntegerProperty(0);
        final StringProperty  stunState  = new SimpleStringProperty("GOTHIT01");

        AttackRule() {}

        AttackRule(String state, String label, int minDmg, int maxDmg,
                   String hitZone, int stunMs, String stunState) {
            this.state.set(state);
            this.label.set(label);
            this.minDamage.set(minDmg);
            this.maxDamage.set(maxDmg);
            this.hitZone.set(hitZone);
            this.stunMs.set(stunMs);
            this.stunState.set(stunState);
        }
    }

    // ── Default display colors (readable statically by combat preview) ─────────
    public static final String DEFAULT_DAMAGE_COLOR = "#ff2222";
    public static final String DEFAULT_HEAL_COLOR   = "#22cc44";

    // ── State ─────────────────────────────────────────────────────────────────

    private final ObservableList<AttackRule> rules = FXCollections.observableArrayList();

    // Timing fields (backed by spinners)
    private Spinner<Integer> attackIntervalSpinner;
    private Spinner<Integer> stunDurationSpinner;
    private Spinner<Integer> koRecoverySpinner;

    // Display color pickers
    private ColorPicker damageColorPicker;
    private ColorPicker healColorPicker;

    private Label statusLabel;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        load();

        // ── Global Timing row ─────────────────────────────────────────────────
        attackIntervalSpinner = intSpinner(100, 10000, 1200);
        stunDurationSpinner   = intSpinner(0,   5000,  800);
        koRecoverySpinner     = intSpinner(500, 30000, 3000);

        HBox timingRow = new HBox(24,
                labeledSpinner("Attack Interval (ms)",  attackIntervalSpinner),
                labeledSpinner("Stun Duration (ms)",    stunDurationSpinner),
                labeledSpinner("KO Recovery (ms)",      koRecoverySpinner)
        );
        timingRow.setAlignment(Pos.CENTER_LEFT);
        timingRow.setPadding(new Insets(8, 10, 8, 10));
        timingRow.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");

        Label timingTitle = lbl("Global Timing", 12, true);
        VBox timingBox = new VBox(6, timingTitle, timingRow);
        timingBox.setPadding(new Insets(10));
        timingBox.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");

        // ── Display Colors ────────────────────────────────────────────────────
        damageColorPicker = new ColorPicker(Color.web(DEFAULT_DAMAGE_COLOR));
        damageColorPicker.setStyle("-fx-color-label-visible: false;");
        healColorPicker   = new ColorPicker(Color.web(DEFAULT_HEAL_COLOR));
        healColorPicker.setStyle("-fx-color-label-visible: false;");

        HBox colorsRow = new HBox(24,
                colorRow("Damage Number Color", damageColorPicker),
                colorRow("Heal Number Color",   healColorPicker)
        );
        colorsRow.setAlignment(Pos.CENTER_LEFT);
        colorsRow.setPadding(new Insets(8, 10, 8, 10));

        Label colorsTitle = lbl("Display Colors", 12, true);
        VBox colorsBox = new VBox(6, colorsTitle, colorsRow);
        colorsBox.setPadding(new Insets(10));
        colorsBox.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");

        // ── Attack rules table ────────────────────────────────────────────────
        TableView<AttackRule> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        // ── Toolbar ───────────────────────────────────────────────────────────
        Button addBtn    = btn("+ Add Rule",     "#1e8449");
        Button removeBtn = btn("− Remove",       "#7b241c");
        Button saveBtn   = btn("💾 Save",        "#0f3460");
        Button loadBtn   = btn("↺ Reload",       "#4a4a7a");

        addBtn.setMaxWidth(Double.MAX_VALUE);
        removeBtn.setMaxWidth(Double.MAX_VALUE);
        saveBtn.setMaxWidth(Double.MAX_VALUE);
        loadBtn.setMaxWidth(Double.MAX_VALUE);

        addBtn.setOnAction(e -> {
            rules.add(new AttackRule());
            table.getSelectionModel().selectLast();
            table.scrollTo(rules.size() - 1);
        });
        removeBtn.setOnAction(e -> {
            AttackRule sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) rules.remove(sel);
        });
        saveBtn.setOnAction(e -> save());
        loadBtn.setOnAction(e -> { load(); setStatus("Reloaded.", true); });

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        HBox toolbar = new HBox(8, addBtn, removeBtn, sp, loadBtn, saveBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 10, 8, 10));
        toolbar.setStyle("-fx-background-color: #16213e;");

        // ── Layout ────────────────────────────────────────────────────────────
        Label title = lbl("Attack Rules", 13, true);
        Label desc  = lbl("Each row maps an animation state to damage, hit zone, and stun response. " +
                          "These rules are used by any system that needs combat logic.", 11, false);
        desc.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");
        desc.setWrapText(true);

        VBox tableBox = new VBox(0, toolbar, table, statusLabel);
        VBox.setVgrow(table, Priority.ALWAYS);
        tableBox.setStyle("-fx-background-color: #1a1a2e;");

        VBox root = new VBox(10, timingBox, colorsBox, tableBox);
        VBox.setVgrow(tableBox, Priority.ALWAYS);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: #1a1a2e;");

        return root;
    }

    // ── Table ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private TableView<AttackRule> buildTable() {
        TableView<AttackRule> table = new TableView<>(rules);
        table.setEditable(true);
        table.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;");

        // State column
        TableColumn<AttackRule, String> stateCol = new TableColumn<>("State");
        stateCol.setPrefWidth(170);
        stateCol.setCellValueFactory(c -> c.getValue().state);
        stateCol.setCellFactory(ComboBoxTableCell.forTableColumn(
                FXCollections.observableArrayList(ATTACK_STATES)));
        stateCol.setOnEditCommit(e -> e.getRowValue().state.set(e.getNewValue()));

        // Label column
        TableColumn<AttackRule, String> labelCol = new TableColumn<>("Label");
        labelCol.setPrefWidth(130);
        labelCol.setCellValueFactory(c -> c.getValue().label);
        labelCol.setCellFactory(TextFieldTableCell.forTableColumn());
        labelCol.setOnEditCommit(e -> e.getRowValue().label.set(e.getNewValue()));

        // Min damage
        TableColumn<AttackRule, Integer> minCol = new TableColumn<>("Min Dmg");
        minCol.setPrefWidth(70);
        minCol.setCellValueFactory(c -> c.getValue().minDamage.asObject());
        minCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        minCol.setOnEditCommit(e -> {
            if (e.getNewValue() != null) e.getRowValue().minDamage.set(e.getNewValue());
        });

        // Max damage
        TableColumn<AttackRule, Integer> maxCol = new TableColumn<>("Max Dmg");
        maxCol.setPrefWidth(70);
        maxCol.setCellValueFactory(c -> c.getValue().maxDamage.asObject());
        maxCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        maxCol.setOnEditCommit(e -> {
            if (e.getNewValue() != null) e.getRowValue().maxDamage.set(e.getNewValue());
        });

        // Hit zone
        TableColumn<AttackRule, String> zoneCol = new TableColumn<>("Hit Zone");
        zoneCol.setPrefWidth(80);
        zoneCol.setCellValueFactory(c -> c.getValue().hitZone);
        zoneCol.setCellFactory(ComboBoxTableCell.forTableColumn(
                FXCollections.observableArrayList(
                        Arrays.stream(HitZone.values()).map(Enum::name).collect(Collectors.toList())
                )
        ));
        zoneCol.setOnEditCommit(e -> e.getRowValue().hitZone.set(e.getNewValue()));

        // Stun Ms
        TableColumn<AttackRule, Integer> stunMsCol = new TableColumn<>("Stun Override (ms)");
        stunMsCol.setPrefWidth(130);
        stunMsCol.setCellValueFactory(c -> c.getValue().stunMs.asObject());
        stunMsCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        stunMsCol.setOnEditCommit(e -> {
            if (e.getNewValue() != null) e.getRowValue().stunMs.set(e.getNewValue());
        });

        // Stun state (which "got hit" state plays on receiver)
        TableColumn<AttackRule, String> stunStateCol = new TableColumn<>("Stun State");
        stunStateCol.setPrefWidth(110);
        stunStateCol.setCellValueFactory(c -> c.getValue().stunState);
        stunStateCol.setCellFactory(ComboBoxTableCell.forTableColumn(
                FXCollections.observableArrayList(STUN_STATES)));
        stunStateCol.setOnEditCommit(e -> e.getRowValue().stunState.set(e.getNewValue()));

        table.getColumns().addAll(stateCol, labelCol, minCol, maxCol, zoneCol, stunMsCol, stunStateCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        styleTable(table);
        return table;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        rules.clear();
        if (!Files.exists(SAVE_FILE)) {
            loadDefaults();
            return;
        }
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(SAVE_FILE.toFile());

            if (root.has("timing")) {
                JsonNode t = root.get("timing");
                if (attackIntervalSpinner != null)
                    attackIntervalSpinner.getValueFactory().setValue(t.path("attackIntervalMs").asInt(1200));
                if (stunDurationSpinner != null)
                    stunDurationSpinner.getValueFactory().setValue(t.path("stunDurationMs").asInt(800));
                if (koRecoverySpinner != null)
                    koRecoverySpinner.getValueFactory().setValue(t.path("koRecoveryMs").asInt(3000));
            }
            if (root.has("display")) {
                JsonNode d = root.get("display");
                if (damageColorPicker != null)
                    damageColorPicker.setValue(Color.web(d.path("damageColor").asText(DEFAULT_DAMAGE_COLOR)));
                if (healColorPicker != null)
                    healColorPicker.setValue(Color.web(d.path("healColor").asText(DEFAULT_HEAL_COLOR)));
            }

            JsonNode attacks = root.path("attacks");
            if (attacks.isArray()) {
                for (JsonNode n : attacks) {
                    rules.add(new AttackRule(
                            n.path("state").asText("PUNCH"),
                            n.path("label").asText("Attack"),
                            n.path("minDamage").asInt(5),
                            n.path("maxDamage").asInt(12),
                            n.path("hitZone").asText("BODY"),
                            n.path("stunMs").asInt(0),
                            n.path("stunState").asText("GOTHIT01")
                    ));
                }
            }
        } catch (Exception e) {
            setStatus("Load failed: " + e.getMessage(), false);
        }
    }

    private void loadDefaults() {
        rules.addAll(List.of(
            new AttackRule("PUNCH",                "Punch",              5,  12, "BODY", 0,   "GOTHIT01"),
            new AttackRule("CROSS",                "Cross",              8,  15, "HEAD", 0,   "GOTHIT01"),
            new AttackRule("HOOK",                 "Hook",               6,  14, "HEAD", 200, "GOTHIT02"),
            new AttackRule("UPPERCUT",             "Uppercut",          10,  20, "HEAD", 400, "GOTHIT02"),
            new AttackRule("HAYMAKER",             "Haymaker",          15,  30, "HEAD", 600, "GOTHIT03"),
            new AttackRule("HEAD_KICK",            "Head Kick",         12,  22, "HEAD", 500, "GOTHIT03"),
            new AttackRule("LOW_KICK",             "Low Kick",           5,  10, "LEGS", 0,   "GOTHIT01"),
            new AttackRule("BODY_KICK",            "Body Kick",          8,  18, "BODY", 300, "GOTHIT02"),
            new AttackRule("SPINNING_BACK_KICK",   "Spinning Back Kick",14,  26, "BODY", 400, "GOTHIT03"),
            new AttackRule("SIDE_KICK",            "Side Kick",         10,  20, "BODY", 350, "GOTHIT02"),
            new AttackRule("POUNCE",               "Pounce",            12,  24, "BODY", 500, "KNOCKED_DOWN"),
            new AttackRule("BITE",                 "Bite",               8,  16, "BODY", 0,   "GOTHIT01")
        ));
    }

    private void save() {
        try {
            ObjectMapper om = new ObjectMapper();
            ObjectNode root = om.createObjectNode();

            ObjectNode timing = om.createObjectNode();
            timing.put("attackIntervalMs", attackIntervalSpinner.getValue());
            timing.put("stunDurationMs",   stunDurationSpinner.getValue());
            timing.put("koRecoveryMs",     koRecoverySpinner.getValue());
            root.set("timing", timing);

            ArrayNode attacks = om.createArrayNode();
            for (AttackRule r : rules) {
                ObjectNode n = om.createObjectNode();
                n.put("state",     r.state.get());
                n.put("label",     r.label.get());
                n.put("minDamage", r.minDamage.get());
                n.put("maxDamage", r.maxDamage.get());
                n.put("hitZone",   r.hitZone.get());
                n.put("stunMs",    r.stunMs.get());
                n.put("stunState", r.stunState.get());
                attacks.add(n);
            }
            root.set("attacks", attacks);

            ObjectNode display = om.createObjectNode();
            display.put("damageColor", toHex(damageColorPicker.getValue()));
            display.put("healColor",   toHex(healColorPicker.getValue()));
            root.set("display", display);

            Files.createDirectories(SAVE_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(SAVE_FILE.toFile(), root);
            setStatus("Saved " + rules.size() + " attack rules.", true);
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage(), false);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Read damageColor or healColor from game-mechanics.json, returns hex string. */
    public static String loadDisplayColor(String key, String def) {
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(SAVE_FILE.toFile());
            return root.path("display").path(key).asText(def);
        } catch (Exception e) { return def; }
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }

    private VBox colorRow(String labelText, ColorPicker picker) {
        Label l = lbl(labelText, 11, false);
        l.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");
        VBox v = new VBox(4, l, picker);
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    private void setStatus(String msg, boolean ok) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "#50c050" : "#e94560") + ";");
    }

    private Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> s = new Spinner<>(min, max, initial);
        s.setEditable(true);
        s.setPrefWidth(110);
        s.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;");
        return s;
    }

    private VBox labeledSpinner(String labelText, Spinner<Integer> spinner) {
        Label l = lbl(labelText, 11, false);
        l.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");
        VBox v = new VBox(4, l, spinner);
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    private Button btn(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white;" +
                   "-fx-background-radius: 4; -fx-font-size: 11; -fx-padding: 5 12 5 12;");
        return b;
    }

    private Label lbl(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: " + size + ";" +
                   (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private void styleTable(TableView<?> table) {
        table.setStyle(
            "-fx-background-color: #0f0f1e;" +
            "-fx-border-color: #3a3a6a;" +
            "-fx-control-inner-background: #0f0f1e;" +
            "-fx-table-cell-border-color: #2a2a4a;"
        );
        for (TableColumn<?, ?> col : table.getColumns()) {
            col.setStyle("-fx-background-color: #16213e; -fx-text-fill: #c0c0e0;");
        }
    }
}
