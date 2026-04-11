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
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Admin-only Game Settings panel — shown as a tab in GameScreen for admin users.
 * Controls server-side and gameplay parameters such as gravity.
 */
public class GameSettingsPanel {

    private final UDPClient client;
    private TextField rebootDelayField;
    private TextField rebootMessageField;
    private Label     rebootStatusLabel;
    private Label     commitStatusLabel;
    private Consumer<Integer> onServerRestart;

    private ComboBox<String> startingBoardCombo;
    private final List<Long> boardIdList = new ArrayList<>();

    public GameSettingsPanel(UDPClient client) {
        this.client = client;
    }

    public void setRestartCallback(Consumer<Integer> callback) {
        this.onServerRestart = callback;
    }

    /** Called by AdminPanel to forward RESTART/DEPLOY packet responses here. */
    public void onPacket(Packet packet) {
        Platform.runLater(() -> {
            switch (packet.type) {
                case ADMIN_SAVE_SETTINGS_RESPONSE -> {
                    boolean ok  = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                    String  msg = ok ? "Saved to server." :
                            packet.payload.has("message")
                                    ? packet.payload.get("message").asText("Save failed.")
                                    : "Save failed.";
                    if (commitStatusLabel != null) setStatus(commitStatusLabel, msg, ok);
                }
                case ADMIN_GET_BOARDS_RESPONSE -> {
                    if (startingBoardCombo == null) return;
                    if (!packet.payload.has("boards")) return;
                    long currentId = AppSettings.getStartingBoardId();
                    boardIdList.clear();
                    startingBoardCombo.getItems().clear();
                    startingBoardCombo.getItems().add("(None)");
                    boardIdList.add(0L);
                    for (JsonNode b : packet.payload.get("boards")) {
                        boardIdList.add(b.get("id").asLong());
                        startingBoardCombo.getItems().add(b.get("name").asText());
                    }
                    int sel = boardIdList.indexOf(currentId);
                    startingBoardCombo.getSelectionModel().select(sel >= 0 ? sel : 0);
                }
                case ADMIN_RESTART_RESPONSE -> {
                    boolean ok = packet.payload.get("success").asBoolean();
                    if (ok) {
                        int delay = packet.payload.has("delay") ? packet.payload.get("delay").asInt(15) : 15;
                        showRebootStatus("Server restarting in " + delay + "s…", true);
                        if (onServerRestart != null) onServerRestart.accept(delay);
                    } else {
                        showRebootStatus("Restart failed: " + packet.payload.get("message").asText(), false);
                    }
                }
                case ADMIN_DEPLOY_RESPONSE -> {
                    boolean ok = packet.payload.get("success").asBoolean();
                    if (ok) {
                        int delay = packet.payload.has("delay") ? packet.payload.get("delay").asInt(15) : 15;
                        showRebootStatus("Deploying — pulling, rebuilding, restarting in " + delay + "s…", true);
                        if (onServerRestart != null) onServerRestart.accept(delay + 60);
                    } else {
                        showRebootStatus("Deploy failed: " + packet.payload.get("message").asText(), false);
                    }
                }
                default -> {}
            }
        });
    }

    public Node buildView() {

        // ── Gravity ───────────────────────────────────────────────────────────
        Label gravityDesc = new Label(
                "Gravity pulls characters toward the floor each frame.\n" +
                "Higher values = faster fall. 0 = no gravity.");
        gravityDesc.getStyleClass().addAll("text-muted", "font-11");
        gravityDesc.setWrapText(true);

        Label gravityLbl = new Label("Gravity strength:");
        gravityLbl.setMinWidth(140);
        gravityLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField gravityField = new TextField(String.valueOf(AppSettings.getGravity()));
        gravityField.setPrefWidth(80);
        gravityField.getStyleClass().add("input-field-md");

        HBox gravityRow = new HBox(12, gravityLbl, gravityField);
        gravityRow.setAlignment(Pos.CENTER_LEFT);

        VBox gravitySection = section("Gravity", gravityDesc, gravityRow);

        // ── Jump ──────────────────────────────────────────────────────────────
        Label jumpDesc = new Label(
                "Jump strength controls the upward velocity applied when a character jumps.\n" +
                "Higher values = higher jump.");
        jumpDesc.getStyleClass().addAll("text-muted", "font-11");
        jumpDesc.setWrapText(true);

        Label jumpLbl = new Label("Jump strength:");
        jumpLbl.setMinWidth(140);
        jumpLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField jumpField = new TextField(String.valueOf(AppSettings.getJumpStrength()));
        jumpField.setPrefWidth(80);
        jumpField.getStyleClass().add("input-field-md");

        HBox jumpRow = new HBox(12, jumpLbl, jumpField);
        jumpRow.setAlignment(Pos.CENTER_LEFT);

        VBox jumpSection = section("Jump", jumpDesc, jumpRow);

        // ── Run Speed ─────────────────────────────────────────────────────────
        Label speedDesc = new Label(
                "Run speed controls how fast characters move left and right.\n" +
                "Higher values = faster movement.");
        speedDesc.getStyleClass().addAll("text-muted", "font-11");
        speedDesc.setWrapText(true);

        Label speedLbl = new Label("Run speed:");
        speedLbl.setMinWidth(140);
        speedLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField speedField = new TextField(String.valueOf(AppSettings.getRunSpeed()));
        speedField.setPrefWidth(80);
        speedField.getStyleClass().add("input-field-md");

        HBox speedRow = new HBox(12, speedLbl, speedField);
        speedRow.setAlignment(Pos.CENTER_LEFT);

        VBox speedSection = section("Run Speed", speedDesc, speedRow);

        // ── Remember Password ─────────────────────────────────────────────────
        Label rememberPassDesc = new Label(
                "When enabled, users will see a \"Remember password\" checkbox on the\n" +
                "login screen and can opt in to have their password saved locally.");
        rememberPassDesc.getStyleClass().addAll("text-muted", "font-11");
        rememberPassDesc.setWrapText(true);

        CheckBox allowRememberPassBox = new CheckBox("Allow users to save their password");
        allowRememberPassBox.setSelected(AppSettings.isAllowRememberPassword());
        allowRememberPassBox.getStyleClass().add("check-secondary");
        allowRememberPassBox.setOnAction(e -> {
            AppSettings.setAllowRememberPassword(allowRememberPassBox.isSelected());
            if (!allowRememberPassBox.isSelected()) {
                // clear any saved passwords when feature is disabled
                AppSettings.setRememberPassword(false);
                AppSettings.setLastPassword("");
            }
            AppSettings.save();
        });

        VBox rememberPassSection = section("Login — Remember Password", rememberPassDesc, allowRememberPassBox);

        // ── Gameplay ──────────────────────────────────────────────────────────
        CheckBox testNpcCheck = new CheckBox("Show Test Fight NPC");
        testNpcCheck.setSelected(AppSettings.isShowTestNpc());
        testNpcCheck.getStyleClass().add("check-secondary");
        testNpcCheck.selectedProperty().addListener((obs, old, val) -> {
            AppSettings.setShowTestNpc(val);
            AppSettings.save();
        });

        Label npcXLbl = new Label("NPC X:");
        npcXLbl.setMinWidth(140);
        npcXLbl.getStyleClass().addAll("text-secondary", "font-12");
        TextField npcXField = new TextField(String.valueOf((int) AppSettings.getTestNpcX()));
        npcXField.setPrefWidth(80);
        npcXField.getStyleClass().add("input-field-md");
        npcXField.textProperty().addListener((obs, old, val) -> {
            try { AppSettings.setTestNpcX(Float.parseFloat(val.trim())); AppSettings.save(); }
            catch (NumberFormatException ignored) {}
        });

        Label npcYLbl = new Label("NPC Y:");
        npcYLbl.setMinWidth(140);
        npcYLbl.getStyleClass().addAll("text-secondary", "font-12");
        TextField npcYField = new TextField(String.valueOf((int) AppSettings.getTestNpcY()));
        npcYField.setPrefWidth(80);
        npcYField.getStyleClass().add("input-field-md");
        npcYField.textProperty().addListener((obs, old, val) -> {
            try { AppSettings.setTestNpcY(Float.parseFloat(val.trim())); AppSettings.save(); }
            catch (NumberFormatException ignored) {}
        });

        HBox npcXRow = new HBox(12, npcXLbl, npcXField);
        npcXRow.setAlignment(Pos.CENTER_LEFT);
        HBox npcYRow = new HBox(12, npcYLbl, npcYField);
        npcYRow.setAlignment(Pos.CENTER_LEFT);

        Label testNpcNote = new Label("Spawns an immortal enemy NPC on the Test Fight Board. X=world X, Y=game Y (0=floor).");
        testNpcNote.getStyleClass().addAll("text-muted", "font-11");
        testNpcNote.setWrapText(true);

        VBox gameplaySection = section("Gameplay", testNpcCheck, npcXRow, npcYRow, testNpcNote);

        // ── Connection ────────────────────────────────────────────────────────
        int labelW = 180;

        Label localHostLbl = new Label("Local Server Host:");
        localHostLbl.setMinWidth(labelW);
        localHostLbl.getStyleClass().addAll("text-secondary", "font-12");
        TextField localHostField = new TextField(AppSettings.getServerHost());
        localHostField.setPrefWidth(200);
        localHostField.getStyleClass().add("input-field-md");

        Label localPortLbl = new Label("Local Server Port:");
        localPortLbl.setMinWidth(labelW);
        localPortLbl.getStyleClass().addAll("text-secondary", "font-12");
        TextField localPortField = new TextField(String.valueOf(AppSettings.getServerPort()));
        localPortField.setPrefWidth(80);
        localPortField.getStyleClass().add("input-field-md");

        Label extHostLbl = new Label("External Server Host:");
        extHostLbl.setMinWidth(labelW);
        extHostLbl.getStyleClass().addAll("text-secondary", "font-12");
        TextField extHostField = new TextField(AppSettings.getExternalServerHost());
        extHostField.setPrefWidth(200);
        extHostField.getStyleClass().add("input-field-md");

        Label extPortLbl = new Label("External Server Port:");
        extPortLbl.setMinWidth(labelW);
        extPortLbl.getStyleClass().addAll("text-secondary", "font-12");
        TextField extPortField = new TextField(String.valueOf(AppSettings.getExternalServerPort()));
        extPortField.setPrefWidth(80);
        extPortField.getStyleClass().add("input-field-md");

        CheckBox allowExtAdminBox = new CheckBox("Allow external admin connections");
        allowExtAdminBox.setSelected(AppSettings.isAllowExternalAdmin());
        allowExtAdminBox.getStyleClass().add("check-secondary");

        CheckBox allowExtDevBox = new CheckBox("Allow external developer connections");
        allowExtDevBox.setSelected(AppSettings.isAllowExternalDev());
        allowExtDevBox.getStyleClass().add("check-secondary");

        HBox localHostRow = new HBox(12, localHostLbl, localHostField); localHostRow.setAlignment(Pos.CENTER_LEFT);
        HBox localPortRow = new HBox(12, localPortLbl, localPortField); localPortRow.setAlignment(Pos.CENTER_LEFT);
        HBox extHostRow   = new HBox(12, extHostLbl,   extHostField);   extHostRow.setAlignment(Pos.CENTER_LEFT);
        HBox extPortRow   = new HBox(12, extPortLbl,   extPortField);   extPortRow.setAlignment(Pos.CENTER_LEFT);

        Label connNote = new Label("Connection changes take effect after restarting the client.");
        connNote.getStyleClass().addAll("text-muted", "font-11");
        connNote.setWrapText(true);

        VBox connectionSection = section("Connection", localHostRow, localPortRow, extHostRow, extPortRow,
                allowExtAdminBox, allowExtDevBox, connNote);

        // ── Starting Board ────────────────────────────────────────────────────
        Label startingBoardDesc = new Label(
                "The board players are placed on when they first join the game world.");
        startingBoardDesc.getStyleClass().addAll("text-muted", "font-11");
        startingBoardDesc.setWrapText(true);

        Label startingBoardLbl = new Label("Starting board:");
        startingBoardLbl.setMinWidth(140);
        startingBoardLbl.getStyleClass().addAll("text-secondary", "font-12");

        startingBoardCombo = new ComboBox<>();
        startingBoardCombo.getItems().add("(Loading…)");
        startingBoardCombo.getSelectionModel().select(0);
        startingBoardCombo.getStyleClass().add("combo-dark");
        startingBoardCombo.setPrefWidth(220);

        Button refreshBoardsBtn = new Button("↻");
        refreshBoardsBtn.getStyleClass().add("btn-secondary");
        refreshBoardsBtn.setOnAction(e -> requestBoardList());

        HBox startingBoardRow = new HBox(12, startingBoardLbl, startingBoardCombo, refreshBoardsBtn);
        startingBoardRow.setAlignment(Pos.CENTER_LEFT);

        VBox startingBoardSection = section("Starting Board", startingBoardDesc, startingBoardRow);

        // ── Reboot Settings ───────────────────────────────────────────────────
        Label delayLbl = new Label("Delay (seconds):");
        delayLbl.setMinWidth(labelW);
        delayLbl.getStyleClass().addAll("text-secondary", "font-12");
        rebootDelayField = new TextField(String.valueOf(AppSettings.getRebootDelaySecs()));
        rebootDelayField.setPrefWidth(70);
        rebootDelayField.getStyleClass().add("input-field-md");

        Label msgLbl = new Label("Notice message:");
        msgLbl.setMinWidth(labelW);
        msgLbl.getStyleClass().addAll("text-secondary", "font-12");
        rebootMessageField = new TextField(AppSettings.getRebootMessage());
        rebootMessageField.setPromptText("The server will reboot in %d seconds.");
        rebootMessageField.setPrefWidth(300);
        rebootMessageField.getStyleClass().add("input-field-md");

        Label rebootHint = new Label("Leave message blank to use default. Use %d for the delay value.");
        rebootHint.getStyleClass().addAll("text-muted", "font-11");
        rebootHint.setWrapText(true);

        rebootStatusLabel = new Label();
        rebootStatusLabel.getStyleClass().add("font-11");

        Button versionBtn = new Button("Compare Versions");
        versionBtn.getStyleClass().add("btn-secondary");
        versionBtn.setOnAction(e -> checkVersions());

        Button restartBtn = new Button("Restart");
        restartBtn.getStyleClass().add("btn-restart");
        restartBtn.setOnAction(e -> confirmRestart());

        Button deployBtn = new Button("Deploy & Restart");
        deployBtn.getStyleClass().add("btn-deploy");
        deployBtn.setOnAction(e -> confirmDeploy());

        HBox delayRow  = new HBox(12, delayLbl,  rebootDelayField);  delayRow.setAlignment(Pos.CENTER_LEFT);
        HBox msgRow    = new HBox(12, msgLbl,    rebootMessageField); msgRow.setAlignment(Pos.CENTER_LEFT);
        HBox actionRow = new HBox(10, versionBtn, restartBtn, deployBtn, rebootStatusLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox rebootSection = section("Reboot Settings", delayRow, msgRow, rebootHint, actionRow);

        // ── Save / status ─────────────────────────────────────────────────────
        commitStatusLabel = new Label();
        Label statusLabel = commitStatusLabel;
        statusLabel.getStyleClass().add("font-11");

        Button saveBtn = new Button("Test Locally");
        saveBtn.setPrefWidth(140);
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> {
            float[] vals = parseFields(gravityField, jumpField, speedField, statusLabel);
            if (vals != null) {
                AppSettings.setGravity(vals[0]);
                AppSettings.setJumpStrength(vals[1]);
                AppSettings.setRunSpeed(vals[2]);
                boolean ok = AppSettings.save();
                setStatus(statusLabel, ok ? "Saved locally (not committed to server)." : "Could not write settings file.", ok);
            }
        });

        Button commitBtn = new Button("Commit to Server");
        commitBtn.setPrefWidth(160);
        commitBtn.getStyleClass().add("btn-warning");
        commitBtn.setOnAction(e -> {
            float[] vals = parseFields(gravityField, jumpField, speedField, statusLabel);
            int localPort, extPort;
            try { localPort = Integer.parseInt(localPortField.getText().trim()); }
            catch (NumberFormatException ex) { setStatus(statusLabel, "Invalid local port.", false); return; }
            try { extPort = Integer.parseInt(extPortField.getText().trim()); }
            catch (NumberFormatException ex) { setStatus(statusLabel, "Invalid external port.", false); return; }
            if (vals != null) {
                AppSettings.setGravity(vals[0]);
                AppSettings.setJumpStrength(vals[1]);
                AppSettings.setRunSpeed(vals[2]);
                AppSettings.setAllowRememberPassword(allowRememberPassBox.isSelected());
                AppSettings.setShowTestNpc(testNpcCheck.isSelected());
                try { AppSettings.setTestNpcX(Float.parseFloat(npcXField.getText().trim())); } catch (NumberFormatException ignored) {}
                try { AppSettings.setTestNpcY(Float.parseFloat(npcYField.getText().trim())); } catch (NumberFormatException ignored) {}
                AppSettings.setServerHost(localHostField.getText().trim());
                AppSettings.setServerPort(localPort);
                AppSettings.setExternalServerHost(extHostField.getText().trim());
                AppSettings.setExternalServerPort(extPort);
                AppSettings.setAllowExternalAdmin(allowExtAdminBox.isSelected());
                AppSettings.setAllowExternalDev(allowExtDevBox.isSelected());
                int rebootDelay = getRebootDelay();
                AppSettings.setRebootDelaySecs(rebootDelay);
                AppSettings.setRebootMessage(rebootMessageField.getText().trim());
                AppSettings.save();
                ObjectNode payload = PacketSerializer.mapper().createObjectNode();
                payload.put("gravity",               vals[0]);
                payload.put("jumpStrength",          vals[1]);
                payload.put("runSpeed",              vals[2]);
                payload.put("allowRememberPassword", allowRememberPassBox.isSelected());
                payload.put("showTestNpc",           testNpcCheck.isSelected());
                payload.put("testNpcX",              AppSettings.getTestNpcX());
                payload.put("testNpcY",              AppSettings.getTestNpcY());
                payload.put("localServerHost",       localHostField.getText().trim());
                payload.put("localServerPort",       localPort);
                payload.put("externalServerHost",    extHostField.getText().trim());
                payload.put("externalServerPort",    extPort);
                payload.put("allowExternalAdmin",    allowExtAdminBox.isSelected());
                payload.put("allowExternalDev",      allowExtDevBox.isSelected());
                payload.put("rebootDelaySecs",       rebootDelay);
                payload.put("rebootMessage",         rebootMessageField.getText().trim());
                int boardIdx = startingBoardCombo.getSelectionModel().getSelectedIndex();
                long startingBoardId = (boardIdx > 0 && boardIdx < boardIdList.size())
                        ? boardIdList.get(boardIdx) : 0L;
                AppSettings.setStartingBoardId(startingBoardId);
                if (startingBoardId > 0) payload.put("startingBoardId", startingBoardId);
                client.sendToAdmin(new Packet(PacketType.ADMIN_SAVE_SETTINGS_REQUEST,
                        SessionStore.getToken(), payload));
                setStatus(statusLabel, "Sending to server…", true);
            }
        });

        HBox buttons = new HBox(10, saveBtn, commitBtn, statusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(16, 20, 20, 20));

        // ── Uploads ───────────────────────────────────────────────────────────
        Label keyLbl = new Label("Upload Key:");
        keyLbl.setMinWidth(labelW);
        keyLbl.getStyleClass().addAll("text-secondary", "font-12");
        PasswordField keyField = new PasswordField();
        keyField.setText(AppSettings.getUploadKey());
        keyField.setPromptText("server upload key");
        keyField.setPrefWidth(200);
        keyField.getStyleClass().add("input-field-md");

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

        HBox keyRow    = new HBox(12, keyLbl, keyField);    keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox uploadRow = new HBox(10, clientJarBtn, syncAppBtn, uploadStatus);
        uploadRow.setAlignment(Pos.CENTER_LEFT);

        VBox uploadSection = section("Uploads", keyRow, uploadRow);

        VBox content = new VBox(gravitySection, jumpSection, speedSection,
                rememberPassSection, gameplaySection, connectionSection,
                startingBoardSection, rebootSection, uploadSection, buttons);
        content.getStyleClass().add("app-root");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-dark");

        requestBoardList();
        return scroll;
    }

    private void requestBoardList() {
        client.sendToAdmin(new Packet(PacketType.ADMIN_GET_BOARDS_REQUEST,
                SessionStore.getToken(), PacketSerializer.emptyPayload()));
    }

    private static VBox section(String title, Node... children) {
        Label header = new Label(title);
        header.setFont(Font.font("System", FontWeight.BOLD, 13));
        header.getStyleClass().add("section-title");

        Separator sep = new Separator();
        sep.getStyleClass().add("sep-dark");

        VBox body = new VBox(6);
        body.setPadding(new Insets(4, 0, 0, 0));
        body.getChildren().addAll(children);

        VBox box = new VBox(4, header, sep, body);
        box.setPadding(new Insets(14, 20, 8, 20));
        box.getStyleClass().add("app-root");
        return box;
    }

    /** Parses and validates all three fields. Returns [gravity, jump, speed] or null on error. */
    private static float[] parseFields(TextField gravityField, TextField jumpField,
                                       TextField speedField, Label statusLabel) {
        float g, j, s;
        try {
            g = Float.parseFloat(gravityField.getText().trim());
            if (g < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus(statusLabel, "Invalid gravity — enter a positive number.", false);
            return null;
        }
        try {
            j = Float.parseFloat(jumpField.getText().trim());
            if (j < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus(statusLabel, "Invalid jump value — enter a positive number.", false);
            return null;
        }
        try {
            s = Float.parseFloat(speedField.getText().trim());
            if (s < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus(statusLabel, "Invalid run speed — enter a positive number.", false);
            return null;
        }
        return new float[]{g, j, s};
    }

    private static void setStatus(Label label, String msg, boolean success) {
        label.setText(msg);
        label.setStyle("-fx-font-size: 11; -fx-text-fill: " + (success ? "-af-success;" : "-af-error;"));
    }

    // ── Reboot helpers ────────────────────────────────────────────────────────

    private int getRebootDelay() {
        try {
            int d = Integer.parseInt(rebootDelayField.getText().trim());
            return d > 0 ? d : 15;
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    private String getRebootMessage() {
        String msg = rebootMessageField.getText().trim();
        if (msg.isEmpty()) return "";
        try { return String.format(msg, getRebootDelay()); }
        catch (Exception e) { return msg; }
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
            if (btn == ButtonType.OK) {
                ObjectNode payload = PacketSerializer.mapper().createObjectNode();
                payload.put("delay", getRebootDelay());
                String msg = getRebootMessage();
                if (!msg.isEmpty()) payload.put("message", msg);
                client.sendToAdmin(new Packet(PacketType.ADMIN_DEPLOY_REQUEST, SessionStore.getToken(), payload));
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
            if (btn == ButtonType.OK) {
                ObjectNode payload = PacketSerializer.mapper().createObjectNode();
                payload.put("delay", getRebootDelay());
                String msg = getRebootMessage();
                if (!msg.isEmpty()) payload.put("message", msg);
                client.sendToAdmin(new Packet(PacketType.ADMIN_RESTART_REQUEST, SessionStore.getToken(), payload));
            }
        });
    }

    private void checkVersions() {
        String clientCommit    = BuildInfo.COMMIT;
        String clientBuildTime = BuildInfo.BUILD_TIME;
        String url = AppSettings.getAssetUrl() + "/build-info";
        Thread.ofVirtual().start(() -> {
            String serverCommit = "unknown", serverBuildTime = "unknown";
            String error = null;
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
            content += match ? "\n\nVersions are a Match." : "\n\nThe server may need to be deployed.";
        }
        alert.setContentText(content);
        styleAlert(alert);
        alert.showAndWait();
    }

    private void showRebootStatus(String msg, boolean ok) {
        if (rebootStatusLabel == null) return;
        rebootStatusLabel.setText(msg);
        rebootStatusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "-af-warning;" : "-af-error;"));
        PauseTransition clear = new PauseTransition(Duration.seconds(6));
        clear.setOnFinished(e -> { rebootStatusLabel.setText(""); rebootStatusLabel.setStyle(""); });
        clear.play();
    }

    // ── Upload helper ─────────────────────────────────────────────────────────

    private void pickAndUpload(Window owner, String uploadKey, String title,
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
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
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
                Platform.runLater(() -> {
                    statusLabel.setText("Upload error: " + ex.getMessage());
                    statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -af-error;");
                });
            }
        });
    }

    private static void styleAlert(Alert alert) {
        alert.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");
        alert.setOnShown(e -> alert.getDialogPane().lookupAll(".label")
                .forEach(n -> n.setStyle("-fx-text-fill: #ffff00;")));
    }
}
