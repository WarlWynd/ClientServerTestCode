package com.game.admin.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Quest Dev panel — create and edit quest definitions stored in
 * server/src/main/resources/quests.json.
 *
 * Objective types: KILL_MOB (target + count), HAVE_ITEMS (count only).
 */
public class QuestDevPanel {

    // ── File path ─────────────────────────────────────────────────────────────

    private static final String QUESTS_PATH = "server/src/main/resources/quests.json";

    // ── Model ─────────────────────────────────────────────────────────────────

    public static class ObjectiveDef {
        String type   = "KILL_MOB";
        String target = "any";
        int    count  = 1;

        ObjectiveDef() {}
        ObjectiveDef(String type, String target, int count) {
            this.type   = type;
            this.target = target;
            this.count  = count;
        }
    }

    public static class QuestDef {
        String               id          = "";
        String               name        = "";
        String               description = "";
        List<ObjectiveDef>   objectives  = new ArrayList<>();
        int                  rewardGold  = 0;
        int                  rewardXp    = 0;

        @Override public String toString() {
            return name.isBlank() ? "(unnamed)" : name;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final ObservableList<QuestDef> quests = FXCollections.observableArrayList();
    private ListView<QuestDef>   listView;
    private TextField            idField;
    private TextField            nameField;
    private TextArea             descArea;
    private VBox                 objectivesBox;
    private Spinner<Integer>     goldSpinner;
    private Spinner<Integer>     xpSpinner;
    private Label                statusLabel;
    private boolean              loading = false;

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        // ── Left list ─────────────────────────────────────────────────────────
        listView = new ListView<>(quests);
        listView.setStyle(
                "-fx-background-color: #0f0f1e;" +
                "-fx-control-inner-background: #0f0f1e;" +
                "-fx-border-color: #2a2a4a;");
        listView.setPlaceholder(new Label("No quests."));
        listView.setPrefWidth(200);
        VBox.setVgrow(listView, Priority.ALWAYS);

        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> loadIntoForm(sel));

        Button newBtn = btn("+ New",  "#1e3a1e");
        Button delBtn = btn("Delete", "#5f1e1e");
        newBtn.setMaxWidth(Double.MAX_VALUE);
        delBtn.setMaxWidth(Double.MAX_VALUE);
        newBtn.setOnAction(e -> createNew());
        delBtn.setOnAction(e -> deleteSelected());

        VBox listCol = new VBox(6, listView, newBtn, delBtn);
        listCol.setPrefWidth(200);
        listCol.setMinWidth(200);
        listCol.setMaxWidth(200);

        // ── Right form ────────────────────────────────────────────────────────
        Label idLbl   = fieldLabel("Quest ID");
        idField = field("quest_004");
        idField.setPromptText("quest_001");
        idField.textProperty().addListener((o, ov, nv) -> syncFromForm());

        Label nameLbl = fieldLabel("Name");
        nameField = field("New Quest");
        nameField.textProperty().addListener((o, ov, nv) -> syncFromForm());

        Label descLbl = fieldLabel("Description");
        descArea = new TextArea();
        descArea.setPromptText("Describe the quest here…");
        descArea.setWrapText(true);
        descArea.setPrefRowCount(3);
        descArea.setStyle(
                "-fx-control-inner-background: #0f0f1e;" +
                "-fx-text-fill: #e0e0e0;" +
                "-fx-border-color: #2a2a4a;" +
                "-fx-font-size: 12;");
        descArea.textProperty().addListener((o, ov, nv) -> syncFromForm());

        Label objLbl = sectionLabel("Objectives");
        objectivesBox = new VBox(4);

        Button addObjBtn = btn("+ Add Objective", "#1e2a5f");
        addObjBtn.setOnAction(e -> {
            QuestDef sel = listView.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            sel.objectives.add(new ObjectiveDef());
            rebuildObjectivesBox(sel);
        });

        Label rewardLbl    = sectionLabel("Rewards");
        Label goldLbl      = fieldLabel("Gold");
        goldSpinner        = intSpinner(0, 1_000_000, 0);
        Label xpLbl        = fieldLabel("XP");
        xpSpinner          = intSpinner(0, 1_000_000, 0);

        goldSpinner.valueProperty().addListener((o, ov, nv) -> syncFromForm());
        xpSpinner.valueProperty().addListener((o, ov, nv)   -> syncFromForm());

        HBox rewardRow = new HBox(12,
                goldLbl, goldSpinner,
                xpLbl,   xpSpinner);
        rewardRow.setAlignment(Pos.CENTER_LEFT);

        Button saveBtn = btn("💾 Save All", "#1e5f3a");
        saveBtn.setPrefWidth(140);
        saveBtn.setOnAction(e -> saveAll());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #80c080; -fx-font-size: 11;");

        HBox saveRow = new HBox(10, saveBtn, statusLabel);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane formScroll = new ScrollPane(new VBox(8,
                idLbl,   idField,
                nameLbl, nameField,
                descLbl, descArea,
                new Separator(),
                objLbl,  objectivesBox, addObjBtn,
                new Separator(),
                rewardLbl,
                rewardRow,
                new Separator(),
                saveRow));
        formScroll.setFitToWidth(true);
        formScroll.setStyle("-fx-background-color: #1a1a2e; -fx-background: #1a1a2e;");
        ((VBox) formScroll.getContent()).setPadding(new Insets(8));
        VBox.setVgrow(formScroll, Priority.ALWAYS);

        VBox formCol = new VBox(formScroll);
        VBox.setVgrow(formCol, Priority.ALWAYS);
        HBox.setHgrow(formCol, Priority.ALWAYS);

        HBox body = new HBox(8, listCol, formCol);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.setPadding(new Insets(8));
        body.setStyle("-fx-background-color: #1a1a2e;");

        load();
        return body;
    }

    // ── Form helpers ──────────────────────────────────────────────────────────

    private void loadIntoForm(QuestDef q) {
        loading = true;
        try {
            if (q == null) {
                idField.setText("");
                nameField.setText("");
                descArea.setText("");
                objectivesBox.getChildren().clear();
                goldSpinner.getValueFactory().setValue(0);
                xpSpinner.getValueFactory().setValue(0);
                return;
            }
            idField.setText(q.id);
            nameField.setText(q.name);
            descArea.setText(q.description);
            goldSpinner.getValueFactory().setValue(q.rewardGold);
            xpSpinner.getValueFactory().setValue(q.rewardXp);
            rebuildObjectivesBox(q);
        } finally {
            loading = false;
        }
    }

    private void syncFromForm() {
        if (loading) return;
        QuestDef q = listView.getSelectionModel().getSelectedItem();
        if (q == null) return;
        q.id          = idField.getText().trim();
        q.name        = nameField.getText().trim();
        q.description = descArea.getText().trim();
        q.rewardGold  = goldSpinner.getValue();
        q.rewardXp    = xpSpinner.getValue();
        listView.refresh();
    }

    private void rebuildObjectivesBox(QuestDef q) {
        objectivesBox.getChildren().clear();
        for (int i = 0; i < q.objectives.size(); i++) {
            objectivesBox.getChildren().add(buildObjectiveRow(q, i));
        }
    }

    private Node buildObjectiveRow(QuestDef q, int index) {
        ObjectiveDef obj = q.objectives.get(index);

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("KILL_MOB", "HAVE_ITEMS");
        typeCombo.setValue(obj.type);
        typeCombo.setStyle("-fx-background-color: #16213e; -fx-text-fill: #e0e0e0;" +
                           "-fx-border-color: #3a3a6a; -fx-border-radius: 4;");

        Label targetLbl  = fieldLabel("Target");
        TextField targetF = field(obj.target);
        targetF.setPrefWidth(90);

        Label countLbl   = fieldLabel("Count");
        Spinner<Integer> countSp = intSpinner(1, 9999, obj.count);
        countSp.setPrefWidth(72);

        Button removeBtn = new Button("✕");
        removeBtn.setStyle("-fx-background-color: #5f1e1e; -fx-text-fill: white;" +
                           "-fx-font-size: 10; -fx-padding: 3 7 3 7; -fx-background-radius: 3;");

        HBox row = new HBox(6, typeCombo, targetLbl, targetF, countLbl, countSp, removeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 0, 2, 0));

        // Show/hide target based on type
        boolean killMob = "KILL_MOB".equals(obj.type);
        targetLbl.setVisible(killMob);
        targetLbl.setManaged(killMob);
        targetF.setVisible(killMob);
        targetF.setManaged(killMob);

        typeCombo.setOnAction(e -> {
            obj.type = typeCombo.getValue();
            boolean km = "KILL_MOB".equals(obj.type);
            targetLbl.setVisible(km);
            targetLbl.setManaged(km);
            targetF.setVisible(km);
            targetF.setManaged(km);
        });
        targetF.textProperty().addListener((o, ov, nv) -> obj.target = nv.trim());
        countSp.valueProperty().addListener((o, ov, nv) -> obj.count = nv);
        removeBtn.setOnAction(e -> {
            q.objectives.remove(index);
            rebuildObjectivesBox(q);
        });

        return row;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void createNew() {
        QuestDef q = new QuestDef();
        q.id   = "quest_" + String.format("%03d", quests.size() + 1);
        q.name = "New Quest";
        q.objectives.add(new ObjectiveDef("KILL_MOB", "any", 1));
        quests.add(q);
        listView.getSelectionModel().select(q);
    }

    private void deleteSelected() {
        QuestDef sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        quests.remove(sel);
        loadIntoForm(null);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        File f = new File(QUESTS_PATH);
        if (!f.exists()) return;
        try {
            JsonNode arr = new ObjectMapper().readTree(f);
            if (!arr.isArray()) return;
            List<QuestDef> loaded = new ArrayList<>();
            for (JsonNode node : arr) {
                QuestDef q = new QuestDef();
                q.id          = node.path("id").asText();
                q.name        = node.path("name").asText();
                q.description = node.path("description").asText();

                JsonNode rewards = node.path("rewards");
                q.rewardGold = rewards.path("gold").asInt(0);
                q.rewardXp   = rewards.path("xp").asInt(0);

                JsonNode objs = node.path("objectives");
                if (objs.isArray()) {
                    for (JsonNode o : objs) {
                        ObjectiveDef od = new ObjectiveDef();
                        od.type   = o.path("type").asText("KILL_MOB");
                        od.target = o.path("target").asText("any");
                        od.count  = o.path("count").asInt(1);
                        q.objectives.add(od);
                    }
                }
                loaded.add(q);
            }
            quests.setAll(loaded);
            if (!quests.isEmpty()) listView.getSelectionModel().select(0);
        } catch (Exception ex) {
            setStatus("Load error: " + ex.getMessage());
        }
    }

    private void saveAll() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode arr = mapper.createArrayNode();
            for (QuestDef q : quests) {
                ObjectNode node = mapper.createObjectNode();
                node.put("id",          q.id);
                node.put("name",        q.name);
                node.put("description", q.description);

                ArrayNode objs = node.putArray("objectives");
                for (ObjectiveDef od : q.objectives) {
                    ObjectNode o = objs.addObject();
                    o.put("type", od.type);
                    if ("KILL_MOB".equals(od.type)) o.put("target", od.target);
                    o.put("count", od.count);
                }

                ObjectNode rewards = node.putObject("rewards");
                rewards.put("gold", q.rewardGold);
                rewards.put("xp",   q.rewardXp);

                arr.add(node);
            }
            File f = new File(QUESTS_PATH);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, arr);
            setStatus("Saved " + quests.size() + " quest(s).");
        } catch (Exception ex) {
            setStatus("Save error: " + ex.getMessage());
        }
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
    }

    private static Button btn(String text, String bg) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: #e0e0e0;" +
                   "-fx-font-size: 12; -fx-padding: 5 12 5 12; -fx-background-radius: 4;");
        return b;
    }

    private static Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #7070a0; -fx-font-size: 11;");
        return l;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #a0a0d0; -fx-font-size: 12; -fx-font-weight: bold;");
        return l;
    }

    private static TextField field(String initial) {
        TextField t = new TextField(initial);
        t.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                   "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5; -fx-font-size: 12;");
        return t;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int init) {
        Spinner<Integer> s = new Spinner<>(min, max, init);
        s.setEditable(true);
        s.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a; -fx-border-radius: 4;");
        s.getEditor().setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0; -fx-font-size: 12;");
        return s;
    }
}
