package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.GameResolution;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main game screen rendered on a JavaFX Canvas.
 *
 * Game world: 800 × 600 units (pixels 1:1).
 *
 * Controls:
 *   W / ↑  — move up
 *   S / ↓  — move down
 *   A / ←  — move left
 *   D / →  — move right
 *
 * The local player's position is updated immediately on keypress (client-side
 * prediction) and a PLAYER_UPDATE packet is sent to the server.  The server
 * broadcasts an authoritative GAME_STATE to all clients, which this screen
 * uses to render all other players.
 */
public class GameScreen {

    // ── Constants ────────────────────────────────────────────────────────────
    private static final float PLAYER_SPEED  = 3.5f;
    private static final float PLAYER_RADIUS = 14f;
    private static final long  SEND_INTERVAL_MS  = 50;     // max 20 updates/s to server
    private static final long  KEEPALIVE_MS      = 15_000; // keepalive when idle (prevents server eviction)

    // ── State ────────────────────────────────────────────────────────────────
    private final Stage     stage;
    private final UDPClient client;
    private final int       WORLD_W;
    private final int       WORLD_H;

    private float   localX;
    private float   localY;
    private int     localScore = 0;

    /** Latest server snapshot: username → {x, y, score} */
    private final Map<String, JsonNode> remotePlayers = new ConcurrentHashMap<>();

    private final Set<KeyCode> heldKeys = ConcurrentHashMap.newKeySet();
    private long lastSendTime = 0;

    private Label pingLabel;
    private Label playerCountLabel;
    private Canvas canvas;
    private AnimationTimer gameLoop;
    private boolean gameLoopRunning = false;
    private Timeline pingTimer;
    private AdminPanel adminPanel;

    // ── Reconnect overlay ────────────────────────────────────────────────────
    private VBox    disconnectedOverlay;
    private Label   overlayTitleLabel;
    private Label   countdownLabel;
    private Timeline countdownTimer;
    private Timeline retryTimer;
    private Timeline heartbeatTimer;
    private volatile boolean reconnecting = false;
    private volatile long    lastGameStateMs = 0;
    private static final long HEARTBEAT_TIMEOUT_MS  = 8_000;
    private static final int  SHUTDOWN_DELAY_SECONDS = 15;

    public GameScreen(Stage stage, UDPClient client) {
        this.stage   = stage;
        this.client  = client;
        GameResolution res = AppSettings.getResolution();
        this.WORLD_W  = res.width;
        this.WORLD_H  = res.height;
        this.FLOOR_Y  = res.height - 40;
        this.localX   = WORLD_W / 2f;
        this.localY   = WORLD_H / 2f;
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        // ── Left sidebar ─────────────────────────────────────────────────────
        Label gameTitle = new Label(AppSettings.getProgramName().toUpperCase());
        gameTitle.setStyle("-fx-text-fill: #e94560; -fx-font-size: 16; -fx-font-weight: bold;");
        gameTitle.setWrapText(true);

        Separator sep1 = new Separator();
        sep1.setStyle("-fx-background-color: #3a3a6a;");

        Label playingAsLbl = new Label("CHARACTER");
        playingAsLbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        String displayName = SessionStore.getCharacterName() != null && !SessionStore.getCharacterName().isBlank()
                ? SessionStore.getCharacterName()
                : SessionStore.getUsername();
        Label nameLabel = new Label(displayName);
        nameLabel.setStyle("-fx-text-fill: #e0e0e0; -fx-font-weight: bold; -fx-font-size: 14;");
        nameLabel.setWrapText(true);

        Separator sep2 = new Separator();
        sep2.setStyle("-fx-background-color: #3a3a6a;");

        Label onlineLbl = new Label("ONLINE");
        onlineLbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        playerCountLabel = new Label("1 Player");
        playerCountLabel.setStyle("-fx-text-fill: #53c0f0; -fx-font-weight: bold; -fx-font-size: 13;");

        Separator sep3 = new Separator();
        sep3.setStyle("-fx-background-color: #3a3a6a;");

        Label pingLbl = new Label("PING");
        pingLbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        pingLabel = new Label("-- ms");
        pingLabel.setStyle("-fx-text-fill: #80c080; -fx-font-weight: bold; -fx-font-size: 13;");

        Separator sep4 = new Separator();
        sep4.setStyle("-fx-background-color: #3a3a6a;");

        Label controlsLbl = new Label("CONTROLS");
        controlsLbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        Label controls = new Label("W A S D\nor Arrow Keys\nto move");
        controls.setStyle("-fx-text-fill: #808080; -fx-font-size: 11;");
        controls.setWrapText(true);

        Button logoutBtn = new Button("⏻  Logout");
        logoutBtn.setMaxWidth(Double.MAX_VALUE);
        logoutBtn.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-size: 12;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 0 8 0;
                """);
        logoutBtn.setOnAction(e -> doLogout());

        VBox sidebar = new VBox(10,
                gameTitle,
                logoutBtn,
                sep1,
                playingAsLbl, nameLabel,
                sep2,
                onlineLbl, playerCountLabel,
                sep3,
                pingLbl, pingLabel,
                sep4,
                controlsLbl, controls);
        sidebar.setPadding(new Insets(16, 12, 16, 12));
        sidebar.setPrefWidth(160);
        sidebar.setMinWidth(160);
        sidebar.setMaxWidth(160);
        sidebar.setStyle("-fx-background-color: #0f0f1e;");

        // ── Canvas ───────────────────────────────────────────────────────────
        canvas = new Canvas(WORLD_W, WORLD_H);

        // Disconnected overlay (stacked on top of canvas, hidden by default)
        overlayTitleLabel = new Label("SERVER RESTARTING");
        overlayTitleLabel.setStyle("-fx-text-fill: #e94560; -fx-font-size: 22; -fx-font-weight: bold;");
        countdownLabel = new Label();
        countdownLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 14;");
        Button retryNowBtn = new Button("Retry Now");
        retryNowBtn.setStyle("""
                -fx-background-color: #2a6a4a;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 24 8 24;
                """);
        retryNowBtn.setOnAction(e -> attemptReconnect());
        disconnectedOverlay = new VBox(16, overlayTitleLabel, countdownLabel, retryNowBtn);
        disconnectedOverlay.setAlignment(Pos.CENTER);
        disconnectedOverlay.setStyle("-fx-background-color: rgba(10,10,20,0.92);");
        disconnectedOverlay.setVisible(false);
        // Make overlay fill the canvas pane
        disconnectedOverlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        StackPane canvasPane = new StackPane(canvas, disconnectedOverlay);
        canvasPane.setStyle("-fx-background-color: #1a1a2e;");

        // ── Game tab content (sidebar + canvas side by side) ─────────────────
        HBox gameContent = new HBox(sidebar, canvasPane);
        HBox.setHgrow(canvasPane, Priority.ALWAYS);
        gameContent.setStyle("-fx-background-color: #1a1a2e;");

        Tab gameTab = new Tab("🎮 Game", gameContent);
        gameTab.setClosable(false);

        // ── Settings tab (all users) ──────────────────────────────────────────
        Tab settingsTab = new Tab("⚙ Settings", new SettingsPanel(this::doRestart).buildView());
        settingsTab.setClosable(false);

        // ── Audio Dev tab (audio admins only) ────────────────────────────────
        TabPane tabPane;
        if (SessionStore.isAdmin()) {
            adminPanel = new AdminPanel(client);
            adminPanel.setRestartCallback(this::startReconnectCountdown);
            Tab adminTab = new Tab("🛡 Admin", adminPanel.buildView());
            adminTab.setClosable(false);
            AudioDevScreen audioDevScreen = new AudioDevScreen(stage);
            Tab audioTab = new Tab("🎵 Audio Dev", audioDevScreen.build());
            audioTab.setClosable(false);
            tabPane = new TabPane(gameTab, settingsTab, adminTab, audioTab);
        } else {
            tabPane = new TabPane(gameTab, settingsTab);
        }

        // ── Tab pane ─────────────────────────────────────────────────────────
        tabPane.setStyle("-fx-background-color: #1a1a2e; -fx-tab-min-width: 120;");
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        VBox root = new VBox(tabPane);
        root.setStyle("-fx-background-color: #1a1a2e;");

        Scene scene = new Scene(root, WORLD_W + 160, WORLD_H + 30);
        // Use filters (capture phase) so keys are tracked before any node handler runs.
        scene.addEventFilter(KeyEvent.KEY_PRESSED,  e -> heldKeys.add(e.getCode()));
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> heldKeys.remove(e.getCode()));

        // Prevent TabPane from consuming arrow keys for tab navigation while in-game.
        tabPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode c = e.getCode();
            if ((c == KeyCode.LEFT || c == KeyCode.RIGHT || c == KeyCode.UP || c == KeyCode.DOWN)
                    && tabPane.getSelectionModel().getSelectedItem() == gameTab) {
                e.consume();
            }
        });

        stage.setTitle(AppSettings.getProgramName() + " v" + com.game.shared.GameVersion.VERSION + " - ");
        stage.setScene(scene);
        stage.show();

        // ── Join game ────────────────────────────────────────────────────────
        sendPacket(PacketType.GAME_JOIN, PacketSerializer.emptyPayload());

        // ── Game loop ────────────────────────────────────────────────────────
        gameLoop = new AnimationTimer() {
            @Override public void handle(long now) {
                processInput();
                render();
            }
        };
        gameLoop.start();
        gameLoopRunning = true;
        if (adminPanel != null) adminPanel.start();

        // ── Ping ─────────────────────────────────────────────────────────────
        pingTimer = new Timeline(new KeyFrame(Duration.seconds(2),
                e -> sendPacket(PacketType.PING, PacketSerializer.emptyPayload())));
        pingTimer.setCycleCount(Timeline.INDEFINITE);
        pingTimer.play();

        // ── Heartbeat watchdog ────────────────────────────────────────────────
        lastGameStateMs = System.currentTimeMillis();
        heartbeatTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (!reconnecting
                    && System.currentTimeMillis() - lastGameStateMs > HEARTBEAT_TIMEOUT_MS) {
                showConnectionLost();
            }
        }));
        heartbeatTimer.setCycleCount(Timeline.INDEFINITE);
        heartbeatTimer.play();
    }

    // ── Input processing ─────────────────────────────────────────────────────

    private void processInput() {
        boolean moved = false;

        if (heldKeys.contains(KeyCode.W) || heldKeys.contains(KeyCode.UP)) {
            localY = Math.max(PLAYER_RADIUS, localY - PLAYER_SPEED); moved = true;
        }
        if (heldKeys.contains(KeyCode.S) || heldKeys.contains(KeyCode.DOWN)) {
            localY = Math.min(FLOOR_Y - PLAYER_RADIUS, localY + PLAYER_SPEED); moved = true;
        }
        if (heldKeys.contains(KeyCode.A) || heldKeys.contains(KeyCode.LEFT)) {
            localX = Math.max(PLAYER_RADIUS, localX - PLAYER_SPEED); moved = true;
        }
        if (heldKeys.contains(KeyCode.D) || heldKeys.contains(KeyCode.RIGHT)) {
            localX = Math.min(WORLD_W - PLAYER_RADIUS, localX + PLAYER_SPEED); moved = true;
        }

        long now = System.currentTimeMillis();
        if ((moved || (now - lastSendTime) > KEEPALIVE_MS) && (now - lastSendTime) > SEND_INTERVAL_MS) {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("x",     localX);
            payload.put("y",     localY);
            payload.put("score", localScore);
            sendPacket(PacketType.PLAYER_UPDATE, payload);
            lastSendTime = now;
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private static final Color[] PALETTE = {
        Color.web("#e94560"), Color.web("#0f3460"), Color.web("#53c0f0"),
        Color.web("#f5a623"), Color.web("#7ed321"), Color.web("#bd10e0")
    };

    private final Map<String, Color> colorMap = new ConcurrentHashMap<>();
    private int colorIndex = 0;

    private Color colorFor(String username) {
        return colorMap.computeIfAbsent(username,
                k -> PALETTE[(colorIndex++) % PALETTE.length]);
    }

    // Floor constants (instance — depend on WORLD_H)
    private final int   FLOOR_Y;  // floor surface Y position
    private static final int   FLOOR_H        = 40;
    private static final int   PLANK_W        = 80;
    private static final int   PLANK_GAP      = 2;

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, WORLD_W, WORLD_H);

        // World border
        gc.setStroke(Color.web("#3a3a6a"));
        gc.setLineWidth(2);
        gc.strokeRect(1, 1, WORLD_W - 2, WORLD_H - 2);

        // ── Floor ────────────────────────────────────────────────────────────

        // Floor base fill
        gc.setFill(Color.web("#2c1810"));
        gc.fillRect(0, FLOOR_Y, WORLD_W, FLOOR_H);

        // Floor planks
        for (int x = 0; x < WORLD_W; x += PLANK_W + PLANK_GAP) {
            // Alternating plank shades for depth
            boolean alt = ((x / (PLANK_W + PLANK_GAP)) % 2 == 0);
            gc.setFill(alt ? Color.web("#3d2112") : Color.web("#4a2a16"));
            gc.fillRect(x, FLOOR_Y + 4, PLANK_W, FLOOR_H - 4);

            // Plank highlight (top edge)
            gc.setFill(Color.web("#6b3d1e"));
            gc.fillRect(x, FLOOR_Y + 4, PLANK_W, 3);

            // Plank shadow (right edge)
            gc.setFill(Color.web("#1e0d06"));
            gc.fillRect(x + PLANK_W, FLOOR_Y + 4, PLANK_GAP, FLOOR_H - 4);
        }

        // Floor top edge highlight line
        gc.setFill(Color.web("#7a4a22"));
        gc.fillRect(0, FLOOR_Y, WORLD_W, 4);

        // Floor top glow
        gc.setFill(Color.color(0.48, 0.29, 0.13, 0.25));
        gc.fillRect(0, FLOOR_Y - 8, WORLD_W, 8);

        // Remote players
        for (Map.Entry<String, JsonNode> entry : remotePlayers.entrySet()) {
            String   username = entry.getKey();
            JsonNode state    = entry.getValue();
            if (username.equals(SessionStore.getUsername())) continue;

            float  rx       = (float) state.get("x").asDouble();
            float  ry       = (float) state.get("y").asDouble();
            int    rs       = state.get("score").asInt();
            String dispName = state.has("characterName")
                    ? state.get("characterName").asText() : username;
            drawPlayer(gc, rx, ry, dispName, rs, colorFor(username), false);
        }

        // Local player (on top)
        String localName = SessionStore.getCharacterName() != null && !SessionStore.getCharacterName().isBlank()
                ? SessionStore.getCharacterName() : SessionStore.getUsername();
        drawPlayer(gc, localX, localY, localName, localScore, Color.web("#e0e0ff"), true);
    }

    private void drawPlayer(GraphicsContext gc, float x, float y,
                            String name, int score, Color color, boolean isLocal) {
        // Shadow
        gc.setFill(Color.color(0, 0, 0, 0.3));
        gc.fillOval(x - PLAYER_RADIUS + 3, y - PLAYER_RADIUS + 3,
                PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);

        // Body
        gc.setFill(color);
        gc.fillOval(x - PLAYER_RADIUS, y - PLAYER_RADIUS,
                PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);

        // Outline
        gc.setStroke(isLocal ? Color.WHITE : Color.color(1, 1, 1, 0.4));
        gc.setLineWidth(isLocal ? 2.5 : 1.5);
        gc.strokeOval(x - PLAYER_RADIUS, y - PLAYER_RADIUS,
                PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);

        // Name label (centered above sprite)
        String label = name + " (" + score + ")";
        gc.setFill(Color.WHITE);
        javafx.scene.text.Font nameFont = Font.font("System", FontWeight.BOLD, 11);
        gc.setFont(nameFont);
        javafx.scene.text.Text measurer = new javafx.scene.text.Text(label);
        measurer.setFont(nameFont);
        double textW = measurer.getLayoutBounds().getWidth();
        gc.fillText(label, x - (float)(textW / 2), y - PLAYER_RADIUS - 5);
    }

    // ── Packet handling ──────────────────────────────────────────────────────

    private void onPacket(Packet packet) {
        if (adminPanel != null) adminPanel.onPacket(packet);
        switch (packet.type) {
            case GAME_STATE -> {
                lastGameStateMs = System.currentTimeMillis();
                JsonNode players = packet.payload.get("players");
                if (players != null && players.isArray()) {
                    Map<String, JsonNode> snapshot = new HashMap<>();
                    for (JsonNode p : players) {
                        snapshot.put(p.get("username").asText(), p);
                    }
                    remotePlayers.clear();
                    remotePlayers.putAll(snapshot);
                }

                int count = packet.payload.has("playerCount")
                        ? packet.payload.get("playerCount").asInt() : remotePlayers.size();
                Platform.runLater(() -> {
                    playerCountLabel.setText("Players: " + count);
                    if (reconnecting) onReconnected();
                });
            }
            case PONG -> {
                long rtt = System.currentTimeMillis() - packet.timestamp;
                Platform.runLater(() -> {
                    pingLabel.setText("Ping: " + rtt + " ms");
                    if (reconnecting) onReconnected();
                });
            }
            case SERVER_NOTICE -> {
                String msg = packet.payload.has("message")
                        ? packet.payload.get("message").asText() : "Server shutting down";
                Platform.runLater(() -> startReconnectCountdown(msg, SHUTDOWN_DELAY_SECONDS));
            }
            case ERROR -> {
                String msg = packet.payload.get("message").asText("Server error.");
                Platform.runLater(() -> {
                    pingLabel.setText("⚠ " + msg);
                    pingLabel.setStyle("-fx-text-fill: #e94560;");
                });
            }
            default -> { /* ignore */ }
        }
    }

    // ── Reconnect ────────────────────────────────────────────────────────────

    /** Called by the heartbeat watchdog when GAME_STATE stops arriving. */
    private void showConnectionLost() {
        Platform.runLater(() -> {
            if (reconnecting) return;
            reconnecting = true;
            if (gameLoopRunning) { gameLoop.stop(); gameLoopRunning = false; }
            overlayTitleLabel.setText("CONNECTION LOST");
            countdownLabel.setText("Attempting to reconnect…");
            disconnectedOverlay.setVisible(true);
            attemptReconnect();
        });
    }

    /** Called by AdminPanel callback (Consumer&lt;Integer&gt;). */
    public void startReconnectCountdown(int seconds) {
        startReconnectCountdown("SERVER RESTARTING", seconds);
    }

    private void startReconnectCountdown(String title, int seconds) {
        Platform.runLater(() -> {
            reconnecting = true;
            if (countdownTimer != null) countdownTimer.stop();
            if (retryTimer    != null) retryTimer.stop();
            if (gameLoopRunning) { gameLoop.stop(); gameLoopRunning = false; }
            overlayTitleLabel.setText(title);
            disconnectedOverlay.setVisible(true);
            countdownLabel.setText("Reconnecting in " + seconds + "s…");

            int[] remaining = {seconds};
            countdownTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                remaining[0]--;
                if (remaining[0] > 0) {
                    countdownLabel.setText("Reconnecting in " + remaining[0] + "s…");
                } else {
                    countdownTimer.stop();
                    countdownLabel.setText("Attempting to reconnect…");
                    attemptReconnect();
                }
            }));
            countdownTimer.setCycleCount(seconds);
            countdownTimer.play();
        });
    }

    private void attemptReconnect() {
        sendPacket(PacketType.GAME_JOIN, PacketSerializer.emptyPayload());
        if (retryTimer != null) retryTimer.stop();
        retryTimer = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            if (reconnecting) {
                countdownLabel.setText("Still trying…");
                sendPacket(PacketType.GAME_JOIN, PacketSerializer.emptyPayload());
            }
        }));
        retryTimer.setCycleCount(Timeline.INDEFINITE);
        retryTimer.play();
    }

    private void onReconnected() {
        reconnecting = false;
        if (countdownTimer != null) { countdownTimer.stop(); countdownTimer = null; }
        if (retryTimer     != null) { retryTimer.stop();     retryTimer     = null; }
        disconnectedOverlay.setVisible(false);
        if (!gameLoopRunning) { gameLoop.start(); gameLoopRunning = true; }
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private void doLogout() {
        if (gameLoopRunning) { gameLoop.stop(); gameLoopRunning = false; }
        pingTimer.stop();
        heartbeatTimer.stop();
        if (countdownTimer != null) countdownTimer.stop();
        if (retryTimer     != null) retryTimer.stop();
        if (adminPanel != null) adminPanel.stop();
        sendPacket(PacketType.GAME_LEAVE,    PacketSerializer.emptyPayload());
        sendPacket(PacketType.LOGOUT_REQUEST, PacketSerializer.emptyPayload());
        SessionStore.clear();
        new LoginScreen(stage, client).show();
    }

    private void doRestart() {
        if (gameLoopRunning) { gameLoop.stop(); gameLoopRunning = false; }
        pingTimer.stop();
        heartbeatTimer.stop();
        if (countdownTimer != null) countdownTimer.stop();
        if (retryTimer     != null) retryTimer.stop();
        if (adminPanel != null) adminPanel.stop();
        sendPacket(PacketType.GAME_LEAVE,     PacketSerializer.emptyPayload());
        sendPacket(PacketType.LOGOUT_REQUEST,  PacketSerializer.emptyPayload());
        SessionStore.clear();
        new LoginScreen(stage, client).show();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void sendPacket(PacketType type, ObjectNode payload) {
        client.send(new Packet(type, SessionStore.getToken(), payload));
    }
}
