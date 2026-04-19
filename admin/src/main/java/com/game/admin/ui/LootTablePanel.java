package com.game.admin.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.beans.property.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.util.converter.IntegerStringConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loot Tables — define named drop tables that can be assigned to mobs.
 *
 * Each table has entries: item name, drop weight, min/max quantity, guaranteed flag.
 * Saved to: admin/src/main/resources/loot-tables.json
 */
public class LootTablePanel {

    public static final Path SAVE_FILE =
            Paths.get("admin/src/main/resources/loot-tables.json");

    // ── Data model ────────────────────────────────────────────────────────────

    public static class LootEntry {
        final StringProperty  itemName    = new SimpleStringProperty("");
        final IntegerProperty weight      = new SimpleIntegerProperty(10);
        final IntegerProperty minQty      = new SimpleIntegerProperty(1);
        final IntegerProperty maxQty      = new SimpleIntegerProperty(1);
        final BooleanProperty guaranteed  = new SimpleBooleanProperty(false);

        LootEntry() {}
        LootEntry(String item, int weight, int min, int max, boolean guaranteed) {
            this.itemName.set(item);
            this.weight.set(weight);
            this.minQty.set(min);
            this.maxQty.set(max);
            this.guaranteed.set(guaranteed);
        }
    }

    public static class LootTable {
        String           name;
        final List<LootEntry> entries = new ArrayList<>();

        LootTable(String name) { this.name = name; }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<LootTable> tables   = new ArrayList<>();
    private LootTable             selected = null;

    private ListView<String>            tableList;
    private TableView<LootEntry>        entryTable;
    private TextField                   tableNameField;
    private Label                       statusLabel;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        load();
        ensureDefaults();

        // ── Left: table roster ────────────────────────────────────────────────
        Label rosterTitle = lbl("Loot Tables", 13, true);

        tableList = new ListView<>();
        tableList.setPrefWidth(180);
        tableList.setPrefHeight(300);
        tableList.setStyle(
                "-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                "-fx-border-radius: 4; -fx-control-inner-background: #0f0f1e;");
        tableList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                if (!empty) setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12;" +
                        "-fx-background-color: " + (isSelected() ? "#3a3a6a" : "transparent") + ";");
            }
        });
        refreshTableList();
        tableList.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            if (i >= 0 && i < tables.size()) loadTable(tables.get(i));
        });

        Button addBtn = btn("+ Add Table", "#1e3a5f");
        Button delBtn = btn("Delete",      "#7b241c");
        addBtn.setOnAction(e -> addTable());
        delBtn.setOnAction(e -> deleteTable());

        HBox rosterBtns = new HBox(4, addBtn, delBtn);

        VBox leftCol = vbox(8, rosterTitle, tableList, rosterBtns);
        leftCol.setPrefWidth(188);

        // ── Right: table editor ───────────────────────────────────────────────
        Label editorTitle = lbl("Table Editor", 13, true);

        tableNameField = new TextField();
        tableNameField.setPromptText("Table name…");
        tableNameField.setStyle(
                "-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5;");
        tableNameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null) { selected.name = n; refreshTableList(); }
        });

        // Entry table
        entryTable = new TableView<>();
        entryTable.setEditable(true);
        entryTable.setStyle("-fx-background-color: #0f0f1e; -fx-control-inner-background: #0f0f1e;" +
                            "-fx-border-color: #3a3a6a;");
        entryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(entryTable, Priority.ALWAYS);

        TableColumn<LootEntry, String>  colItem  = strCol("Item",       120, e -> e.itemName);
        TableColumn<LootEntry, Integer> colWt    = intCol("Weight",      60,  e -> e.weight);
        TableColumn<LootEntry, Integer> colMin   = intCol("Min Qty",     60,  e -> e.minQty);
        TableColumn<LootEntry, Integer> colMax   = intCol("Max Qty",     60,  e -> e.maxQty);
        TableColumn<LootEntry, Boolean> colGuaranteed = new TableColumn<>("Always");
        colGuaranteed.setPrefWidth(55);
        colGuaranteed.setCellValueFactory(cd -> cd.getValue().guaranteed);
        colGuaranteed.setCellFactory(CheckBoxTableCell.forTableColumn(colGuaranteed));
        colGuaranteed.setEditable(true);

        //noinspection unchecked
        entryTable.getColumns().addAll(colItem, colWt, colMin, colMax, colGuaranteed);

        // Entry action buttons
        Button addEntryBtn = btn("+ Entry",  "#1e3a5f");
        Button dupEntryBtn = btn("Duplicate","#2a3a5f");
        Button delEntryBtn = btn("Remove",   "#7b241c");
        addEntryBtn.setOnAction(e -> addEntry());
        dupEntryBtn.setOnAction(e -> dupEntry());
        delEntryBtn.setOnAction(e -> delEntry());

        HBox entryBtns = new HBox(6, addEntryBtn, dupEntryBtn, delEntryBtn);

        // Weight hint
        Label weightHint = new Label("Weight = relative drop chance (e.g. 10 vs 5 → 2× more likely). \"Always\" = guaranteed every kill.");
        weightHint.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        weightHint.setWrapText(true);

        Button saveBtn = btn("💾 Save All Tables", "#1e5f3a");
        saveBtn.setOnAction(e -> save());
        saveBtn.setMaxWidth(200);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        VBox rightCol = new VBox(8,
                editorTitle,
                new VBox(4, lbl("Name:", 11, false), tableNameField),
                lbl("Entries:", 11, true),
                entryTable,
                entryBtns,
                weightHint,
                saveBtn, statusLabel);
        rightCol.setPadding(new Insets(8));
        rightCol.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        VBox.setVgrow(entryTable, Priority.ALWAYS);
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox root = new HBox(8, leftCol, rightCol);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(root, Priority.ALWAYS);

        if (!tables.isEmpty()) tableList.getSelectionModel().select(0);
        return root;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void addTable() {
        LootTable t = new LootTable("New Table");
        tables.add(t);
        refreshTableList();
        tableList.getSelectionModel().select(tables.size() - 1);
    }

    private void deleteTable() {
        if (selected == null) return;
        tables.remove(selected);
        selected = null;
        tableNameField.setText("");
        entryTable.getItems().clear();
        refreshTableList();
        if (!tables.isEmpty()) tableList.getSelectionModel().select(0);
    }

    private void loadTable(LootTable t) {
        selected = t;
        tableNameField.setText(t.name);
        entryTable.getItems().setAll(t.entries);
    }

    private void addEntry() {
        if (selected == null) return;
        LootEntry e = new LootEntry("Item", 10, 1, 1, false);
        selected.entries.add(e);
        entryTable.getItems().add(e);
        entryTable.getSelectionModel().select(e);
        entryTable.scrollTo(e);
    }

    private void dupEntry() {
        LootEntry sel = entryTable.getSelectionModel().getSelectedItem();
        if (sel == null || selected == null) return;
        LootEntry dup = new LootEntry(sel.itemName.get(), sel.weight.get(),
                sel.minQty.get(), sel.maxQty.get(), sel.guaranteed.get());
        int idx = selected.entries.indexOf(sel) + 1;
        selected.entries.add(idx, dup);
        entryTable.getItems().add(idx, dup);
        entryTable.getSelectionModel().select(dup);
    }

    private void delEntry() {
        LootEntry sel = entryTable.getSelectionModel().getSelectedItem();
        if (sel == null || selected == null) return;
        selected.entries.remove(sel);
        entryTable.getItems().remove(sel);
    }

    private void refreshTableList() {
        String selName = selected != null ? selected.name : null;
        tableList.getItems().clear();
        tables.forEach(t -> tableList.getItems().add(t.name));
        if (selName != null) {
            for (int i = 0; i < tables.size(); i++) {
                if (tables.get(i).name.equals(selName)) {
                    tableList.getSelectionModel().select(i);
                    break;
                }
            }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void save() {
        try {
            ObjectMapper om = new ObjectMapper();
            ArrayNode root  = om.createArrayNode();
            for (LootTable t : tables) {
                ObjectNode tn = om.createObjectNode();
                tn.put("name", t.name);
                ArrayNode entries = om.createArrayNode();
                for (LootEntry e : t.entries) {
                    ObjectNode en = om.createObjectNode();
                    en.put("item",       e.itemName.get());
                    en.put("weight",     e.weight.get());
                    en.put("minQty",     e.minQty.get());
                    en.put("maxQty",     e.maxQty.get());
                    en.put("guaranteed", e.guaranteed.get());
                    entries.add(en);
                }
                tn.set("entries", entries);
                root.add(tn);
            }
            Files.createDirectories(SAVE_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(SAVE_FILE.toFile(), root);
            setStatus("✓ Saved " + tables.size() + " table(s).", true);
        } catch (Exception e) {
            setStatus("✗ Save failed: " + e.getMessage(), false);
        }
    }

    private void load() {
        tables.clear();
        if (!Files.exists(SAVE_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode tn : om.readTree(SAVE_FILE.toFile())) {
                LootTable t = new LootTable(tn.path("name").asText("Table"));
                for (JsonNode en : tn.path("entries")) {
                    t.entries.add(new LootEntry(
                            en.path("item").asText("Item"),
                            en.path("weight").asInt(10),
                            en.path("minQty").asInt(1),
                            en.path("maxQty").asInt(1),
                            en.path("guaranteed").asBoolean(false)));
                }
                tables.add(t);
            }
        } catch (Exception ignored) {}
    }

    private void ensureDefaults() {
        if (!tables.isEmpty()) return;
        LootTable common = new LootTable("Common Drops");
        common.entries.add(new LootEntry("Gold Coin",    50, 1,  5,  false));
        common.entries.add(new LootEntry("Health Potion",20, 1,  1,  false));
        common.entries.add(new LootEntry("Cloth Scraps", 30, 1,  3,  false));

        LootTable beast = new LootTable("Beast Drops");
        beast.entries.add(new LootEntry("Animal Hide",  40, 1,  2,  false));
        beast.entries.add(new LootEntry("Fang",         25, 1,  3,  false));
        beast.entries.add(new LootEntry("Gold Coin",    20, 1,  3,  false));

        LootTable boss = new LootTable("Boss Drops");
        boss.entries.add(new LootEntry("Gold Coin",     100, 20, 50, true));
        boss.entries.add(new LootEntry("Rare Equipment",  5,  1,  1, false));
        boss.entries.add(new LootEntry("Boss Trophy",   100,  1,  1, true));

        tables.addAll(List.of(common, beast, boss));
    }

    /** Returns the names of all currently loaded tables. Used by MobManagerPanel. */
    public static List<String> loadTableNames() {
        List<String> names = new ArrayList<>();
        if (!Files.exists(SAVE_FILE)) return names;
        try {
            for (JsonNode node : new ObjectMapper().readTree(SAVE_FILE.toFile()))
                names.add(node.path("name").asText());
        } catch (Exception ignored) {}
        return names;
    }

    private void setStatus(String msg, boolean ok) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "#50c050" : "#e94560") + ";");
    }

    // ── Column helpers ────────────────────────────────────────────────────────

    private <T> TableColumn<LootEntry, String> strCol(String title, double w,
            java.util.function.Function<LootEntry, StringProperty> prop) {
        TableColumn<LootEntry, String> col = new TableColumn<>(title);
        col.setPrefWidth(w);
        col.setCellValueFactory(cd -> prop.apply(cd.getValue()));
        col.setCellFactory(TextFieldTableCell.forTableColumn());
        col.setEditable(true);
        return col;
    }

    private TableColumn<LootEntry, Integer> intCol(String title, double w,
            java.util.function.Function<LootEntry, IntegerProperty> prop) {
        TableColumn<LootEntry, Integer> col = new TableColumn<>(title);
        col.setPrefWidth(w);
        col.setCellValueFactory(cd -> prop.apply(cd.getValue()).asObject());
        col.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        col.setEditable(true);
        return col;
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

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
