package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.BuildInfo;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

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

    private Label     headerLabel;
    private Label     statusLabel;
    private TextField rebootDelayField;
    private TextField rebootMessageField;
    private final ObservableList<PlayerRow> rows = FXCollections.observableArrayList();
    private Timeline ticker;

    /** Called with countdown seconds when the server is about to restart (deploy or restart). */
    private Consumer<Integer> onServerRestart;

    public void setRestartCallback(Consumer<Integer> callback) {
        this.onServerRestart = callback;
    }

    public AdminPanel(UDPClient client) {
        this.client = client;
    }

    // ── Build ────────────────────────────────────────────────────────────────

    public Node buildView() {
        headerLabel = new Label("Admin Panel — " + SessionStore.getUsername());
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        headerLabel.getStyleClass().add("text-primary");

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.getStyleClass().add("text-success");

        Button deployBtn = new Button("Deploy & Restart");
        deployBtn.getStyleClass().add("btn-deploy");
        deployBtn.setOnAction(e -> confirmDeploy());

        Button restartBtn = new Button("Restart");
        restartBtn.getStyleClass().add("btn-restart");
        restartBtn.setOnAction(e -> confirmRestart());

        Button versionBtn = new Button("Compare Versions");
        versionBtn.getStyleClass().add("btn-secondary");
        versionBtn.setOnAction(e -> checkVersions());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(8, headerLabel, headerSpacer, versionBtn, deployBtn, restartBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 6, 14));
        header.setSpacing(8);
        header.getStyleClass().add("app-surface");

        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(2, 14, 4, 14));
        statusBar.getStyleClass().add("app-surface");

        TableView<PlayerRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getStyleClass().add("dark-table");
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

        // ── Upload section ────────────────────────────────────────────────────
        VBox uploadSection = buildUploadSection();

        // ── Reboot settings ───────────────────────────────────────────────────
        VBox rebootSection = buildRebootSection();

        VBox root = new VBox(header, statusBar, connSection, uploadSection, rebootSection, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.getStyleClass().add("app-root");

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> requestPlayerList()));
        ticker.setCycleCount(Timeline.INDEFINITE);

        return root;
    }

    // ── Connection section ────────────────────────────────────────────────────

    private VBox buildConnectionSection() {
        Label hostLbl = new Label("Server Host");
        hostLbl.setMinWidth(100);
        hostLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField hostField = new TextField(AppSettings.getServerHost());
        hostField.setPrefWidth(200);
        hostField.getStyleClass().add("input-field-sm");

        Label portLbl = new Label("Server Port");
        portLbl.setMinWidth(100);
        portLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField portField = new TextField(String.valueOf(AppSettings.getServerPort()));
        portField.setPrefWidth(80);
        portField.getStyleClass().add("input-field-sm");

        Label connStatus = new Label();
        connStatus.getStyleClass().add("font-11");

        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().add("btn-primary-sm");
        saveBtn.setOnAction(e -> {
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) {
                connStatus.setText("Invalid port.");
                connStatus.setStyle("-fx-font-size: 11; -fx-text-fill: -af-error;");
                return;
            }
            AppSettings.setServerHost(hostField.getText().trim());
            AppSettings.setServerPort(port);
            boolean ok = AppSettings.save();
            connStatus.setText(ok ? "Saved. Restart to apply." : "Could not write settings file.");
            connStatus.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "-af-success;" : "-af-error;"));
        });

        HBox hostRow = new HBox(10, hostLbl, hostField);
        hostRow.setAlignment(Pos.CENTER_LEFT);

        HBox portRow = new HBox(10, portLbl, portField);
        portRow.setAlignment(Pos.CENTER_LEFT);

        HBox btnRow = new HBox(10, saveBtn, connStatus);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        return buildSection("Connection", hostRow, portRow, btnRow);
    }

    // ── Upload section ───────────────────────────────────────────────────────

    private VBox buildUploadSection() {
        Label keyLbl = new Label("Upload Key");
        keyLbl.setMinWidth(100);
        keyLbl.getStyleClass().addAll("text-secondary", "font-12");

        PasswordField keyField = new PasswordField();
        keyField.setText(AppSettings.getUploadKey());
        keyField.setPromptText("server upload key");
        keyField.setPrefWidth(200);
        keyField.getStyleClass().add("input-field-sm");

        Label uploadStatus = new Label();
        uploadStatus.getStyleClass().add("font-11");
        uploadStatus.setWrapText(true);

        Button clientJarBtn = new Button("Upload Client JAR");
        clientJarBtn.getStyleClass().add("btn-info");
        clientJarBtn.setOnAction(e -> pickAndUpload(
                clientJarBtn.getScene().getWindow(),
                "Select Client JAR",
                keyField.getText().trim(),
                AppSettings.getAssetUrl() + "/assets/client/game-client.jar",
                "game-client.jar",
                uploadStatus));

        Button syncAppBtn = new Button("Upload Sync App");
        syncAppBtn.getStyleClass().add("btn-info");
        syncAppBtn.setOnAction(e -> pickAndUpload(
                syncAppBtn.getScene().getWindow(),
                "Select Sync App JAR",
                keyField.getText().trim(),
                AppSettings.getAssetUrl() + "/assets/sync/syncapp.jar",
                "syncapp.jar",
                uploadStatus));

        HBox keyRow = new HBox(10, keyLbl, keyField);
        keyRow.setAlignment(Pos.CENTER_LEFT);

        HBox btnRow = new HBox(10, clientJarBtn, syncAppBtn, uploadStatus);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        return buildSection("Uploads", keyRow, btnRow);
    }

    // ── Reboot section ────────────────────────────────────────────────────────

    private VBox buildRebootSection() {
        Label delayLbl = new Label("Delay (seconds)");
        delayLbl.setMinWidth(130);
        delayLbl.getStyleClass().addAll("text-secondary", "font-12");

        rebootDelayField = new TextField("60");
        rebootDelayField.setPrefWidth(60);
        rebootDelayField.getStyleClass().add("input-field-sm");

        Label msgLbl = new Label("Notice message");
        msgLbl.setMinWidth(130);
        msgLbl.getStyleClass().addAll("text-secondary", "font-12");

        rebootMessageField = new TextField();
        rebootMessageField.setPromptText("The server will reboot in %d seconds.");
        rebootMessageField.setPrefWidth(300);
        rebootMessageField.getStyleClass().add("input-field-sm");

        Label hint = new Label("Leave message blank to use default. Use %d for the delay value.");
        hint.getStyleClass().addAll("text-muted", "italic", "font-11");

        HBox delayRow = new HBox(10, delayLbl, rebootDelayField);
        delayRow.setAlignment(Pos.CENTER_LEFT);

        HBox msgRow = new HBox(10, msgLbl, rebootMessageField);
        msgRow.setAlignment(Pos.CENTER_LEFT);

        return buildSection("Reboot Settings", delayRow, msgRow, hint);
    }

    private int getRebootDelay() {
        try {
            int d = Integer.parseInt(rebootDelayField.getText().trim());
            return d > 0 ? d : 15;
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    private String getRebootMessage() {
        if (rebootMessageField == null) return "";
        String msg = rebootMessageField.getText().trim();
        if (msg.isEmpty()) return "";
        // Replace %d with the actual delay value
        try { return String.format(msg, getRebootDelay()); }
        catch (Exception e) { return msg; }
    }

    private VBox buildSection(String title, Node... children) {
        Label sectionTitle = new Label(title);
        sectionTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        sectionTitle.getStyleClass().add("section-title");

        Separator sep = new Separator();
        sep.getStyleClass().add("sep-dark");

        VBox section = new VBox(6);
        section.getChildren().addAll(sectionTitle, sep);
        section.getChildren().addAll(children);
        section.setPadding(new Insets(10, 14, 10, 14));
        section.getStyleClass().add("app-surface");
        return section;
    }

    private void pickAndUpload(Window owner, String title, String uploadKey,
                               String uploadUrl, String displayName, Label statusLabel) {
        if (uploadKey.isEmpty()) {
            statusLabel.setText("Enter an upload key first.");
            statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -af-error;");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR files", "*.jar"));
        java.io.File selected = chooser.showOpenDialog(owner);
        if (selected == null) return;

        Path jar = selected.toPath();
        statusLabel.setText("Uploading " + displayName + "…");
        statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -af-warning;");

        Thread.ofVirtual().start(() -> {
            try {
                byte[] bytes = Files.readAllBytes(jar);
                HttpURLConnection conn = (HttpURLConnection) URI.create(uploadUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-Upload-Key", uploadKey);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Uploaded " + displayName + " (" + (bytes.length / 1024) + " KB)");
                        statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -af-success;");
                    });
                } else {
                    Platform.runLater(() -> {
                        statusLabel.setText("Upload failed — HTTP " + code);
                        statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -af-error;");
                    });
                }
            } catch (Exception ex) {
                log.error("Upload failed: {}", ex.getMessage(), ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Upload error: " + ex.getMessage());
                    statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -af-error;");
                });
            }
        });
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
                    Platform.runLater(() -> showStatus("Access denied: " + msg, false));
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
                showStatus(msg, ok);
            });
            case ADMIN_BAN_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Banned: "      + packet.payload.get("username").asText()
                        : "Ban failed: "  + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_SET_ADMIN_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? (packet.payload.get("isAdmin").asBoolean() ? "Granted admin: " : "Revoked admin: ")
                                + packet.payload.get("username").asText()
                        : "Admin change failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_RESTART_RESPONSE -> Platform.runLater(() -> {
                boolean ok = packet.payload.get("success").asBoolean();
                if (ok) {
                    int delay = packet.payload.has("delay") ? packet.payload.get("delay").asInt(15) : 15;
                    statusLabel.setText("Server restarting in " + delay + "s…");
                    statusLabel.setStyle("-fx-text-fill: -af-warning;");
                    if (onServerRestart != null) onServerRestart.accept(delay);
                } else {
                    showStatus("Restart failed: " + packet.payload.get("message").asText(), false);
                }
            });
            case ADMIN_DEPLOY_RESPONSE -> Platform.runLater(() -> {
                boolean ok = packet.payload.get("success").asBoolean();
                if (ok) {
                    int delay = packet.payload.has("delay") ? packet.payload.get("delay").asInt(15) : 15;
                    showStatus("Deploying — pulling, rebuilding, restarting in " + delay + "s…", true);
                    if (onServerRestart != null) onServerRestart.accept(delay + 60);
                } else {
                    showStatus("Deploy failed: " + packet.payload.get("message").asText(), false);
                }
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
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                if (row.username.get().equals(SessionStore.getUsername())) {
                    setGraphic(null); return;
                }
                btn.setText(row.isAdmin ? "Revoke" : "Grant");
                btn.getStyleClass().removeAll("btn-revoke", "btn-grant");
                btn.getStyleClass().add(row.isAdmin ? "btn-revoke" : "btn-grant");
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
                btn.getStyleClass().add("btn-primary-sm");
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
                btn.getStyleClass().add("btn-danger-sm");
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

    private void checkVersions() {
        String clientCommit    = BuildInfo.COMMIT;
        String clientBuildTime = BuildInfo.BUILD_TIME;
        String url = AppSettings.getAssetUrl() + "/build-info";

        Thread.ofVirtual().start(() -> {
            String serverCommit    = "unknown";
            String serverBuildTime = "unknown";
            String error           = null;
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    String body = new String(conn.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.JsonNode json =
                            PacketSerializer.mapper().readTree(body);
                    serverCommit    = json.path("commit").asText("unknown");
                    serverBuildTime = json.path("buildTime").asText("unknown");
                } else if (code == 404) {
                    error = "Server does not expose build info (HTTP 404).\nDeploy the latest server build first.";
                } else {
                    error = "HTTP " + code;
                }
            } catch (Exception ex) {
                error = ex.getMessage();
            }

            final String sc = serverCommit, st = serverBuildTime, err = error;
            Platform.runLater(() -> showVersionDialog(clientCommit, clientBuildTime, sc, st, err));
        });
    }

    private void showVersionDialog(String clientCommit, String clientBuildTime,
                                   String serverCommit, String serverBuildTime, String error) {
        boolean match = clientCommit.equals(serverCommit) && !"unknown".equals(clientCommit);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Version Comparison");
        alert.setHeaderText(match ? "✓ Client and server are in sync" : "⚠ Version mismatch!");

        String content;
        if (error != null) {
            content = "Could not reach server build-info endpoint:\n" + error
                    + "\n\nClient commit:  " + clientCommit
                    + "\nClient built:   " + clientBuildTime;
        } else {
            content = String.format(
                    "Client commit:  %s\nClient built:   %s\n\nServer commit:  %s\nServer built:   %s",
                    clientCommit, clientBuildTime, serverCommit, serverBuildTime);
            if (match) content += "\n\nVersions are a Match.";
            else        content += "\n\nThe server may need to be deployed.";
        }
        alert.setContentText(content);
        styleAlert(alert);
        alert.showAndWait();
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
        styleAlert(alert);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == javafx.scene.control.ButtonType.OK) {
                ObjectNode payload = PacketSerializer.mapper().createObjectNode();
                payload.put("delay", getRebootDelay());
                String msg = getRebootMessage();
                if (!msg.isEmpty()) payload.put("message", msg);
                client.send(new Packet(PacketType.ADMIN_DEPLOY_REQUEST, SessionStore.getToken(), payload));
            }
        });
    }

    private void confirmRestart() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Restart Server");
        alert.setHeaderText("Restart the game server?");
        alert.setContentText("All connected players will be disconnected.\nThe server will restart automatically if launched via restart.sh.");
        styleAlert(alert);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == javafx.scene.control.ButtonType.OK) doRestart();
        });
    }

    private void doRestart() {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("delay",   getRebootDelay());
        String msg = getRebootMessage();
        if (!msg.isEmpty()) payload.put("message", msg);
        client.send(new Packet(PacketType.ADMIN_RESTART_REQUEST, SessionStore.getToken(), payload));
    }

    private void styleAlert(Alert alert) {
        alert.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
        alert.setOnShown(e -> alert.getDialogPane().lookupAll(".label")
                .forEach(n -> n.setStyle("-fx-text-fill: #ffff00;")));
    }

    private void showStatus(String msg, boolean ok) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: " + (ok ? "-af-success;" : "-af-error;"));
        PauseTransition clear = new PauseTransition(Duration.seconds(4));
        clear.setOnFinished(e -> {
            statusLabel.setText("");
            statusLabel.setStyle("");
        });
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
