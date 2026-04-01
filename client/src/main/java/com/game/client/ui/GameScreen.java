package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.AssetSyncClient;
import com.game.client.AudioManager;
import com.game.client.MobilePlatform;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.animation.AnimationTimer;
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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
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
    private static final int   WORLD_W       = 800;
    private static final int   WORLD_H       = 600;
    private static final float PLAYER_SPEED  = 3.5f;
    private static final float PLAYER_RADIUS = 14f;
    private static final long  SEND_INTERVAL_MS = 50; // max 20 updates/s to server

    // ── State ────────────────────────────────────────────────────────────────
    private final Stage     stage;
    private final UDPClient client;
    private final String    version;

    private float   localX = WORLD_W / 2f;
    private float   localY = WORLD_H / 2f;
    private int     localScore = 0;

    /** Latest server snapshot: username → {x, y, score} */
    private final Map<String, JsonNode> remotePlayers = new ConcurrentHashMap<>();

    private final Set<KeyCode> heldKeys = ConcurrentHashMap.newKeySet();
    private boolean wasSpaceHeld = false;
    private boolean wasMoving    = false;

    /** Usernames seen in the last GAME_STATE — used to detect joins/leaves. */
    private final Set<String> knownPlayers = ConcurrentHashMap.newKeySet();
    private long lastSendTime = 0;
    private long lastHeartbeatTime = 0;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;

    private Label pingLabel;
    private Label playerCountLabel;
    private Canvas canvas;
    private AnimationTimer gameLoop;
    private AdminPanel adminPanel;     // non-null only when SessionStore.isAdmin()
    private VirtualJoystick joystick;  // non-null only on mobile

    public GameScreen(Stage stage, UDPClient client, String version) {
        this.stage   = stage;
        this.client  = client;
        this.version = version;
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        // ── HUD top bar ──────────────────────────────────────────────────────
        Label nameLabel = new Label("Playing as: " + SessionStore.getUsername());
        nameLabel.setStyle("-fx-text-fill: #e0e0e0; -fx-font-weight: bold;");

        playerCountLabel = new Label("Players: 1");
        playerCountLabel.setStyle("-fx-text-fill: #a0a0c0;");

        pingLabel = new Label("");
        pingLabel.setStyle("-fx-text-fill: #80c080;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button logoutBtn = new Button("Logout");
        logoutBtn.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-size: 12;
                -fx-background-radius: 4;
                """);
        logoutBtn.setOnAction(e -> doLogout());

        HBox hud = new HBox(14, nameLabel, playerCountLabel, pingLabel, spacer, logoutBtn);
        hud.setAlignment(Pos.CENTER_LEFT);
        hud.setPadding(new Insets(6, 12, 6, 12));
        hud.setStyle("-fx-background-color: #0f0f1e;");

        // ── Canvas (responsive — binds to container size) ─────────────────────
        canvas = new Canvas();
        StackPane gameArea = new StackPane(canvas);
        VBox.setVgrow(gameArea, Priority.ALWAYS);
        canvas.widthProperty().bind(gameArea.widthProperty());
        canvas.heightProperty().bind(gameArea.heightProperty());

        if (MobilePlatform.isMobile()) {
            joystick = new VirtualJoystick(160, 160);
            joystick.setOpacity(AppSettings.getHudOpacity());
            StackPane.setAlignment(joystick, Pos.BOTTOM_LEFT);
            StackPane.setMargin(joystick, new Insets(0, 0, 16, 16));
            gameArea.getChildren().add(joystick);
        }

        // ── Help bar ─────────────────────────────────────────────────────────
        String helpText = MobilePlatform.isMobile() ? "Use joystick to move" : "Move: WASD or Arrow Keys";
        Label help = new Label(helpText);
        help.setStyle("-fx-text-fill: #606080; -fx-font-size: 11;");
        HBox helpBar = new HBox(help);
        helpBar.setAlignment(Pos.CENTER);
        helpBar.setPadding(new Insets(4));
        helpBar.setStyle("-fx-background-color: #0f0f1e;");

        // ── Root layout ──────────────────────────────────────────────────────
        Tab gameTab = new Tab("Game", new VBox(gameArea, helpBar));
        gameTab.setClosable(false);

        Tab settingsTab = new Tab("Settings", new SettingsPanel().buildView());
        settingsTab.setClosable(false);

        TabPane tabs = new TabPane(gameTab);
        tabs.setStyle("-fx-background-color: #1a1a2e;");

        if (SessionStore.isAdmin()) {
            adminPanel = new AdminPanel(client);
            Tab adminTab = new Tab("Admin", adminPanel.buildView());
            adminTab.setClosable(false);
            tabs.getTabs().add(adminTab);
        }

        tabs.getTabs().add(settingsTab);

        if (SessionStore.isAdmin() || SessionStore.isDeveloper()) {
            Tab audioDevTab = new Tab("Audio Dev", new AudioDevPanel().buildView());
            audioDevTab.setClosable(false);
            tabs.getTabs().add(audioDevTab);
        }

        // Re-focus canvas and clear held keys when returning to the game tab
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, cur) -> {
            if (cur == gameTab) {
                heldKeys.clear();
                canvas.requestFocus();
            }
        });

        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox root = new VBox(hud, tabs);

        root.setStyle("-fx-background-color: #1a1a2e;");

        Scene scene = MobilePlatform.isMobile()
                ? new Scene(root)
                : new Scene(root, WORLD_W, WORLD_H + 96);

        if (MobilePlatform.isDesktop()) {
            final Set<KeyCode> movementKeys = Set.of(
                    KeyCode.W, KeyCode.A, KeyCode.S, KeyCode.D,
                    KeyCode.UP, KeyCode.DOWN, KeyCode.LEFT, KeyCode.RIGHT);
            scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                heldKeys.add(e.getCode());
                if (movementKeys.contains(e.getCode())) e.consume();
            });
            scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
                heldKeys.remove(e.getCode());
                if (movementKeys.contains(e.getCode())) e.consume();
            });
        }

        stage.setTitle("Multiplayer Game");
        stage.setScene(scene);
        stage.show();

        canvas.setFocusTraversable(true);
        canvas.requestFocus();

        // ── Asset sync (background) ──────────────────────────────────────────
        String assetUrl = SessionStore.getAssetUrl();
        String token    = SessionStore.getToken();
        if (assetUrl != null) {
            Thread.ofPlatform().daemon(true).name("asset-sync").start(
                    () -> AssetSyncClient.sync(assetUrl, token));
        }

        // ── Join game ────────────────────────────────────────────────────────
        sendPacket(PacketType.GAME_JOIN, PacketSerializer.emptyPayload());

        // ── Start admin polling (if applicable) ──────────────────────────────
        if (adminPanel != null) adminPanel.start();

        // ── Game loop ────────────────────────────────────────────────────────
        gameLoop = new AnimationTimer() {
            @Override public void handle(long now) {
                processInput();
                render();
            }
        };
        gameLoop.start();
    }

    // ── Input processing ─────────────────────────────────────────────────────

    private void processInput() {
        boolean moved = false;

        if (joystick != null) {
            // Touch input
            double dx = joystick.getDx();
            double dy = joystick.getDy();
            if (Math.abs(dx) > 0.05 || Math.abs(dy) > 0.05) {
                localX = Math.max(PLAYER_RADIUS, Math.min(WORLD_W - PLAYER_RADIUS, localX + (float)(dx * PLAYER_SPEED)));
                localY = Math.max(PLAYER_RADIUS, Math.min(WORLD_H - PLAYER_RADIUS, localY + (float)(dy * PLAYER_SPEED)));
                moved = true;
            }
        } else {
            // Keyboard input
            boolean spaceNow = heldKeys.contains(KeyCode.SPACE);
            if (spaceNow && !wasSpaceHeld) playSpaceSound();
            wasSpaceHeld = spaceNow;

            if (heldKeys.contains(KeyCode.W) || heldKeys.contains(KeyCode.UP)) {
                localY = Math.max(PLAYER_RADIUS, localY - PLAYER_SPEED); moved = true;
            }
            if (heldKeys.contains(KeyCode.S) || heldKeys.contains(KeyCode.DOWN)) {
                localY = Math.min(WORLD_H - PLAYER_RADIUS, localY + PLAYER_SPEED); moved = true;
            }
            if (heldKeys.contains(KeyCode.A) || heldKeys.contains(KeyCode.LEFT)) {
                localX = Math.max(PLAYER_RADIUS, localX - PLAYER_SPEED); moved = true;
            }
            if (heldKeys.contains(KeyCode.D) || heldKeys.contains(KeyCode.RIGHT)) {
                localX = Math.min(WORLD_W - PLAYER_RADIUS, localX + PLAYER_SPEED); moved = true;
            }
        }

        if (moved && !wasMoving) AudioManager.play("move.wav");
        wasMoving = moved;

        long now = System.currentTimeMillis();
        if (moved && (now - lastSendTime) > SEND_INTERVAL_MS) {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("x",     localX);
            payload.put("y",     localY);
            payload.put("score", localScore);
            sendPacket(PacketType.PLAYER_UPDATE, payload);
            lastSendTime = now;
            lastHeartbeatTime = now;
        } else if (!moved && (now - lastHeartbeatTime) > HEARTBEAT_INTERVAL_MS) {
            ObjectNode payload = PacketSerializer.mapper().createObjectNode();
            payload.put("x",     localX);
            payload.put("y",     localY);
            payload.put("score", localScore);
            sendPacket(PacketType.PLAYER_UPDATE, payload);
            lastHeartbeatTime = now;
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

    private void render() {
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        if (cw <= 0 || ch <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Scale world to canvas, preserving aspect ratio (letterbox)
        double scale  = Math.min(cw / WORLD_W, ch / WORLD_H);
        double offsetX = (cw - WORLD_W * scale) / 2.0;
        double offsetY = (ch - WORLD_H * scale) / 2.0;

        // Background (full canvas)
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, cw, ch);

        gc.save();
        gc.translate(offsetX, offsetY);
        gc.scale(scale, scale);

        // World border
        gc.setStroke(Color.web("#3a3a6a"));
        gc.setLineWidth(2 / scale);
        gc.strokeRect(1, 1, WORLD_W - 2, WORLD_H - 2);

        // Remote players
        for (Map.Entry<String, JsonNode> entry : remotePlayers.entrySet()) {
            String   name  = entry.getKey();
            JsonNode state = entry.getValue();
            if (name.equals(SessionStore.getUsername())) continue;

            float rx = (float) state.get("x").asDouble();
            float ry = (float) state.get("y").asDouble();
            int   rs = state.get("score").asInt();
            drawPlayer(gc, rx, ry, name, rs, colorFor(name), false);
        }

        // Local player (on top)
        drawPlayer(gc, localX, localY, SessionStore.getUsername(), localScore,
                Color.web("#e0e0ff"), true);

        gc.restore();
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

        // Name label
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        gc.fillText(name + " (" + score + ")",
                x - PLAYER_RADIUS, y - PLAYER_RADIUS - 5);
    }

    // ── Packet handling ──────────────────────────────────────────────────────

    private void onPacket(Packet packet) {
        switch (packet.type) {
            case GAME_STATE -> {
                JsonNode players = packet.payload.get("players");
                if (players != null && players.isArray()) {
                    Map<String, JsonNode> snapshot = new HashMap<>();
                    for (JsonNode p : players) {
                        snapshot.put(p.get("username").asText(), p);
                    }

                    // Detect joins and leaves (ignore local player)
                    String me = SessionStore.getUsername();
                    for (String u : snapshot.keySet()) {
                        if (!u.equals(me) && !knownPlayers.contains(u))
                            AudioManager.play("player_join.wav");
                    }
                    for (String u : knownPlayers) {
                        if (!snapshot.containsKey(u))
                            AudioManager.play("player_leave.wav");
                    }
                    knownPlayers.clear();
                    knownPlayers.addAll(snapshot.keySet());

                    // Score-up detection for local player
                    if (snapshot.containsKey(me)) {
                        int serverScore = snapshot.get(me).get("score").asInt();
                        if (serverScore > localScore) AudioManager.play("score.wav");
                        localScore = serverScore;
                    }

                    remotePlayers.clear();
                    remotePlayers.putAll(snapshot);
                }

                int count = packet.payload.has("playerCount")
                        ? packet.payload.get("playerCount").asInt() : remotePlayers.size();
                Platform.runLater(() -> playerCountLabel.setText("Players: " + count));
            }
            case PONG -> {
                long rtt = System.currentTimeMillis() - packet.timestamp;
                Platform.runLater(() -> pingLabel.setText("Ping: " + rtt + " ms"));
            }
            case ERROR -> {
                String msg = packet.payload.get("message").asText("Server error.");
                Platform.runLater(() -> {
                    pingLabel.setText("⚠ " + msg);
                    pingLabel.setStyle("-fx-text-fill: #e94560;");
                });
            }
            case ADMIN_USER_LIST_RESPONSE, ADMIN_KICK_RESPONSE, ADMIN_BAN_RESPONSE,
                 ADMIN_SET_ADMIN_RESPONSE, ADMIN_RESTART_RESPONSE, ADMIN_DEPLOY_RESPONSE -> {
                if (adminPanel != null) adminPanel.onPacket(packet);
            }
            default -> { /* ignore */ }
        }
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private void doLogout() {
        gameLoop.stop();
        if (adminPanel != null) adminPanel.stop();
        sendPacket(PacketType.GAME_LEAVE,    PacketSerializer.emptyPayload());
        sendPacket(PacketType.LOGOUT_REQUEST, PacketSerializer.emptyPayload());
        SessionStore.clear();
        new LoginScreen(stage, client, version).show();
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    private void playSpaceSound() {
        if (MobilePlatform.isMobile()) return; // javax.sound.sampled unavailable on Android
        double vol = AppSettings.getSoundMode().volume;
        if (vol == 0.0) return;
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                AudioFormat fmt = new AudioFormat(44100, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt, 4096);
                    line.start();
                    int frames = (int)(44100 * 0.12); // ~120 ms
                    byte[] buf = new byte[frames * 2];
                    for (int i = 0; i < frames; i++) {
                        double t   = i / 44100.0;
                        double env = 1.0 - (double) i / frames; // linear fade-out
                        short  s   = (short)(Math.sin(2 * Math.PI * 520 * t) * env * Short.MAX_VALUE * 0.5 * vol);
                        buf[i * 2]     = (byte)(s & 0xFF);
                        buf[i * 2 + 1] = (byte)(s >> 8);
                    }
                    line.write(buf, 0, buf.length);
                    line.drain();
                }
            } catch (Exception ignored) {}
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void sendPacket(PacketType type, ObjectNode payload) {
        client.send(new Packet(type, SessionStore.getToken(), payload));
    }
}
