package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AdminPanel.class);

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

        Button deployBtn = new Button("Deploy & Restart");
        deployBtn.setStyle("""
                -fx-background-color: #1a4a1a;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 4 12 4 12;
                """);
        deployBtn.setOnAction(e -> confirmDeploy());

        Button restartBtn = new Button("Restart");
        restartBtn.setStyle("""
                -fx-background-color: #7a3000;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 4 12 4 12;
                """);
        restartBtn.setOnAction(e -> confirmRestart());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(headerLabel, headerSpacer, deployBtn, restartBtn);
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
                strCol("Email",          "email",         180),
                strCol("Username",       "username",      130),
                strCol("Character Name", "characterName", 130),
                strCol("IP",             "ip",            120),
                strCol("Connected", "connectedTime", 100),
                strCol("Score",     "score",          55),
                strCol("X",         "x",              45),
                strCol("Y",         "y",              45),
                adminCol(),
                kickCol(),
                banCol()
        );

        // ── Connection settings ───────────────────────────────────────────────
        VBox connSection = buildConnectionSection();

        VBox root = new VBox(header, statusBar, connSection, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1a1a2e;");

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> requestPlayerList()));
        ticker.setCycleCount(Timeline.INDEFINITE);

        return root;
    }

    // ── Connection section ────────────────────────────────────────────────────

    private VBox buildConnectionSection() {
        Label hostLbl = new Label("Server Host");
        hostLbl.setMinWidth(100);
        hostLbl.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 12;");

        TextField hostField = new TextField(AppSettings.getServerHost());
        hostField.setPrefWidth(200);
        hostField.setStyle("""
                -fx-background-color: #16213e;
                -fx-text-fill: #e0e0e0;
                -fx-border-color: #3a3a6a;
                -fx-border-radius: 4;
                -fx-background-radius: 4;
                -fx-padding: 5;
                """);

        Label portLbl = new Label("Server Port");
        portLbl.setMinWidth(100);
        portLbl.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 12;");

        TextField portField = new TextField(String.valueOf(AppSettings.getServerPort()));
        portField.setPrefWidth(80);
        portField.setStyle(hostField.getStyle());

        Label connStatus = new Label();
        connStatus.setStyle("-fx-font-size: 11;");

        Button saveBtn = new Button("Save");
        saveBtn.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 5 14 5 14;
                """);
        saveBtn.setOnAction(e -> {
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) {
                connStatus.setText("Invalid port.");
                connStatus.setStyle("-fx-font-size: 11; -fx-text-fill: #e94560;");
                return;
            }
            AppSettings.setServerHost(hostField.getText().trim());
            AppSettings.setServerPort(port);
            boolean ok = AppSettings.save();
            connStatus.setText(ok ? "Saved. Restart to apply." : "Could not write settings file.");
            connStatus.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "#50c050" : "#e94560") + ";");
        });

        HBox hostRow = new HBox(10, hostLbl, hostField);
        hostRow.setAlignment(Pos.CENTER_LEFT);

        HBox portRow = new HBox(10, portLbl, portField);
        portRow.setAlignment(Pos.CENTER_LEFT);

        HBox btnRow = new HBox(10, saveBtn, connStatus);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Label sectionTitle = new Label("Connection");
        sectionTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        sectionTitle.setTextFill(Color.web("#e94560"));

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #2a2a4a;");

        VBox section = new VBox(6, sectionTitle, sep, hostRow, portRow, btnRow);
        section.setPadding(new Insets(10, 14, 10, 14));
        section.setStyle("-fx-background-color: #0f0f1e;");
        return section;
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
            case ADMIN_USER_LIST_RESPONSE -> {
                boolean success = packet.payload.get("success").asBoolean();
                if (!success) {
                    String msg = packet.payload.has("message")
                            ? packet.payload.get("message").asText() : "Access denied";
                    log.warn("ADMIN_USER_LIST_RESPONSE denied: {}", msg);
                    Platform.runLater(() -> showStatus("Access denied: " + msg, "#e94560"));
                    return;
                }
                JsonNode players = packet.payload.get("players");
                int count = players != null && players.isArray() ? players.size() : 0;
                log.info("ADMIN_USER_LIST_RESPONSE ok — {} player(s)", count);
                Platform.runLater(() -> {
                    rows.clear();
                    if (players != null && players.isArray()) {
                        for (JsonNode p : players) {
                            rows.add(new PlayerRow(
                                    p.get("username").asText(),
                                    p.has("email")         ? p.get("email").asText()         : "",
                                    p.has("characterName") ? p.get("characterName").asText() : "—",
                                    p.has("ip")            ? p.get("ip").asText()            : "—",
                                    p.get("joinedAt").asLong(),
                                    p.get("x").asDouble(),
                                    p.get("y").asDouble(),
                                    p.get("score").asInt(),
                                    p.has("isAdmin") && p.get("isAdmin").asBoolean()));
                        }
                    }
                    headerLabel.setText(String.format("Admin Panel — %s  |  %d player%s online",
                            SessionStore.getUsername(), rows.size(), rows.size() == 1 ? "" : "s"));
                });
            }
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
            case ADMIN_SET_ADMIN_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? (packet.payload.get("isAdmin").asBoolean() ? "Granted admin: " : "Revoked admin: ")
                                + packet.payload.get("username").asText()
                        : "Admin change failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok ? "#80c080" : "#e94560");
            });
            case ADMIN_RESTART_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Server restarting — reconnect in a moment."
                        : "Restart failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok ? "#f0a030" : "#e94560");
            });
            case ADMIN_DEPLOY_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Deploying — pulling code, rebuilding, restarting. Reconnect in ~1 min."
                        : "Deploy failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok ? "#50c050" : "#e94560");
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
                case "email"         -> row.email;
                case "username"      -> row.username;
                case "characterName" -> row.characterName;
                case "ip"            -> row.ip;
                case "connectedTime" -> row.connectedTime;
                case "score"         -> row.score;
                case "x"             -> row.x;
                case "y"             -> row.y;
                default              -> new SimpleStringProperty("");
            };
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> adminCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Admin");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button();
            {
                btn.setStyle("""
                        -fx-font-size: 11;
                        -fx-background-radius: 4;
                        -fx-padding: 3 8 3 8;
                        """);
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                if (row.username.get().equals(SessionStore.getUsername())) {
                    setGraphic(null); return;
                }
                btn.setText(row.isAdmin ? "Revoke" : "Grant");
                String bg = row.isAdmin ? "#505080" : "#3a7a3a";
                btn.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; "
                        + "-fx-font-size: 11; -fx-background-radius: 4; -fx-padding: 3 8 3 8;");
                btn.setOnAction(e -> doSetAdmin(row.username.get(), !row.isAdmin));
                setGraphic(btn);
            }
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
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                setGraphic(row.username.get().equals(SessionStore.getUsername()) ? null : btn);
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
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                setGraphic(row.username.get().equals(SessionStore.getUsername()) ? null : btn);
            }
        });
        return col;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void requestPlayerList() {
        log.info("Sending ADMIN_USER_LIST_REQUEST (token={})",
                SessionStore.getToken() != null ? SessionStore.getToken().substring(0, 8) + "…" : "null");
        client.send(new Packet(PacketType.ADMIN_USER_LIST_REQUEST,
                SessionStore.getToken(), PacketSerializer.emptyPayload()));
    }

    private void doKick(String username) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        client.send(new Packet(PacketType.ADMIN_KICK_REQUEST, SessionStore.getToken(), payload));
    }

    private void doSetAdmin(String username, boolean isAdmin) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("isAdmin",  isAdmin);
        client.send(new Packet(PacketType.ADMIN_SET_ADMIN_REQUEST, SessionStore.getToken(), payload));
    }

    private void doBan(String username) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("ban", true);
        client.send(new Packet(PacketType.ADMIN_BAN_REQUEST, SessionStore.getToken(), payload));
    }

    private void confirmDeploy() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Deploy & Restart");
        alert.setHeaderText("Deploy latest code and restart?");
        alert.setContentText("""
                The server will:
                  1. Commit and push any local changes
                  2. Pull latest code from remote
                  3. Rebuild the server JAR
                  4. Restart automatically

                All players will be disconnected.
                Reconnect in approximately 1 minute.""");
        alert.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == javafx.scene.control.ButtonType.OK)
                client.send(new Packet(PacketType.ADMIN_DEPLOY_REQUEST,
                        SessionStore.getToken(), PacketSerializer.emptyPayload()));
        });
    }

    private void confirmRestart() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Restart Server");
        alert.setHeaderText("Restart the game server?");
        alert.setContentText("All connected players will be disconnected.\nThe server will restart automatically if launched via restart.sh.");
        alert.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == javafx.scene.control.ButtonType.OK) doRestart();
        });
    }

    private void doRestart() {
        client.send(new Packet(PacketType.ADMIN_RESTART_REQUEST,
                SessionStore.getToken(), PacketSerializer.emptyPayload()));
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
        final StringProperty  email         = new SimpleStringProperty();
        final StringProperty  username      = new SimpleStringProperty();
        final StringProperty  characterName = new SimpleStringProperty();
        final StringProperty  ip            = new SimpleStringProperty();
        final StringProperty  connectedTime = new SimpleStringProperty();
        final StringProperty  score         = new SimpleStringProperty();
        final StringProperty  x             = new SimpleStringProperty();
        final StringProperty  y             = new SimpleStringProperty();
        volatile boolean      isAdmin;
        final long            joinedAt;

        PlayerRow(String username, String email, String characterName, String ip, long joinedAt, double x, double y, int score, boolean isAdmin) {
            this.joinedAt = joinedAt;
            this.isAdmin  = isAdmin;
            this.email.set(email);
            this.username.set(username);
            this.characterName.set(characterName);
            this.ip.set(ip);
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
