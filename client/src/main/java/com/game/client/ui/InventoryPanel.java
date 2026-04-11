package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inventory panel — shows the current character's inventory fetched from the server.
 *
 * Call {@link #build()} to get the root Node, then route incoming packets via
 * {@link #onPacket(Packet)} from GameScreen.onPacket().
 *
 * The panel sends INVENTORY_REQUEST on build to auto-populate on first open.
 * Press the Refresh button at any time to re-fetch.
 */
public class InventoryPanel {

    private final UDPClient client;
    private final ObservableList<InventoryRow> rows = FXCollections.observableArrayList();
    private TableView<InventoryRow> table;
    private Label statusLabel;
    private Label platinumLabel;
    private Label goldLabel;
    private Label silverLabel;
    private Label bronzeLabel;
    private Runnable onCurrencyUpdated; // optional callback to notify GameScreen

    // Armor tab — slot labels keyed by ArmorSlot
    private final java.util.EnumMap<ItemRegistryPanel.ArmorSlot, Label> slotLabels =
            new java.util.EnumMap<>(ItemRegistryPanel.ArmorSlot.class);
    private Label armorStatSummary;

    // ── Row model ─────────────────────────────────────────────────────────────

    public static class InventoryRow {
        private final long   id;
        private final String itemName;
        private final int    quantity;
        boolean equipped; // mutable for equip toggle without re-fetch

        public InventoryRow(long id, String itemName, int quantity, boolean equipped) {
            this.id       = id;
            this.itemName = itemName;
            this.quantity = quantity;
            this.equipped = equipped;
        }

        public long   getId()        { return id; }
        public String getItemName()  { return itemName; }
        public int    getQuantity()  { return quantity; }
        public String getEquipped()  { return equipped ? "\u2713" : ""; }
        public boolean isEquipped()  { return equipped; }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    public InventoryPanel(UDPClient client) {
        this.client = client;
    }

    /** Optional callback invoked on the FX thread after currency labels are updated. */
    public void setOnCurrencyUpdated(Runnable r) { this.onCurrencyUpdated = r; }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        // ── Currency bar (shared across tabs) ─────────────────────────────────
        platinumLabel = coinLabel("0", "#b8d4e8");
        goldLabel     = coinLabel("0", "#ffd700");
        silverLabel   = coinLabel("0", "#c0c0c0");
        bronzeLabel   = coinLabel("0", "#cd7f32");

        HBox currencyBar = new HBox(20,
                coinRow("\u25CF Platinum", "#b8d4e8", platinumLabel),
                coinRow("\u25CF Gold",     "#ffd700", goldLabel),
                coinRow("\u25CF Silver",   "#c0c0c0", silverLabel),
                coinRow("\u25CF Bronze",   "#cd7f32", bronzeLabel));
        currencyBar.setAlignment(Pos.CENTER_LEFT);
        currencyBar.setPadding(new Insets(6, 10, 6, 10));
        currencyBar.setStyle("-fx-background-color: #11112a; -fx-background-radius: 4;");

        // ── Items tab ─────────────────────────────────────────────────────────
        TableColumn<InventoryRow, String>  nameCol     = new TableColumn<>("Item");
        TableColumn<InventoryRow, Integer> qtyCol      = new TableColumn<>("Qty");
        TableColumn<InventoryRow, String>  equippedCol = new TableColumn<>("Equipped");

        nameCol.setCellValueFactory(new PropertyValueFactory<>("itemName"));
        qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        equippedCol.setCellValueFactory(new PropertyValueFactory<>("equipped"));

        nameCol.setPrefWidth(240);
        qtyCol.setPrefWidth(55);
        equippedCol.setPrefWidth(70);

        table = new TableView<>(rows);
        table.getColumns().addAll(nameCol, qtyCol, equippedCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle(
                "-fx-background-color: #0f0f1e;" +
                "-fx-control-inner-background: #0f0f1e;" +
                "-fx-table-cell-border-color: #2a2a4a;" +
                "-fx-table-header-border-color: #3a3a6a;");
        table.setPlaceholder(new Label("No items in inventory."));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button refreshBtn = btn("Refresh",         "#1e3a5f");
        Button equipBtn   = btn("Equip / Unequip", "#1e5f3a");
        Button dropOneBtn = btn("Drop (1)",        "#5f1e1e");
        Button dropAllBtn = btn("Drop All",        "#7b241c");

        refreshBtn.setOnAction(e -> requestInventory());
        equipBtn.setOnAction(e   -> toggleEquip());
        dropOneBtn.setOnAction(e -> dropSelected(1));
        dropAllBtn.setOnAction(e -> {
            InventoryRow row = table.getSelectionModel().getSelectedItem();
            if (row != null) dropSelected(row.getQuantity());
        });

        HBox btnRow = new HBox(8, refreshBtn, equipBtn, dropOneBtn, dropAllBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        Label hint = new Label("Select an item to equip/drop it.  Items are shared across sessions.");
        hint.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 11;");
        hint.setWrapText(true);

        VBox itemsContent = new VBox(10, table, btnRow, statusLabel, hint);
        itemsContent.setPadding(new Insets(10));
        itemsContent.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(table, Priority.ALWAYS);

        Tab itemsTab = new Tab("Items", itemsContent);
        itemsTab.setClosable(false);

        // ── Armor tab ─────────────────────────────────────────────────────────
        Tab armorTab = new Tab("Armor", buildArmorTab());
        armorTab.setClosable(false);

        // Rebuild armor view whenever the armor tab is selected
        armorTab.setOnSelectionChanged(e -> {
            if (armorTab.isSelected()) refreshArmorTab();
        });

        TabPane subTabs = new TabPane(armorTab, itemsTab);
        subTabs.setStyle("-fx-background-color: #1a1a2e; -fx-tab-min-width: 80;");
        VBox.setVgrow(subTabs, Priority.ALWAYS);

        VBox root = new VBox(0, currencyBar, subTabs);
        root.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(subTabs, Priority.ALWAYS);

        requestInventory();
        return root;
    }

    // ── Armor tab ─────────────────────────────────────────────────────────────

    private static final ItemRegistryPanel.ArmorSlot[][] SLOT_GRID = {
        { ItemRegistryPanel.ArmorSlot.HEAD,      ItemRegistryPanel.ArmorSlot.NECK      },
        { ItemRegistryPanel.ArmorSlot.SHOULDERS, ItemRegistryPanel.ArmorSlot.CHEST     },
        { ItemRegistryPanel.ArmorSlot.BACK,      ItemRegistryPanel.ArmorSlot.WAIST     },
        { ItemRegistryPanel.ArmorSlot.WRISTS_L,  ItemRegistryPanel.ArmorSlot.WRISTS_R  },
        { ItemRegistryPanel.ArmorSlot.HANDS,     ItemRegistryPanel.ArmorSlot.LEGS      },
        { ItemRegistryPanel.ArmorSlot.FEET,      ItemRegistryPanel.ArmorSlot.TRINKET   },
        { ItemRegistryPanel.ArmorSlot.RING_L,     ItemRegistryPanel.ArmorSlot.RING_R     },
        { ItemRegistryPanel.ArmorSlot.EARRING_L,  ItemRegistryPanel.ArmorSlot.EARRING_R  },
    };

    private Node buildArmorTab() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));

        for (int r = 0; r < SLOT_GRID.length; r++) {
            for (int c = 0; c < SLOT_GRID[r].length; c++) {
                ItemRegistryPanel.ArmorSlot slot = SLOT_GRID[r][c];
                if (slot == null) continue;
                VBox cell = buildSlotCell(slot);
                grid.add(cell, c, r);
                GridPane.setHgrow(cell, Priority.ALWAYS);
            }
        }

        armorStatSummary = new Label("");
        armorStatSummary.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");
        armorStatSummary.setWrapText(true);

        Label statsTitle = new Label("Equipped Stat Totals");
        statsTitle.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 12; -fx-font-weight: bold;");

        VBox content = new VBox(12, grid, statsTitle, armorStatSummary);
        content.setPadding(new Insets(4, 10, 10, 10));
        content.setStyle("-fx-background-color: #1a1a2e;");

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: #1a1a2e; -fx-background: #1a1a2e;");
        return sp;
    }

    private VBox buildSlotCell(ItemRegistryPanel.ArmorSlot slot) {
        Label slotName = new Label(slot.label());
        slotName.setStyle("-fx-text-fill: #7080a0; -fx-font-size: 10; -fx-font-weight: bold;");

        Label itemLabel = new Label("(empty)");
        itemLabel.setStyle("-fx-text-fill: #505060; -fx-font-size: 12; -fx-font-style: italic;");
        itemLabel.setMaxWidth(Double.MAX_VALUE);
        itemLabel.setWrapText(true);

        VBox cell = new VBox(3, slotName, itemLabel);
        cell.setPadding(new Insets(8, 10, 8, 10));
        cell.setMinWidth(160);
        cell.setPrefWidth(200);
        cell.setStyle(
            "-fx-background-color: #0f0f1e;" +
            "-fx-border-color: #2a2a4a;" +
            "-fx-border-radius: 4;" +
            "-fx-background-radius: 4;");

        slotLabels.put(slot, itemLabel);
        return cell;
    }

    private void refreshArmorTab() {
        // Clear all slots
        for (Label lbl : slotLabels.values()) {
            lbl.setText("(empty)");
            lbl.setStyle("-fx-text-fill: #505060; -fx-font-size: 12; -fx-font-style: italic;");
        }

        Map<String, ItemRegistryPanel.ItemDef> itemMap = ItemRegistryPanel.loadItemMap();

        // Stat totals: ARMOR,HP,MANA,INT,STR,WIS,CHA,STA,AGI,DEX,LUK
        int[] totals = new int[11];

        for (InventoryRow row : rows) {
            if (!row.isEquipped()) continue;
            ItemRegistryPanel.ItemDef def = itemMap.get(row.getItemName());
            if (def == null) continue;
            if (def.category != ItemRegistryPanel.ItemCategory.ARMOR &&
                def.category != ItemRegistryPanel.ItemCategory.JEWELRY) continue;

            ItemRegistryPanel.ArmorSlot slot = def.armorSlot != null
                    ? def.armorSlot : ItemRegistryPanel.ArmorSlot.NONE;

            Label lbl = slotLabels.get(slot);
            if (lbl != null) {
                lbl.setText(row.getItemName());
                lbl.setStyle("-fx-text-fill: #d0d8e0; -fx-font-size: 12; -fx-font-style: normal;");
            }

            totals[0]  += def.statArmor;
            totals[1]  += def.statHp;   totals[2]  += def.statMana;
            totals[3]  += def.statInt;  totals[4]  += def.statStr;
            totals[5]  += def.statWis;  totals[6]  += def.statCha;
            totals[7]  += def.statSta;  totals[8]  += def.statAgi;
            totals[9]  += def.statDex;  totals[10] += def.statLuk;
        }

        if (armorStatSummary != null) {
            String[] names = {"ARMOR","HP","MANA","INT","STR","WIS","CHA","STA","AGI","DEX","LUK"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 11; i++) {
                if (totals[i] != 0) {
                    if (sb.length() > 0) sb.append("   ");
                    sb.append(names[i]).append(": ").append(totals[i] > 0 ? "+" : "").append(totals[i]);
                }
            }
            armorStatSummary.setText(sb.length() == 0 ? "No bonuses from equipped armor." : sb.toString());
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    public void requestInventory() {
        if (client == null) return;
        try {
            client.send(new Packet(PacketType.INVENTORY_REQUEST,
                    SessionStore.getToken(), PacketSerializer.emptyPayload()));
        } catch (Exception ex) {
            setStatus("Request failed: " + ex.getMessage());
        }
    }

    private void toggleEquip() {
        InventoryRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) { setStatus("Select an item first."); return; }
        try {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("entryId",  row.getId());
            payload.put("equipped", !row.isEquipped());
            client.send(new Packet(PacketType.INVENTORY_EQUIP_REQUEST,
                    SessionStore.getToken(), payload));
        } catch (Exception ex) {
            setStatus("Error: " + ex.getMessage());
        }
    }

    private void dropSelected(int qty) {
        InventoryRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) { setStatus("Select an item first."); return; }
        try {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("entryId",  row.getId());
            payload.put("quantity", qty);
            client.send(new Packet(PacketType.INVENTORY_DROP_REQUEST,
                    SessionStore.getToken(), payload));
        } catch (Exception ex) {
            setStatus("Error: " + ex.getMessage());
        }
    }

    // ── Packet handling (called from GameScreen.onPacket) ─────────────────────

    public void onPacket(Packet packet) {
        switch (packet.type) {
            case INVENTORY_RESPONSE -> {
                JsonNode items = packet.payload.get("items");
                List<InventoryRow> newRows = new ArrayList<>();
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        newRows.add(new InventoryRow(
                                item.get("id").asLong(),
                                item.get("itemName").asText(),
                                item.get("quantity").asInt(),
                                item.get("equipped").asBoolean()));
                    }
                }
                int pt = packet.payload.has("platinum") ? packet.payload.get("platinum").asInt() : 0;
                int gd = packet.payload.has("gold")     ? packet.payload.get("gold").asInt()     : 0;
                int sv = packet.payload.has("silver")   ? packet.payload.get("silver").asInt()   : 0;
                int br = packet.payload.has("bronze")   ? packet.payload.get("bronze").asInt()   : 0;
                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    setStatus("Loaded " + rows.size() + " item(s).");
                    if (platinumLabel != null) platinumLabel.setText(String.valueOf(pt));
                    if (goldLabel     != null) goldLabel    .setText(String.valueOf(gd));
                    if (silverLabel   != null) silverLabel  .setText(String.valueOf(sv));
                    if (bronzeLabel   != null) bronzeLabel  .setText(String.valueOf(br));
                    if (onCurrencyUpdated != null) onCurrencyUpdated.run();
                    refreshArmorTab();
                });
            }
            case INVENTORY_EQUIP_RESPONSE -> {
                boolean ok       = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                long    entryId  = packet.payload.has("entryId")  ? packet.payload.get("entryId").asLong()     : -1L;
                boolean equipped = packet.payload.has("equipped") ? packet.payload.get("equipped").asBoolean() : false;
                if (ok) {
                    Platform.runLater(() -> {
                        rows.stream().filter(r -> r.getId() == entryId).findFirst().ifPresent(r -> {
                            r.equipped = equipped;
                            table.refresh();
                        });
                        setStatus(equipped ? "Item equipped." : "Item unequipped.");
                        refreshArmorTab();
                    });
                } else {
                    Platform.runLater(() -> setStatus("Equip failed."));
                }
            }
            case INVENTORY_DROP_RESPONSE -> {
                boolean ok = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                if (ok) requestInventory();
                Platform.runLater(() -> setStatus(ok ? "Item dropped." : "Drop failed."));
            }
            case INVENTORY_GIVE_ITEM_RESPONSE -> {
                boolean ok  = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                String  msg = packet.payload.has("message") ? packet.payload.get("message").asText("") : "";
                if (ok) requestInventory();
                Platform.runLater(() -> setStatus(msg));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
    }

    private static Label coinLabel(String text, String color) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12; -fx-font-weight: bold;");
        return l;
    }

    private static HBox coinRow(String name, String color, Label amtLabel) {
        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12; -fx-font-weight: bold;");
        HBox row = new HBox(6, nameLbl, amtLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button btn(String text, String bgColor) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + bgColor + ";" +
                "-fx-text-fill: #e0e0e0;" +
                "-fx-font-size: 12;" +
                "-fx-padding: 5 12 5 12;" +
                "-fx-background-radius: 4;");
        return b;
    }
}
