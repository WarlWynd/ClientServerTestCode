package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    private float   localX = WORLD_W / 2f;
    private float   localY = WORLD_H / 2f;
    private int     localScore = 0;

    /** Latest server snapshot: username → {x, y, score} */
    private final Map<String, JsonNode> remotePlayers = new ConcurrentHashMap<>();

    private final Set<KeyCode> heldKeys = ConcurrentHashMap.newKeySet();
    private boolean wasSpaceHeld = false;
    private long lastSendTime = 0;

    private Label pingLabel;
    private Label playerCountLabel;
    private Canvas canvas;
    private AnimationTimer gameLoop;

    public GameScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
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

        // ── Canvas ───────────────────────────────────────────────────────────
        canvas = new Canvas(WORLD_W, WORLD_H);

        // ── Help bar ─────────────────────────────────────────────────────────
        Label help = new Label("Move: WASD or Arrow Keys");
        help.setStyle("-fx-text-fill: #606080; -fx-font-size: 11;");
        HBox helpBar = new HBox(help);
        helpBar.setAlignment(Pos.CENTER);
        helpBar.setPadding(new Insets(4));
        helpBar.setStyle("-fx-background-color: #0f0f1e;");

        // ── Root layout ──────────────────────────────────────────────────────
        VBox root = new VBox(hud, canvas, helpBar);
        root.setStyle("-fx-background-color: #1a1a2e;");

        Scene scene = new Scene(root, WORLD_W, WORLD_H + 64);
        scene.addEventFilter(KeyEvent.KEY_PRESSED,  e -> heldKeys.add(e.getCode()));
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> heldKeys.remove(e.getCode()));

        stage.setTitle("Multiplayer Game");
        stage.setScene(scene);
        stage.show();

        canvas.setFocusTraversable(true);
        canvas.requestFocus();

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
    }

    // ── Input processing ─────────────────────────────────────────────────────

    private void processInput() {
        boolean spaceNow = heldKeys.contains(KeyCode.SPACE);
        if (spaceNow && !wasSpaceHeld) playSpaceSound();
        wasSpaceHeld = spaceNow;

        boolean moved = false;

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

        long now = System.currentTimeMillis();
        if (moved && (now - lastSendTime) > SEND_INTERVAL_MS) {
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

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, WORLD_W, WORLD_H);

        // World border
        gc.setStroke(Color.web("#3a3a6a"));
        gc.setLineWidth(2);
        gc.strokeRect(1, 1, WORLD_W - 2, WORLD_H - 2);

        // Remote players
        for (Map.Entry<String, JsonNode> entry : remotePlayers.entrySet()) {
            String   name  = entry.getKey();
            JsonNode state = entry.getValue();
            if (name.equals(SessionStore.getUsername())) continue; // draw local separately

            float rx = (float) state.get("x").asDouble();
            float ry = (float) state.get("y").asDouble();
            int   rs = state.get("score").asInt();
            drawPlayer(gc, rx, ry, name, rs, colorFor(name), false);
        }

        // Local player (on top)
        drawPlayer(gc, localX, localY, SessionStore.getUsername(), localScore,
                Color.web("#e0e0ff"), true);
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
            default -> { /* ignore */ }
        }
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private void doLogout() {
        gameLoop.stop();
        sendPacket(PacketType.GAME_LEAVE,    PacketSerializer.emptyPayload());
        sendPacket(PacketType.LOGOUT_REQUEST, PacketSerializer.emptyPayload());
        SessionStore.clear();
        new LoginScreen(stage, client).show();
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    private void playSpaceSound() {
        Thread.ofVirtual().start(() -> {
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
                        short  s   = (short)(Math.sin(2 * Math.PI * 520 * t) * env * Short.MAX_VALUE * 0.5);
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
