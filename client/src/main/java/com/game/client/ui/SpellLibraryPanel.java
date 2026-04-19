package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Spell Library — define every spell's effects.
 *
 * Spells are referenced by name from Item definitions (for item-cast spells)
 * and can also be cast directly by players/enemies.
 *
 * Saved to: client/src/main/resources/graphics/sprites/spell-library.json
 */
public class SpellLibraryPanel {

    public static final Path SAVE_FILE =
            Paths.get("client/src/main/resources/graphics/sprites/spell-library.json");

    // ── Effect type ───────────────────────────────────────────────────────────
    public enum EffectType {
        DAMAGE, HEAL, STAT_ADD, STAT_REMOVE, DOT, HOT, DISPEL;

        public String label() {
            return switch (this) {
                case DAMAGE      -> "Damage";
                case HEAL        -> "Heal";
                case STAT_ADD    -> "Stat Add";
                case STAT_REMOVE -> "Stat Remove";
                case DOT         -> "Damage over Time";
                case HOT         -> "Heal over Time";
                case DISPEL      -> "Dispel";
            };
        }

        /** Whether this effect targets a specific stat. */
        public boolean hasStat()     { return this == STAT_ADD || this == STAT_REMOVE; }
        /** Whether this effect has a damage type. */
        public boolean hasDmgType()  { return this == DAMAGE || this == DOT; }
        /** Whether this effect has a duration. */
        public boolean hasDuration() { return this == DOT || this == HOT || this == STAT_ADD || this == STAT_REMOVE; }
    }

    // ── Damage type ───────────────────────────────────────────────────────────
    public enum DamageType {
        PHYSICAL, FIRE, COLD, POISON, DISEASE, MAGIC;

        public String label() { return name().charAt(0) + name().substring(1).toLowerCase(); }

        public String color() {
            return switch (this) {
                case PHYSICAL -> "#c0c0c0";
                case FIRE     -> "#ff4422";
                case COLD     -> "#44aaff";
                case POISON   -> "#88cc44";
                case DISEASE  -> "#aa8844";
                case MAGIC    -> "#cc88ff";
            };
        }
    }

    // ── Target type ───────────────────────────────────────────────────────────
    public enum TargetType {
        SELF, SINGLE_ENEMY, SINGLE_ALLY, AREA_ENEMY, AREA_ALLY, AREA_ALL;

        public String label() {
            return switch (this) {
                case SELF         -> "Self";
                case SINGLE_ENEMY -> "Single Enemy";
                case SINGLE_ALLY  -> "Single Ally";
                case AREA_ENEMY   -> "Area (Enemies)";
                case AREA_ALLY    -> "Area (Allies)";
                case AREA_ALL     -> "Area (All)";
            };
        }
    }

    // ── Stat keys available for STAT_ADD / STAT_REMOVE ────────────────────────
    static final String[] STAT_KEYS = {
        "HP", "MANA", "HP_REGEN", "MANA_REGEN",
        "INT", "STR", "WIS", "CHA", "STA", "AGI", "DEX", "LUK",
        "ARMOR", "HASTE", "ENH_DMG",
        "FIRE_RES", "COLD_RES", "POISON_RES", "DISEASE_RES", "MAGIC_RES"
    };

    // ── Data model ────────────────────────────────────────────────────────────

    public static class SpellEffect {
        public EffectType type       = EffectType.DAMAGE;
        public String     stat       = "HP";
        public int        value      = 10;
        public boolean    isPercent  = false;
        public int        duration   = 0;      // seconds; 0 = instant
        public DamageType damageType = DamageType.MAGIC;
    }

    public static class SpellDef {
        public String            name;
        public String            description = "";
        public int               manaCost    = 10;
        public int               cooldown    = 0;   // seconds
        public TargetType        targetType  = TargetType.SINGLE_ENEMY;
        public List<SpellEffect> effects     = new ArrayList<>();

        SpellDef(String name) { this.name = name; }
    }

    // ── Panel state ───────────────────────────────────────────────────────────
    private final List<SpellDef> spells = new ArrayList<>();
    private SpellDef selected = null;

    private ListView<String>  spellList;
    private TextField         nameField;
    private TextArea          descField;
    private Spinner<Integer>  spMana;
    private Spinner<Integer>  spCooldown;
    private ComboBox<String>  targetCombo;
    private VBox              effectsBox;
    private Label             statusLabel;
    private TextField         searchField;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        load();

        // ── Left: spell list ──────────────────────────────────────────────────
        Label listTitle = lbl("Spell Library", 13, true);

        searchField = new TextField();
        searchField.setPromptText("Search…");
        searchField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                             "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 4;");
        searchField.textProperty().addListener((obs, o, n) -> refreshList(n));

        spellList = new ListView<>();
        spellList.setPrefWidth(200);
        VBox.setVgrow(spellList, Priority.ALWAYS);
        spellList.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                           "-fx-border-radius: 4; -fx-control-inner-background: #0f0f1e;");
        spellList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                if (!empty)
                    setStyle("-fx-text-fill: #a080ff; -fx-font-size: 12; -fx-padding: 4 4 4 4;" +
                             "-fx-background-color: " + (isSelected() ? "#3a3a6a" : "transparent") + ";");
            }
        });
        refreshList("");
        spellList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            spells.stream().filter(s -> s.name.equals(n)).findFirst().ifPresent(this::loadIntoForm);
        });

        Button addBtn = btn("+ New Spell", "#2a1a3a");
        Button delBtn = btn("Delete",      "#7b241c");
        Button dupBtn = btn("Duplicate",   "#1e3a5f");
        addBtn.setOnAction(e -> addNew());
        delBtn.setOnAction(e -> deleteSelected());
        dupBtn.setOnAction(e -> duplicateSelected());

        VBox leftCol = vbox(6, listTitle, searchField, spellList,
                new HBox(4, addBtn, delBtn), dupBtn);
        leftCol.setPrefWidth(210);

        // ── Centre: editor ────────────────────────────────────────────────────
        nameField = new TextField();
        nameField.setPromptText("Spell name…");
        nameField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                           "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5;");
        nameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null) { selected.name = n; refreshList(searchField.getText()); }
        });

        descField = new TextArea();
        descField.setPromptText("Spell description…");
        descField.setPrefRowCount(3);
        descField.setWrapText(true);
        descField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                           "-fx-border-color: #3a3a6a; -fx-control-inner-background: #0f0f1e;");
        descField.textProperty().addListener((obs, o, n) -> { if (selected != null) selected.description = n; });

        spMana     = intSpinner(0, 9999, 0);
        spCooldown = intSpinner(0, 9999, 0);
        spMana    .valueProperty().addListener((o, p, n) -> { if (selected != null) selected.manaCost = n; });
        spCooldown.valueProperty().addListener((o, p, n) -> { if (selected != null) selected.cooldown  = n; });

        targetCombo = new ComboBox<>();
        for (TargetType t : TargetType.values()) targetCombo.getItems().add(t.label());
        targetCombo.getStyleClass().add("combo-dark");
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        targetCombo.setOnAction(e -> {
            if (selected != null) {
                int idx = targetCombo.getSelectionModel().getSelectedIndex();
                if (idx >= 0) selected.targetType = TargetType.values()[idx];
            }
        });

        effectsBox = new VBox(6);
        effectsBox.setStyle("-fx-background-color: #0f0f1e; -fx-padding: 8;" +
                            "-fx-border-color: #3a3a6a; -fx-border-radius: 4;");

        Button addEffectBtn = btn("+ Add Effect", "#1a2a1a");
        addEffectBtn.setMaxWidth(160);
        addEffectBtn.setOnAction(e -> {
            if (selected != null) {
                selected.effects.add(new SpellEffect());
                rebuildEffectsBox();
            }
        });

        ScrollPane effectsScroll = new ScrollPane(effectsBox);
        effectsScroll.setFitToWidth(true);
        effectsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        effectsScroll.setStyle("-fx-background-color: #0f0f1e; -fx-background: #0f0f1e;");
        effectsScroll.setPrefHeight(280);

        Button saveBtn = btn("💾 Save Spells", "#1e5f3a");
        saveBtn.setMaxWidth(200);
        saveBtn.setOnAction(e -> save());

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        HBox manaRow = new HBox(8, lbl("Mana Cost:", 11, false), spMana);
        manaRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(spMana, Priority.ALWAYS);
        HBox coolRow = new HBox(8, lbl("Cooldown (sec):", 11, false), spCooldown);
        coolRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(spCooldown, Priority.ALWAYS);

        VBox cfgCol = vbox(8,
                lbl("Spell Configuration", 13, true),
                new VBox(4, lbl("Name:",        11, false), nameField),
                new VBox(4, lbl("Description:", 11, false), descField),
                manaRow, coolRow,
                new VBox(4, lbl("Target:",      11, false), targetCombo),
                new VBox(4, lbl("Effects:",     11, true), addEffectBtn, effectsScroll),
                saveBtn, statusLabel);

        ScrollPane cfgScroll = new ScrollPane(cfgCol);
        cfgScroll.setFitToWidth(true);
        cfgScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        cfgScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        cfgScroll.setStyle("-fx-background-color: #1a1a2e; -fx-background: #1a1a2e;");
        HBox.setHgrow(cfgScroll, Priority.ALWAYS);

        HBox root = new HBox(8, leftCol, cfgScroll);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(root, Priority.ALWAYS);

        if (!spells.isEmpty()) spellList.getSelectionModel().select(0);
        return root;
    }

    // ── Effects editor ────────────────────────────────────────────────────────

    private void rebuildEffectsBox() {
        effectsBox.getChildren().clear();
        if (selected == null || selected.effects.isEmpty()) {
            Label empty = new Label("No effects — click '+ Add Effect'.");
            empty.setStyle("-fx-text-fill: #606080; -fx-font-size: 11;");
            effectsBox.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < selected.effects.size(); i++)
            effectsBox.getChildren().add(buildEffectRow(selected.effects, i));
    }

    private Node buildEffectRow(List<SpellEffect> effects, int idx) {
        SpellEffect ef = effects.get(idx);

        // ── Row index label ───────────────────────────────────────────────────
        Label idxLbl = new Label("#" + (idx + 1));
        idxLbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 11; -fx-min-width: 22;");

        // ── Type combo ────────────────────────────────────────────────────────
        ComboBox<String> typeCombo = new ComboBox<>();
        for (EffectType t : EffectType.values()) typeCombo.getItems().add(t.label());
        typeCombo.getSelectionModel().select(ef.type.ordinal());
        typeCombo.setStyle("-fx-background-color: #1a1a3a; -fx-text-fill: #e0e0e0; -fx-font-size: 11;");
        typeCombo.setPrefWidth(140);

        // ── Stat combo (STAT_ADD / STAT_REMOVE) ───────────────────────────────
        ComboBox<String> statCombo = new ComboBox<>();
        statCombo.getItems().addAll(STAT_KEYS);
        statCombo.setValue(ef.stat);
        statCombo.setStyle("-fx-background-color: #1a1a3a; -fx-text-fill: #44cc88; -fx-font-size: 11;");
        statCombo.setPrefWidth(110);
        statCombo.setOnAction(e -> ef.stat = statCombo.getValue());
        statCombo.setVisible(ef.type.hasStat());
        statCombo.setManaged(ef.type.hasStat());

        // ── Damage type combo (DAMAGE / DOT) ──────────────────────────────────
        ComboBox<String> dmgTypeCombo = new ComboBox<>();
        for (DamageType d : DamageType.values()) dmgTypeCombo.getItems().add(d.label());
        dmgTypeCombo.getSelectionModel().select(ef.damageType.ordinal());
        dmgTypeCombo.setStyle("-fx-background-color: #1a1a3a; -fx-text-fill: " +
                ef.damageType.color() + "; -fx-font-size: 11;");
        dmgTypeCombo.setPrefWidth(90);
        dmgTypeCombo.setOnAction(e -> {
            int i = dmgTypeCombo.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                ef.damageType = DamageType.values()[i];
                dmgTypeCombo.setStyle("-fx-background-color: #1a1a3a; -fx-text-fill: " +
                        ef.damageType.color() + "; -fx-font-size: 11;");
            }
        });
        dmgTypeCombo.setVisible(ef.type.hasDmgType());
        dmgTypeCombo.setManaged(ef.type.hasDmgType());

        // ── Type combo: update effect type then rebuild the whole row list ────
        typeCombo.setOnAction(e -> {
            int i = typeCombo.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                ef.type = EffectType.values()[i];
                rebuildEffectsBox();
            }
        });

        // ── Value spinner ─────────────────────────────────────────────────────
        Spinner<Integer> valSpin = new Spinner<>(-9999, 9999, ef.value);
        valSpin.setEditable(true);
        valSpin.setPrefWidth(80);
        valSpin.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0; -fx-font-size: 11;");
        valSpin.valueProperty().addListener((o, p, n) -> ef.value = n);

        // ── % checkbox ───────────────────────────────────────────────────────
        CheckBox pctCheck = new CheckBox("%");
        pctCheck.setSelected(ef.isPercent);
        pctCheck.setStyle("-fx-text-fill: #c0c0c0; -fx-font-size: 11;");
        pctCheck.setOnAction(e -> ef.isPercent = pctCheck.isSelected());

        // ── Duration spinner ──────────────────────────────────────────────────
        Spinner<Integer> durSpin = new Spinner<>(0, 9999, ef.duration);
        durSpin.setEditable(true);
        durSpin.setPrefWidth(70);
        durSpin.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0; -fx-font-size: 11;");
        durSpin.valueProperty().addListener((o, p, n) -> ef.duration = n);
        Label durLbl = new Label("sec");
        durLbl.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        // durRow declared here so typeCombo closure can reference it
        HBox durRow = new HBox(4, lbl("Dur:", 11, false), durSpin, durLbl);
        durRow.setAlignment(Pos.CENTER_LEFT);
        durRow.setVisible(ef.type.hasDuration());
        durRow.setManaged(ef.type.hasDuration());

        // ── Remove button ─────────────────────────────────────────────────────
        Button removeBtn = new Button("✕");
        removeBtn.setStyle("-fx-background-color: #5f1e1e; -fx-text-fill: white;" +
                           "-fx-font-size: 11; -fx-padding: 3 7 3 7; -fx-background-radius: 3;");
        removeBtn.setOnAction(e -> { effects.remove(idx); rebuildEffectsBox(); });

        // ── Layout: two lines per effect ──────────────────────────────────────
        HBox line1 = new HBox(6, idxLbl, typeCombo, statCombo, dmgTypeCombo,
                              new Region(), removeBtn);
        HBox.setHgrow(line1.getChildren().get(4), Priority.ALWAYS);
        line1.setAlignment(Pos.CENTER_LEFT);

        HBox line2 = new HBox(8,
                lbl("Val:", 11, false), valSpin, pctCheck, durRow);
        line2.setAlignment(Pos.CENTER_LEFT);
        line2.setPadding(new Insets(0, 0, 0, 28));

        VBox row = new VBox(4, line1, line2);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-color: #12122a; -fx-border-color: #2a2a4a;" +
                     "-fx-border-radius: 4; -fx-background-radius: 4;");
        return row;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void addNew() {
        SpellDef s = new SpellDef("New Spell");
        spells.add(s);
        refreshList(searchField.getText());
        spellList.getSelectionModel().select(s.name);
    }

    private void deleteSelected() {
        if (selected == null) return;
        spells.remove(selected);
        selected = null;
        clearForm();
        refreshList(searchField.getText());
        if (!spells.isEmpty()) spellList.getSelectionModel().select(0);
    }

    private void duplicateSelected() {
        if (selected == null) return;
        SpellDef dup = new SpellDef(selected.name + " (copy)");
        dup.description = selected.description;
        dup.manaCost    = selected.manaCost;
        dup.cooldown    = selected.cooldown;
        dup.targetType  = selected.targetType;
        for (SpellEffect ef : selected.effects) {
            SpellEffect c = new SpellEffect();
            c.type = ef.type; c.stat = ef.stat; c.value = ef.value;
            c.isPercent = ef.isPercent; c.duration = ef.duration; c.damageType = ef.damageType;
            dup.effects.add(c);
        }
        spells.add(spells.indexOf(selected) + 1, dup);
        refreshList(searchField.getText());
        spellList.getSelectionModel().select(dup.name);
    }

    private void loadIntoForm(SpellDef s) {
        selected = s;
        nameField.setText(s.name);
        descField.setText(s.description);
        spMana    .getValueFactory().setValue(s.manaCost);
        spCooldown.getValueFactory().setValue(s.cooldown);
        targetCombo.getSelectionModel().select(s.targetType.ordinal());
        rebuildEffectsBox();
    }

    private void clearForm() {
        if (nameField   != null) nameField.setText("");
        if (descField   != null) descField.setText("");
        if (effectsBox  != null) effectsBox.getChildren().clear();
    }

    private void refreshList(String filter) {
        String sel = selected != null ? selected.name : null;
        spellList.getItems().clear();
        String lo = (filter == null ? "" : filter).toLowerCase();
        for (SpellDef s : spells) {
            if (!lo.isEmpty() && !s.name.toLowerCase().contains(lo)) continue;
            spellList.getItems().add(s.name);
        }
        if (sel != null && spellList.getItems().contains(sel))
            spellList.getSelectionModel().select(sel);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void save() {
        try {
            ObjectMapper om = new ObjectMapper();
            ArrayNode root = om.createArrayNode();
            for (SpellDef s : spells) {
                ObjectNode n = om.createObjectNode();
                n.put("name",        s.name);
                n.put("description", s.description);
                n.put("manaCost",    s.manaCost);
                n.put("cooldown",    s.cooldown);
                n.put("targetType",  s.targetType.name());
                ArrayNode efArr = om.createArrayNode();
                for (SpellEffect ef : s.effects) {
                    ObjectNode efn = om.createObjectNode();
                    efn.put("type",       ef.type.name());
                    efn.put("stat",       ef.stat);
                    efn.put("value",      ef.value);
                    efn.put("isPercent",  ef.isPercent);
                    efn.put("duration",   ef.duration);
                    efn.put("damageType", ef.damageType.name());
                    efArr.add(efn);
                }
                n.set("effects", efArr);
                root.add(n);
            }
            Files.createDirectories(SAVE_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(SAVE_FILE.toFile(), root);
            statusLabel.setText("✓ Saved " + spells.size() + " spell(s).");
            statusLabel.setStyle("-fx-text-fill: #50c050; -fx-font-size: 11;");
        } catch (Exception e) {
            statusLabel.setText("✗ Save failed: " + e.getMessage());
            statusLabel.setStyle("-fx-text-fill: #e94560; -fx-font-size: 11;");
        }
    }

    private void load() {
        spells.clear();
        if (!Files.exists(SAVE_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode n : om.readTree(SAVE_FILE.toFile())) {
                SpellDef s = new SpellDef(n.path("name").asText("Spell"));
                s.description = n.path("description").asText("");
                s.manaCost    = n.path("manaCost").asInt(0);
                s.cooldown    = n.path("cooldown").asInt(0);
                try { s.targetType = TargetType.valueOf(n.path("targetType").asText("SINGLE_ENEMY")); }
                catch (IllegalArgumentException ignored) {}
                for (JsonNode efn : n.path("effects")) {
                    SpellEffect ef = new SpellEffect();
                    try { ef.type      = EffectType.valueOf(efn.path("type").asText("DAMAGE")); }
                    catch (IllegalArgumentException ignored) {}
                    ef.stat      = efn.path("stat").asText("HP");
                    ef.value     = efn.path("value").asInt(0);
                    ef.isPercent = efn.path("isPercent").asBoolean(false);
                    ef.duration  = efn.path("duration").asInt(0);
                    try { ef.damageType = DamageType.valueOf(efn.path("damageType").asText("MAGIC")); }
                    catch (IllegalArgumentException ignored) {}
                    s.effects.add(ef);
                }
                spells.add(s);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Returns a name → SpellDef map for cross-panel lookups (e.g. InventoryPanel).
     */
    public static java.util.Map<String, SpellDef> loadSpellMap() {
        java.util.Map<String, SpellDef> map = new java.util.LinkedHashMap<>();
        if (!Files.exists(SAVE_FILE)) return map;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode n : om.readTree(SAVE_FILE.toFile())) {
                SpellDef s = new SpellDef(n.path("name").asText("Spell"));
                s.description = n.path("description").asText("");
                s.manaCost    = n.path("manaCost").asInt(0);
                s.cooldown    = n.path("cooldown").asInt(0);
                try { s.targetType = TargetType.valueOf(n.path("targetType").asText("SINGLE_ENEMY")); }
                catch (IllegalArgumentException ignored) {}
                for (JsonNode efn : n.path("effects")) {
                    SpellEffect ef = new SpellEffect();
                    try { ef.type = EffectType.valueOf(efn.path("type").asText("DAMAGE")); }
                    catch (IllegalArgumentException ignored) {}
                    ef.stat      = efn.path("stat").asText("HP");
                    ef.value     = efn.path("value").asInt(0);
                    ef.isPercent = efn.path("isPercent").asBoolean(false);
                    ef.duration  = efn.path("duration").asInt(0);
                    try { ef.damageType = DamageType.valueOf(efn.path("damageType").asText("MAGIC")); }
                    catch (IllegalArgumentException ignored) {}
                    s.effects.add(ef);
                }
                map.put(s.name, s);
            }
        } catch (Exception ignored) {}
        return map;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> s = new Spinner<>(min, max, initial);
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        s.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;");
        return s;
    }

    private VBox vbox(int spacing, Node... children) {
        VBox v = new VBox(spacing, children);
        v.setPadding(new Insets(8));
        v.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return v;
    }
}
