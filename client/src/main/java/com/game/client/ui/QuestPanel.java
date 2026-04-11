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
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest panel — lists all quests and their status for the current character.
 *
 * Call {@link #build()} to get the root Node, then route incoming packets via
 * {@link #onPacket(Packet)} from GameScreen.onPacket().
 *
 * Status values: AVAILABLE, ACTIVE, COMPLETED, ABANDONED.
 */
public class QuestPanel {

    private final UDPClient client;
    private final ObservableList<QuestRow> rows = FXCollections.observableArrayList();
    private ListView<QuestRow> listView;
    private Label  nameLabel;
    private Label  statusLabel;
    private TextArea descArea;
    private Label  objectivesLabel;
    private Label  rewardsLabel;
    private Label  feedbackLabel;
    private Button acceptBtn;
    private Button abandonBtn;

    // ── Row model ─────────────────────────────────────────────────────────────

    public static class QuestRow {
        final String   id;
        final String   name;
        final String   description;
        final String   status;
        final int      killProgress;
        final JsonNode objectives;
        final JsonNode rewards;

        QuestRow(String id, String name, String description,
                 String status, int killProgress, JsonNode objectives, JsonNode rewards) {
            this.id           = id;
            this.name         = name;
            this.description  = description;
            this.status       = status;
            this.killProgress = killProgress;
            this.objectives   = objectives;
            this.rewards      = rewards;
        }

        @Override public String toString() {
            String badge = switch (status) {
                case "ACTIVE"    -> "[Active] ";
                case "COMPLETED" -> "[Done] ";
                case "ABANDONED" -> "[Abandoned] ";
                default          -> "";
            };
            return badge + name;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    public QuestPanel(UDPClient client) {
        this.client = client;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node build() {
        Label title = new Label("Quests");
        title.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 16; -fx-font-weight: bold;");

        // ── Quest list ────────────────────────────────────────────────────────
        listView = new ListView<>(rows);
        listView.setPrefWidth(220);
        listView.setStyle(
                "-fx-background-color: #0f0f1e;" +
                "-fx-control-inner-background: #0f0f1e;" +
                "-fx-border-color: #2a2a4a;");
        listView.setPlaceholder(new Label("No quests found."));
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> showDetail(sel));
        VBox.setVgrow(listView, Priority.ALWAYS);

        Button refreshBtn = btn("Refresh", "#1e3a5f");
        refreshBtn.setOnAction(e -> requestQuestList());

        VBox listCol = new VBox(6, listView, refreshBtn);
        listCol.setPrefWidth(220);

        // ── Detail panel ──────────────────────────────────────────────────────
        nameLabel = new Label("");
        nameLabel.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 14; -fx-font-weight: bold;");
        nameLabel.setWrapText(true);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #9090c0; -fx-font-size: 12;");

        descArea = new TextArea();
        descArea.setEditable(false);
        descArea.setWrapText(true);
        descArea.setPrefRowCount(4);
        descArea.setStyle(
                "-fx-control-inner-background: #0f0f1e;" +
                "-fx-text-fill: #c0c0d0;" +
                "-fx-font-size: 12;");

        objectivesLabel = new Label("");
        objectivesLabel.setStyle("-fx-text-fill: #a0c0e0; -fx-font-size: 12;");
        objectivesLabel.setWrapText(true);

        rewardsLabel = new Label("");
        rewardsLabel.setStyle("-fx-text-fill: #ffd700; -fx-font-size: 12;");

        acceptBtn  = btn("Accept Quest",  "#1e5f3a");
        abandonBtn = btn("Abandon Quest", "#5f1e1e");

        acceptBtn.setOnAction(e  -> acceptSelected());
        abandonBtn.setOnAction(e -> abandonSelected());

        HBox btnRow = new HBox(8, acceptBtn, abandonBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        feedbackLabel = new Label("");
        feedbackLabel.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        Label sectionObj = sectionLabel("Objectives:");
        Label sectionRew = sectionLabel("Rewards:");

        VBox detailCol = new VBox(8,
                nameLabel, statusLabel,
                new Separator(),
                descArea,
                sectionObj, objectivesLabel,
                sectionRew, rewardsLabel,
                btnRow, feedbackLabel);
        detailCol.setPadding(new Insets(4, 0, 0, 8));
        VBox.setVgrow(detailCol, Priority.ALWAYS);
        HBox.setHgrow(detailCol, Priority.ALWAYS);

        HBox body = new HBox(10, listCol, detailCol);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox root = new VBox(10, title, body);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: #1a1a2e;");

        showDetail(null);
        requestQuestList();
        return root;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    public void requestQuestList() {
        if (client == null) return;
        try {
            client.send(new Packet(PacketType.QUEST_LIST_REQUEST,
                    SessionStore.getToken(), PacketSerializer.emptyPayload()));
        } catch (Exception ex) {
            setFeedback("Request failed: " + ex.getMessage());
        }
    }

    private void acceptSelected() {
        QuestRow row = listView.getSelectionModel().getSelectedItem();
        if (row == null) { setFeedback("Select a quest first."); return; }
        if (!"AVAILABLE".equals(row.status)) { setFeedback("Quest is already " + row.status.toLowerCase() + "."); return; }
        try {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("questId", row.id);
            client.send(new Packet(PacketType.QUEST_ACCEPT_REQUEST,
                    SessionStore.getToken(), payload));
        } catch (Exception ex) {
            setFeedback("Error: " + ex.getMessage());
        }
    }

    private void abandonSelected() {
        QuestRow row = listView.getSelectionModel().getSelectedItem();
        if (row == null) { setFeedback("Select a quest first."); return; }
        if (!"ACTIVE".equals(row.status)) { setFeedback("Only active quests can be abandoned."); return; }
        try {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("questId", row.id);
            client.send(new Packet(PacketType.QUEST_ABANDON_REQUEST,
                    SessionStore.getToken(), payload));
        } catch (Exception ex) {
            setFeedback("Error: " + ex.getMessage());
        }
    }

    // ── Packet handling (called from GameScreen.onPacket) ─────────────────────

    public void onPacket(Packet packet) {
        switch (packet.type) {
            case QUEST_LIST_RESPONSE -> {
                JsonNode quests = packet.payload.get("quests");
                List<QuestRow> newRows = new ArrayList<>();
                if (quests != null && quests.isArray()) {
                    for (JsonNode q : quests) {
                        newRows.add(new QuestRow(
                                q.get("id").asText(),
                                q.get("name").asText(),
                                q.get("description").asText(),
                                q.get("status").asText(),
                                q.has("killProgress") ? q.get("killProgress").asInt() : 0,
                                q.get("objectives"),
                                q.get("rewards")));
                    }
                }
                // Sort: ACTIVE first, then AVAILABLE, then COMPLETED
                newRows.sort((a, b) -> statusOrder(a.status) - statusOrder(b.status));
                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    setFeedback("Loaded " + rows.size() + " quest(s).");
                });
            }
            case QUEST_ACCEPT_RESPONSE -> {
                boolean ok  = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                String  msg = packet.payload.has("message") ? packet.payload.get("message").asText("") : "";
                if (ok) requestQuestList();
                Platform.runLater(() -> setFeedback(msg.isEmpty() ? (ok ? "Quest accepted." : "Accept failed.") : msg));
            }
            case QUEST_ABANDON_RESPONSE -> {
                boolean ok = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                if (ok) requestQuestList();
                Platform.runLater(() -> setFeedback(ok ? "Quest abandoned." : "Abandon failed."));
            }
        }
    }

    // ── Detail display ────────────────────────────────────────────────────────

    private void showDetail(QuestRow row) {
        if (row == null) {
            nameLabel.setText("");
            statusLabel.setText("");
            descArea.setText("");
            objectivesLabel.setText("");
            rewardsLabel.setText("");
            acceptBtn.setDisable(true);
            abandonBtn.setDisable(true);
            return;
        }
        nameLabel.setText(row.name);
        statusLabel.setText("Status: " + formatStatus(row.status));
        descArea.setText(row.description);
        objectivesLabel.setText(formatObjectivesWithProgress(row.objectives, row.killProgress));
        rewardsLabel.setText(formatRewards(row.rewards));
        acceptBtn.setDisable(!"AVAILABLE".equals(row.status));
        abandonBtn.setDisable(!"ACTIVE".equals(row.status));
    }

    private String formatStatus(String status) {
        return switch (status) {
            case "ACTIVE"    -> "Active";
            case "COMPLETED" -> "Completed";
            case "ABANDONED" -> "Abandoned";
            default          -> "Available";
        };
    }

    private String formatObjectives(JsonNode objectives) {
        return formatObjectivesWithProgress(objectives, 0);
    }

    private String formatObjectivesWithProgress(JsonNode objectives, int killProgress) {
        if (objectives == null || !objectives.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode obj : objectives) {
            String type   = obj.has("type")   ? obj.get("type").asText()   : "";
            String target = obj.has("target") ? obj.get("target").asText() : "";
            int    count  = obj.has("count")  ? obj.get("count").asInt()   : 1;
            switch (type) {
                case "KILL_MOB" -> sb.append("  Kill ")
                      .append("any".equals(target) ? "" : target + " ")
                      .append(count).append(" enemy/enemies")
                      .append(killProgress > 0 ? "  (" + killProgress + "/" + count + ")" : "")
                      .append("\n");
                case "HAVE_ITEMS" -> sb.append("  Have ").append(count).append(" item(s) in inventory\n");
                default           -> sb.append("  ").append(type).append(" (").append(count).append(")\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private String formatRewards(JsonNode rewards) {
        if (rewards == null) return "";
        StringBuilder sb = new StringBuilder();
        if (rewards.has("gold")) sb.append("  Gold: ").append(rewards.get("gold").asInt()).append("\n");
        if (rewards.has("xp"))   sb.append("  XP: ").append(rewards.get("xp").asInt()).append("\n");
        return sb.toString().stripTrailing();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int statusOrder(String status) {
        return switch (status) {
            case "ACTIVE"    -> 0;
            case "AVAILABLE" -> 1;
            case "COMPLETED" -> 2;
            default          -> 3;
        };
    }

    private void setFeedback(String msg) {
        if (feedbackLabel != null) feedbackLabel.setText(msg);
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #7070a0; -fx-font-size: 11; -fx-font-weight: bold;");
        return l;
    }

    private static Button btn(String text, String bgColor) {
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
