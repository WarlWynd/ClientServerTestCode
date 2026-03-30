package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * Embeddable admin dashboard panel — shown as a tab in GameScreen for admin users.
 *
 * Call {@link #buildView()} once to get the root Node, then {@link #start()} to
 * begin polling, and {@link #stop()} when the user logs out.
 *
 * Packet handling is delegated from GameScreen via {@link #onPacket(Packet)}.
 */
public class AdminPanel {

    private final UDPClient client;

    private Label headerLabel;
    private Label statusLabel;
    private final ObservableList<PlayerRow> rows = FXCollections.observableArrayList();
    private Timeline ticker;

    public AdminPanel(UDPClient client) {
        this.client = client;
    }

    // ── Build ────────────────────────────────────────────────────────────────

    public Node buildView() {
        headerLabel = new Label("Admin Panel — " + SessionStore.getUsername());
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        headerLabel.setTextFill(Color.web("#e0e0ff"));

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.setTextFill(Color.web("#80c080"));

        HBox header = new HBox(headerLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 6, 14));
        header.setStyle("-fx-background-color: #0f0f1e;");

        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(2, 14, 4, 14));
        statusBar.setStyle("-fx-background-color: #0f0f1e;");

        TableView<PlayerRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("""
                -fx-background-color: #1a1a2e;
                -fx-text-fill: #e0e0e0;
                -fx-table-cell-border-color: #2a2a4a;
                """);
        table.setPlaceholder(new Label("No players connected"));

        table.getColumns().addAll(
                strCol("Username",  "username",      180),
                strCol("Connected", "connectedTime", 130),
                strCol("Score",     "score",          70),
                strCol("X",         "x",              60),
                strCol("Y",         "y",              60),
                kickCol(),
                banCol()
        );

        VBox root = new VBox(header, statusBar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1a1a2e;");

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> requestPlayerList()));
        ticker.setCycleCount(Timeline.INDEFINITE);

        return root;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() {
        ticker.play();
        requestPlayerList();
    }

    public void stop() {
        if (ticker != null) ticker.stop();
    }

    // ── Packet handling (called by GameScreen) ────────────────────────────────

    public void onPacket(Packet packet) {
        switch (packet.type) {
            case ADMIN_USER_LIST_RESPONSE -> Platform.runLater(() -> {
                if (!packet.payload.get("success").asBoolean()) return;
                JsonNode players = packet.payload.get("players");
                rows.clear();
                if (players != null && players.isArray()) {
                    for (JsonNode p : players) {
                        rows.add(new PlayerRow(
                                p.get("username").asText(),
                                p.get("joinedAt").asLong(),
                                p.get("x").asDouble(),
                                p.get("y").asDouble(),
                                p.get("score").asInt()));
                    }
                }
                headerLabel.setText(String.format("Admin Panel — %s  |  %d player%s online",
                        SessionStore.getUsername(), rows.size(), rows.size() == 1 ? "" : "s"));
            });
            case ADMIN_KICK_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Kicked: "      + packet.payload.get("username").asText()
                        : "Kick failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok ? "#80c080" : "#e94560");
            });
            case ADMIN_BAN_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Banned: "      + packet.payload.get("username").asText()
                        : "Ban failed: "  + packet.payload.get("message").asText();
                showStatus(msg, ok ? "#80c080" : "#e94560");
            });
        }
    }

    // ── Table helpers ─────────────────────────────────────────────────────────

    private TableColumn<PlayerRow, String> strCol(String title, String field, double width) {
        TableColumn<PlayerRow, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(data -> {
            PlayerRow row = data.getValue();
            return switch (field) {
                case "username"      -> row.username;
                case "connectedTime" -> row.connectedTime;
                case "score"         -> row.score;
                case "x"             -> row.x;
                case "y"             -> row.y;
                default              -> new SimpleStringProperty("");
            };
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> kickCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Action");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("Kick");
            {
                btn.setStyle("""
                        -fx-background-color: #e94560;
                        -fx-text-fill: white;
                        -fx-font-size: 11;
                        -fx-background-radius: 4;
                        -fx-padding: 3 8 3 8;
                        """);
                btn.setOnAction(e -> {
                    PlayerRow row = getTableView().getItems().get(getIndex());
                    doKick(row.username.get());
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> banCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Ban");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("Ban");
            {
                btn.setStyle("""
                        -fx-background-color: #a03030;
                        -fx-text-fill: white;
                        -fx-font-size: 11;
                        -fx-background-radius: 4;
                        -fx-padding: 3 8 3 8;
                        """);
                btn.setOnAction(e -> {
                    PlayerRow row = getTableView().getItems().get(getIndex());
                    doBan(row.username.get());
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });
        return col;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void requestPlayerList() {
        client.send(new Packet(PacketType.ADMIN_USER_LIST_REQUEST,
                SessionStore.getToken(), PacketSerializer.emptyPayload()));
    }

    private void doKick(String username) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        client.send(new Packet(PacketType.ADMIN_KICK_REQUEST, SessionStore.getToken(), payload));
    }

    private void doBan(String username) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("ban", true);
        client.send(new Packet(PacketType.ADMIN_BAN_REQUEST, SessionStore.getToken(), payload));
    }

    private void showStatus(String msg, String color) {
        statusLabel.setTextFill(Color.web(color));
        statusLabel.setText(msg);
        PauseTransition clear = new PauseTransition(Duration.seconds(4));
        clear.setOnFinished(e -> statusLabel.setText(""));
        clear.play();
    }

    // ── Inner model ───────────────────────────────────────────────────────────

    static class PlayerRow {
        final StringProperty username      = new SimpleStringProperty();
        final StringProperty connectedTime = new SimpleStringProperty();
        final StringProperty score         = new SimpleStringProperty();
        final StringProperty x             = new SimpleStringProperty();
        final StringProperty y             = new SimpleStringProperty();
        final long           joinedAt;

        PlayerRow(String username, long joinedAt, double x, double y, int score) {
            this.joinedAt = joinedAt;
            this.username.set(username);
            this.score.set(String.valueOf(score));
            this.x.set(String.format("%.0f", x));
            this.y.set(String.format("%.0f", y));
            updateConnectedTime();
        }

        void updateConnectedTime() {
            long secs = (System.currentTimeMillis() - joinedAt) / 1000;
            if (secs < 60)        connectedTime.set(secs + "s");
            else if (secs < 3600) connectedTime.set((secs / 60) + "m " + (secs % 60) + "s");
            else                  connectedTime.set((secs / 3600) + "h " + ((secs % 3600) / 60) + "m");
        }
    }
}
