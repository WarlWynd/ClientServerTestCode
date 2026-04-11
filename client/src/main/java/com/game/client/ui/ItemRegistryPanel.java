package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Item Registry — define every item's stat profile.
 *
 * Stats: HP, MANA, INT, STR, WIS, CHA, STA, AGI, DEX, LUK
 *
 * Items are referenced by name from Loot Tables.
 * Saved to: client/src/main/resources/graphics/sprites/item-registry.json
 */
public class ItemRegistryPanel {

    public static final Path SAVE_FILE =
            Paths.get("client/src/main/resources/graphics/sprites/item-registry.json");

    // ── Item category ─────────────────────────────────────────────────────────
    public enum ItemCategory { WEAPON, ARMOR, JEWELRY, CONSUMABLE, MATERIAL, QUEST, MISC }

    // ── Armor / jewelry equipment slot ────────────────────────────────────────
    public enum ArmorSlot {
        NONE, HEAD, NECK, SHOULDERS, CHEST, BACK, WRISTS, HANDS, WAIST, LEGS, FEET, RING, TRINKET;
        public String label() {
            return switch (this) {
                case NONE      -> "—";
                case HEAD      -> "Head";
                case NECK      -> "Neck";
                case SHOULDERS -> "Shoulders";
                case CHEST     -> "Chest";
                case BACK      -> "Back";
                case WRISTS    -> "Wrists";
                case HANDS     -> "Hands";
                case WAIST     -> "Waist";
                case LEGS      -> "Legs";
                case FEET      -> "Feet";
                case RING      -> "Ring";
                case TRINKET   -> "Trinket";
            };
        }
    }

    // ── Data model ────────────────────────────────────────────────────────────
    public static class ItemDef {
        String       name;
        ItemCategory category;
        ArmorSlot    armorSlot = ArmorSlot.NONE;
        String       description;
        int          statArmor;                        // damage reduction %
        int          statHp, statMana;
        int          statInt, statStr, statWis, statCha;
        int          statSta, statAgi, statDex, statLuk;
        int          value; // gold value

        ItemDef(String name, ItemCategory category) {
            this.name = name; this.category = category; this.description = "";
        }
    }

    // ── Panel state ───────────────────────────────────────────────────────────
    private UDPClient           client   = null;
    private final List<ItemDef> items    = new ArrayList<>();
    private ItemDef             selected = null;

    private ListView<String>  itemList;
    private TextField         nameField;
    private ComboBox<String>  categoryCombo;
    private ComboBox<String>  slotCombo;
    private TextArea          descField;
    private Spinner<Integer>  spArmor;
    private Spinner<Integer>  spHp, spMana;
    private Spinner<Integer>  spInt, spStr, spWis, spCha, spSta, spAgi, spDex, spLuk, spValue;
    private Label             statusLabel;
    private TextField         searchField;

    // ── Construction ─────────────────────────────────────────────────────────

    public ItemRegistryPanel() {}

    public ItemRegistryPanel(UDPClient client) {
        this.client = client;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        load();
        ensureDefaults();

        // ── Left: item list ───────────────────────────────────────────────────
        Label listTitle = lbl("Item Registry", 13, true);

        searchField = new TextField();
        searchField.setPromptText("Search…");
        searchField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                             "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 4;");
        searchField.textProperty().addListener((obs, o, n) -> refreshList(n));

        itemList = new ListView<>();
        itemList.setPrefWidth(200);
        VBox.setVgrow(itemList, Priority.ALWAYS);
        itemList.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;" +
                          "-fx-border-radius: 4; -fx-control-inner-background: #0f0f1e;");
        itemList.setCellFactory(lv -> new ListCell<>() {
            private static final java.util.Set<String> COINS = java.util.Set.of(
                    "Bronze Coin", "Silver Coin", "Gold Coin", "Platinum Coin");
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                if (!empty) {
                    String bare = item.length() > 2 ? item.substring(2).trim() : item.trim();
                    String padding = COINS.contains(bare) ? "1 4 1 4" : "4 4 4 4";
                    setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12;" +
                            "-fx-padding: " + padding + ";" +
                            "-fx-background-color: " + (isSelected() ? "#3a3a6a" : "transparent") + ";");
                }
            }
        });
        refreshList("");
        itemList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            // Find the actual item (strip icon prefix, handle search filter)
            String bare = n.length() > 2 ? n.substring(2).trim() : n.trim();
            items.stream().filter(it -> it.name.equals(bare)).findFirst().ifPresent(this::loadIntoForm);
        });

        Button addWeaponBtn  = btn("+ Weapon",    "#3a1a1a");
        Button addArmorBtn   = btn("+ Armor",     "#1a2a3a");
        Button addJewelryBtn = btn("+ Jewelry",   "#2a1a3a");
        Button addConsBtn    = btn("+ Consumable","#1a3a1a");
        Button addMatBtn     = btn("+ Material",  "#2a2a1a");
        Button addMiscBtn    = btn("+ Misc",      "#2a1a2a");
        Button delBtn        = btn("Delete",      "#7b241c");
        Button dupBtn        = btn("Duplicate",   "#1e3a5f");

        addWeaponBtn.setOnAction(e  -> addNew(ItemCategory.WEAPON));
        addArmorBtn.setOnAction(e   -> addNew(ItemCategory.ARMOR));
        addJewelryBtn.setOnAction(e -> addNew(ItemCategory.JEWELRY));
        addConsBtn.setOnAction(e    -> addNew(ItemCategory.CONSUMABLE));
        addMatBtn.setOnAction(e     -> addNew(ItemCategory.MATERIAL));
        addMiscBtn.setOnAction(e    -> addNew(ItemCategory.MISC));
        delBtn.setOnAction(e        -> deleteSelected());
        dupBtn.setOnAction(e        -> duplicateSelected());

        VBox leftCol = vbox(6, listTitle, searchField, itemList,
                new HBox(4, addWeaponBtn,  addArmorBtn),
                new HBox(4, addJewelryBtn, addConsBtn),
                new HBox(4, addMatBtn,     addMiscBtn),
                new HBox(4, dupBtn,        delBtn));
        leftCol.setPrefWidth(210);

        // ── Centre: editor ────────────────────────────────────────────────────
        nameField = new TextField();
        nameField.setPromptText("Item name…");
        nameField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                           "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5;");
        nameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null) { selected.name = n; refreshList(searchField.getText()); }
        });

        categoryCombo = new ComboBox<>();
        for (ItemCategory c : ItemCategory.values()) categoryCombo.getItems().add(c.name());
        categoryCombo.getStyleClass().add("combo-dark");
        categoryCombo.setMaxWidth(Double.MAX_VALUE);
        categoryCombo.setOnAction(e -> {
            if (selected != null && categoryCombo.getValue() != null)
                selected.category = ItemCategory.valueOf(categoryCombo.getValue());
        });

        slotCombo = new ComboBox<>();
        for (ArmorSlot s : ArmorSlot.values()) slotCombo.getItems().add(s.name());
        slotCombo.getStyleClass().add("combo-dark");
        slotCombo.setMaxWidth(Double.MAX_VALUE);
        slotCombo.setOnAction(e -> {
            if (selected != null && slotCombo.getValue() != null)
                selected.armorSlot = ArmorSlot.valueOf(slotCombo.getValue());
        });

        descField = new TextArea();
        descField.setPromptText("Item description…");
        descField.setPrefRowCount(3);
        descField.setWrapText(true);
        descField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                           "-fx-border-color: #3a3a6a; -fx-control-inner-background: #0f0f1e;");
        descField.textProperty().addListener((obs, o, n) -> {
            if (selected != null) selected.description = n;
        });

        // Gold value
        spValue = intSpinner(-9999, 99999, 0);

        // ── Stats grid ────────────────────────────────────────────────────────
        spArmor = intSpinner(0, 100, 0);
        spHp   = statSpinner(); spMana = statSpinner();
        spInt  = statSpinner(); spStr  = statSpinner(); spWis = statSpinner(); spCha = statSpinner();
        spSta  = statSpinner(); spAgi  = statSpinner(); spDex = statSpinner(); spLuk = statSpinner();

        spArmor.valueProperty().addListener((o, p, n) -> { if (selected != null) selected.statArmor = n; });
        spHp.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statHp   = n; });
        spMana.valueProperty().addListener((o, p, n)  -> { if (selected != null) selected.statMana = n; });
        spInt.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statInt  = n; });
        spStr.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statStr  = n; });
        spWis.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statWis  = n; });
        spCha.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statCha  = n; });
        spSta.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statSta  = n; });
        spAgi.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statAgi  = n; });
        spDex.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statDex  = n; });
        spLuk.valueProperty().addListener((o, p, n)   -> { if (selected != null) selected.statLuk  = n; });
        spValue.valueProperty().addListener((o, p, n) -> { if (selected != null) selected.value    = n; });

        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(10); statsGrid.setVgap(6);
        statsGrid.setStyle("-fx-background-color: #0f0f1e; -fx-padding: 10;" +
                           "-fx-border-color: #3a3a6a; -fx-border-radius: 4;");

        int row = 0;
        addStatRow(statsGrid, row++, "ARMOR (Dmg Reduction %)", spArmor, "#7090c0");
        addStatRow(statsGrid, row++, "HP   (Hit Points)",   spHp,   "#e05050");
        addStatRow(statsGrid, row++, "MANA (Mana Pool)",    spMana, "#5080ff");
        addStatRow(statsGrid, row++, "INT  (Intelligence)", spInt,  "#53c0f0");
        addStatRow(statsGrid, row++, "STR  (Strength)",     spStr,  "#e94560");
        addStatRow(statsGrid, row++, "WIS  (Wisdom)",       spWis,  "#c8a020");
        addStatRow(statsGrid, row++, "CHA  (Charisma)",     spCha,  "#bd10e0");
        addStatRow(statsGrid, row++, "STA  (Stamina)",      spSta,  "#50c050");
        addStatRow(statsGrid, row++, "AGI  (Agility)",      spAgi,  "#ff8844");
        addStatRow(statsGrid, row++, "DEX  (Dexterity)",    spDex,  "#44cc88");
        addStatRow(statsGrid, row++, "LUK  (Luck)",         spLuk,  "#f0e030");

        ColumnConstraints label_c = new ColumnConstraints(); label_c.setPrefWidth(140);
        ColumnConstraints spin_c  = new ColumnConstraints(); spin_c.setHgrow(Priority.ALWAYS);
        statsGrid.getColumnConstraints().addAll(label_c, spin_c);

        Button saveBtn = btn("💾 Save Registry", "#1e5f3a");
        saveBtn.setMaxWidth(200);
        saveBtn.setOnAction(e -> save());

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        HBox valueRow = new HBox(8, lbl("Gold Value:", 11, false), spValue);
        valueRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spValue, Priority.ALWAYS);

        VBox cfgCol = vbox(8,
                lbl("Item Configuration", 13, true),
                new VBox(4, lbl("Name:",        11, false), nameField),
                new VBox(4, lbl("Category:",    11, false), categoryCombo),
                new VBox(4, lbl("Equip Slot (Armor/Jewelry):", 11, false), slotCombo),
                new VBox(4, lbl("Description:", 11, false), descField),
                valueRow,
                lbl("Stat Bonuses  (negative = penalty):", 11, true),
                statsGrid,
                saveBtn, statusLabel);

        if (client != null) {
            Button giveBtn = btn("🎒 Give to Self", "#1e3a5f");
            giveBtn.setMaxWidth(200);
            giveBtn.setOnAction(e -> giveToSelf());
            cfgCol.getChildren().add(giveBtn);
        }
        cfgCol.setPrefWidth(340);
        VBox.setVgrow(cfgCol, Priority.ALWAYS);

        // ── Right: stat summary ───────────────────────────────────────────────
        Label summaryTitle = lbl("Stat Guide", 13, true);
        String guide =
                "ARMOR — Dmg Reduction\n" +
                "  % of incoming damage\n" +
                "  reduced. 0–100.\n\n" +
                "HP — Hit Points\n"     +
                "  Direct HP bonus/penalty\n\n" +
                "MANA — Mana Pool\n"    +
                "  Direct Mana bonus/penalty\n\n" +
                "INT — Intelligence\n"  +
                "  Spell power, mana pool\n\n" +
                "STR — Strength\n"      +
                "  Physical damage, carry weight\n\n" +
                "WIS — Wisdom\n"        +
                "  Magic defence, mana regen\n\n" +
                "CHA — Charisma\n"      +
                "  NPC reactions, shop prices\n\n" +
                "STA — Stamina\n"       +
                "  Max HP, endurance\n\n" +
                "AGI — Agility\n"       +
                "  Move speed, dodge chance\n\n" +
                "DEX — Dexterity\n"     +
                "  Attack speed, crit chance\n\n" +
                "LUK — Luck\n"          +
                "  Drop rates, crit bonus\n\n" +
                "─────────────────\n"   +
                "Positive values = bonus\n" +
                "Negative values = penalty\n\n" +
                "Gold Value = base sell\n" +
                "price at vendors.";
        Label guideLabel = new Label(guide);
        guideLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");
        guideLabel.setWrapText(true);

        VBox rightCol = vbox(8, summaryTitle, guideLabel);
        rightCol.setPrefWidth(200);

        HBox root = new HBox(8, leftCol, cfgCol, rightCol);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(root, Priority.ALWAYS);

        if (!items.isEmpty()) itemList.getSelectionModel().select(0);
        return root;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void addNew(ItemCategory cat) {
        ItemDef it = new ItemDef("New " + cat.name().charAt(0) + cat.name().substring(1).toLowerCase(), cat);
        items.add(it);
        refreshList(searchField.getText());
        selectItem(it);
    }

    private void deleteSelected() {
        if (selected == null) return;
        items.remove(selected);
        selected = null;
        clearForm();
        refreshList(searchField.getText());
        if (!items.isEmpty()) itemList.getSelectionModel().select(0);
    }

    private void duplicateSelected() {
        if (selected == null) return;
        ItemDef dup = new ItemDef(selected.name + " (copy)", selected.category);
        dup.armorSlot   = selected.armorSlot;
        dup.statArmor   = selected.statArmor;
        dup.description = selected.description;
        dup.statHp   = selected.statHp;  dup.statMana = selected.statMana;
        dup.statInt  = selected.statInt; dup.statStr  = selected.statStr;
        dup.statWis  = selected.statWis; dup.statCha  = selected.statCha;
        dup.statSta  = selected.statSta; dup.statAgi  = selected.statAgi;
        dup.statDex  = selected.statDex; dup.statLuk  = selected.statLuk;
        dup.value    = selected.value;
        int idx = items.indexOf(selected) + 1;
        items.add(idx, dup);
        refreshList(searchField.getText());
        selectItem(dup);
    }

    private void selectItem(ItemDef it) {
        String icon = categoryIcon(it.category);
        String label = icon + " " + it.name;
        itemList.getSelectionModel().select(label);
    }

    private void loadIntoForm(ItemDef it) {
        selected = it;
        nameField.setText(it.name);
        categoryCombo.setValue(it.category.name());
        slotCombo.setValue(it.armorSlot != null ? it.armorSlot.name() : ArmorSlot.NONE.name());
        descField.setText(it.description);
        spArmor.getValueFactory().setValue(it.statArmor);
        spHp.getValueFactory().setValue(it.statHp);
        spMana.getValueFactory().setValue(it.statMana);
        spInt.getValueFactory().setValue(it.statInt);
        spStr.getValueFactory().setValue(it.statStr);
        spWis.getValueFactory().setValue(it.statWis);
        spCha.getValueFactory().setValue(it.statCha);
        spSta.getValueFactory().setValue(it.statSta);
        spAgi.getValueFactory().setValue(it.statAgi);
        spDex.getValueFactory().setValue(it.statDex);
        spLuk.getValueFactory().setValue(it.statLuk);
        spValue.getValueFactory().setValue(it.value);
    }

    private void clearForm() {
        nameField.setText("");
        descField.setText("");
        for (Spinner<Integer> sp : List.of(spArmor, spHp, spMana, spInt, spStr, spWis, spCha, spSta, spAgi, spDex, spLuk, spValue))
            if (sp != null) sp.getValueFactory().setValue(0);
    }

    private static final java.util.List<String> COIN_ORDER =
            java.util.List.of("Bronze Coin", "Silver Coin", "Gold Coin", "Platinum Coin");

    private void refreshList(String filter) {
        String sel = selected != null ? selected.name : null;
        itemList.getItems().clear();
        String lo = (filter == null ? "" : filter).toLowerCase();

        // Sort: coins first (in tier order), then everything else in insertion order
        java.util.List<ItemDef> sorted = new java.util.ArrayList<>(items);
        sorted.sort((a, b) -> {
            int ia = COIN_ORDER.indexOf(a.name), ib = COIN_ORDER.indexOf(b.name);
            if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
            if (ia >= 0) return -1;
            if (ib >= 0) return  1;
            return 0;
        });

        for (ItemDef it : sorted) {
            if (!lo.isEmpty() && !it.name.toLowerCase().contains(lo)) continue;
            itemList.getItems().add(categoryIcon(it.category) + " " + it.name);
        }
        if (sel != null) {
            for (String s : itemList.getItems()) {
                if (s.endsWith(sel)) { itemList.getSelectionModel().select(s); break; }
            }
        }
    }

    private static String categoryIcon(ItemCategory cat) {
        return switch (cat) {
            case WEAPON     -> "⚔";
            case ARMOR      -> "🛡";
            case JEWELRY    -> "💍";
            case CONSUMABLE -> "🧪";
            case MATERIAL   -> "🪨";
            case QUEST      -> "📜";
            case MISC       -> "📦";
        };
    }

    /**
     * Loads item-registry.json and returns a name→ItemDef map.
     * Useful for cross-referencing item categories/slots in other panels
     * (e.g., InventoryPanel's Armor tab) without holding a full panel instance.
     */
    public static java.util.Map<String, ItemDef> loadItemMap() {
        java.util.Map<String, ItemDef> map = new java.util.LinkedHashMap<>();
        if (!java.nio.file.Files.exists(SAVE_FILE)) return map;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            for (com.fasterxml.jackson.databind.JsonNode n : om.readTree(SAVE_FILE.toFile())) {
                ItemCategory cat;
                try { cat = ItemCategory.valueOf(n.path("category").asText("MISC")); }
                catch (IllegalArgumentException e) { cat = ItemCategory.MISC; }
                ItemDef it = new ItemDef(n.path("name").asText("Item"), cat);
                try { it.armorSlot = ArmorSlot.valueOf(n.path("armorSlot").asText("NONE")); }
                catch (IllegalArgumentException ignored) { it.armorSlot = ArmorSlot.NONE; }
                it.description = n.path("description").asText("");
                it.value       = n.path("value").asInt(0);
                com.fasterxml.jackson.databind.JsonNode stats = n.path("stats");
                it.statArmor = stats.path("ARMOR").asInt(0);
                it.statHp   = stats.path("HP").asInt(0);   it.statMana = stats.path("MANA").asInt(0);
                it.statInt  = stats.path("INT").asInt(0);  it.statStr  = stats.path("STR").asInt(0);
                it.statWis  = stats.path("WIS").asInt(0);  it.statCha  = stats.path("CHA").asInt(0);
                it.statSta  = stats.path("STA").asInt(0);  it.statAgi  = stats.path("AGI").asInt(0);
                it.statDex  = stats.path("DEX").asInt(0);  it.statLuk  = stats.path("LUK").asInt(0);
                map.put(it.name, it);
            }
        } catch (Exception ignored) {}
        return map;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void save() {
        try {
            ObjectMapper om = new ObjectMapper();
            ArrayNode root  = om.createArrayNode();
            for (ItemDef it : items) {
                ObjectNode n = om.createObjectNode();
                n.put("name",        it.name);
                n.put("category",    it.category.name());
                n.put("armorSlot",   it.armorSlot != null ? it.armorSlot.name() : ArmorSlot.NONE.name());
                n.put("description", it.description);
                n.put("value",       it.value);
                ObjectNode stats = om.createObjectNode();
                stats.put("ARMOR", it.statArmor);
                stats.put("HP",  it.statHp);  stats.put("MANA", it.statMana);
                stats.put("INT", it.statInt); stats.put("STR",  it.statStr);
                stats.put("WIS", it.statWis); stats.put("CHA",  it.statCha);
                stats.put("STA", it.statSta); stats.put("AGI",  it.statAgi);
                stats.put("DEX", it.statDex); stats.put("LUK",  it.statLuk);
                n.set("stats", stats);
                root.add(n);
            }
            Files.createDirectories(SAVE_FILE.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(SAVE_FILE.toFile(), root);
            statusLabel.setText("✓ Saved " + items.size() + " item(s).");
            statusLabel.setStyle("-fx-text-fill: #50c050; -fx-font-size: 11;");
        } catch (Exception e) {
            statusLabel.setText("✗ Save failed: " + e.getMessage());
            statusLabel.setStyle("-fx-text-fill: #e94560; -fx-font-size: 11;");
        }
    }

    private void load() {
        items.clear();
        if (!Files.exists(SAVE_FILE)) return;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode n : om.readTree(SAVE_FILE.toFile())) {
                ItemCategory cat = ItemCategory.valueOf(n.path("category").asText("MISC"));
                ItemDef it = new ItemDef(n.path("name").asText("Item"), cat);
                try { it.armorSlot = ArmorSlot.valueOf(n.path("armorSlot").asText("NONE")); }
                catch (IllegalArgumentException ignored) { it.armorSlot = ArmorSlot.NONE; }
                it.description = n.path("description").asText("");
                it.value       = n.path("value").asInt(0);
                JsonNode stats = n.path("stats");
                it.statArmor = stats.path("ARMOR").asInt(0);
                it.statHp   = stats.path("HP").asInt(0);   it.statMana = stats.path("MANA").asInt(0);
                it.statInt  = stats.path("INT").asInt(0);  it.statStr  = stats.path("STR").asInt(0);
                it.statWis  = stats.path("WIS").asInt(0);  it.statCha  = stats.path("CHA").asInt(0);
                it.statSta  = stats.path("STA").asInt(0);  it.statAgi  = stats.path("AGI").asInt(0);
                it.statDex  = stats.path("DEX").asInt(0);  it.statLuk  = stats.path("LUK").asInt(0);
                items.add(it);
            }
        } catch (Exception ignored) {}
    }

    private void ensureDefaults() {
        // Merge: add any default that isn't already present by name.
        // Currency (bronze=1 < silver=10 < gold=100 < platinum=1000)
        addMissing("Bronze Coin",   ItemCategory.MATERIAL,   "Bronze currency. 10 bronze = 1 silver.",          0,0,  0,0,0,0,0,0,0,0,   1);
        addMissing("Silver Coin",   ItemCategory.MATERIAL,   "Silver currency. 10 silver = 1 gold.",            0,0,  0,0,0,0,0,0,0,0,  10);
        addMissing("Gold Coin",     ItemCategory.MATERIAL,   "Gold currency. 10 gold = 1 platinum.",            0,0,  0,0,0,0,0,0,0,0, 100);
        addMissing("Platinum Coin", ItemCategory.MATERIAL,   "Platinum currency. Most valuable coin.",          0,0,  0,0,0,0,0,0,0,0,1000);

        // Materials
        addMissing("Fang",          ItemCategory.MATERIAL,   "A sharp beast fang. Used in crafting.",           0,0,  0,1,0,0,0,1,0,0,  3);
        addMissing("Animal Hide",   ItemCategory.MATERIAL,   "Rough hide. Useful for crafting armor.",          0,0,  0,0,0,0,1,0,0,0,  5);
        addMissing("Cloth Scraps",  ItemCategory.MATERIAL,   "Torn cloth pieces.",                              0,0,  0,0,0,0,0,0,0,0,  2);
        addMissing("Bone Fragment", ItemCategory.MATERIAL,   "Fragment of old bone.",                           0,0,  0,0,0,0,0,0,0,0,  2);

        // Consumables
        addMissing("Health Potion", ItemCategory.CONSUMABLE, "Restores HP when used.",                         20,0,  0,0,0,0,0,0,0,0, 15);
        addMissing("Mana Potion",   ItemCategory.CONSUMABLE, "Restores Mana when used.",                        0,20, 0,0,0,0,0,0,0,0, 15);
        addMissing("Stamina Brew",  ItemCategory.CONSUMABLE, "Temporarily boosts STA.",                         0,0,  0,0,0,0,3,0,0,0, 12);

        // Boss drops
        addMissing("Boss Trophy",   ItemCategory.MISC,       "Proof of a great victory.",                       0,0,  1,1,1,1,1,1,1,1, 50);
        addMissing("Rare Equipment",ItemCategory.ARMOR,      "Finely crafted gear.",                           10,5,  0,3,0,0,2,0,2,0,100);

        // Default armor pieces
        addMissingArmor("Iron Helmet",     ItemCategory.ARMOR,   ArmorSlot.HEAD,      "Basic iron helmet.",            0,0, 0,1,0,0,2,0,0,0, 30);
        addMissingArmor("Iron Pauldrons",  ItemCategory.ARMOR,   ArmorSlot.SHOULDERS, "Iron shoulder guards.",         0,0, 0,1,0,0,2,0,0,0, 25);
        addMissingArmor("Chain Hauberk",   ItemCategory.ARMOR,   ArmorSlot.CHEST,     "Chainmail chest armor.",        5,0, 0,2,0,0,4,0,0,0, 80);
        addMissingArmor("Leather Cloak",   ItemCategory.ARMOR,   ArmorSlot.BACK,      "A sturdy traveling cloak.",     0,0, 0,0,0,0,1,1,0,0, 20);
        addMissingArmor("Iron Bracers",    ItemCategory.ARMOR,   ArmorSlot.WRISTS,    "Iron wrist guards.",            0,0, 0,1,0,0,1,0,1,0, 18);
        addMissingArmor("Mail Gauntlets",  ItemCategory.ARMOR,   ArmorSlot.HANDS,     "Chainmail gloves.",             0,0, 0,1,0,0,1,0,1,0, 22);
        addMissingArmor("Leather Belt",    ItemCategory.ARMOR,   ArmorSlot.WAIST,     "A reinforced leather belt.",    0,0, 0,0,0,0,1,1,0,0, 15);
        addMissingArmor("Iron Greaves",    ItemCategory.ARMOR,   ArmorSlot.LEGS,      "Iron leg armor.",               0,0, 0,1,0,0,3,0,0,0, 55);
        addMissingArmor("Iron Boots",      ItemCategory.ARMOR,   ArmorSlot.FEET,      "Heavy iron boots.",             0,0, 0,1,0,0,2,0,0,0, 35);

        // Default jewelry
        addMissingArmor("Gold Ring",       ItemCategory.JEWELRY, ArmorSlot.RING,      "A simple gold ring.",           0,0, 0,0,0,1,0,0,0,2, 40);
        addMissingArmor("Silver Necklace", ItemCategory.JEWELRY, ArmorSlot.NECK,      "A delicate silver necklace.",   0,5, 2,0,1,1,0,0,0,0, 60);
        addMissingArmor("Lucky Charm",     ItemCategory.JEWELRY, ArmorSlot.TRINKET,   "Trinket that improves luck.",   0,0, 0,0,0,0,0,0,0,5, 75);
    }

    private void addMissing(String name, ItemCategory cat, String desc,
                            int HP, int MANA,
                            int INT, int STR, int WIS, int CHA,
                            int STA, int AGI, int DEX, int LUK, int value) {
        if (items.stream().anyMatch(it -> it.name.equals(name))) return;
        ItemDef it = new ItemDef(name, cat);
        it.description = desc;
        it.statHp   = HP;   it.statMana = MANA;
        it.statInt  = INT;  it.statStr  = STR;  it.statWis = WIS; it.statCha = CHA;
        it.statSta  = STA;  it.statAgi  = AGI;  it.statDex = DEX; it.statLuk = LUK;
        it.value = value;
        items.add(it);
    }

    private void addMissingArmor(String name, ItemCategory cat, ArmorSlot slot, String desc,
                                 int HP, int MANA,
                                 int INT, int STR, int WIS, int CHA,
                                 int STA, int AGI, int DEX, int LUK, int value) {
        if (items.stream().anyMatch(it -> it.name.equals(name))) return;
        ItemDef it = new ItemDef(name, cat);
        it.armorSlot    = slot;
        it.description  = desc;
        it.statHp   = HP;   it.statMana = MANA;
        it.statInt  = INT;  it.statStr  = STR;  it.statWis = WIS; it.statCha = CHA;
        it.statSta  = STA;  it.statAgi  = AGI;  it.statDex = DEX; it.statLuk = LUK;
        it.value = value;
        items.add(it);
    }

    private void giveToSelf() {
        if (selected == null) {
            statusLabel.setText("Select an item first.");
            return;
        }
        if (client == null || !SessionStore.isLoggedIn()) {
            statusLabel.setText("Not connected.");
            return;
        }
        try {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("itemName", selected.name);
            payload.put("quantity", 1);
            client.send(new Packet(PacketType.INVENTORY_GIVE_ITEM_REQUEST,
                    SessionStore.getToken(), payload));
            statusLabel.setText("Sent \u2713 — check your inventory.");
            statusLabel.setStyle("-fx-text-fill: #50c050; -fx-font-size: 11;");
        } catch (Exception ex) {
            statusLabel.setText("Error: " + ex.getMessage());
            statusLabel.setStyle("-fx-text-fill: #e94560; -fx-font-size: 11;");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addStatRow(GridPane grid, int row, String label, Spinner<Integer> spinner, String color) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 11; -fx-font-weight: bold;");
        grid.add(l, 0, row);
        grid.add(spinner, 1, row);
    }

    private Spinner<Integer> statSpinner() {
        Spinner<Integer> s = new Spinner<>(-999, 999, 0);
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        s.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;");
        return s;
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
