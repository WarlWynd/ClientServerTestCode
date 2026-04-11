package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.GameResolution;
import com.game.client.SessionStore;
import com.game.client.ThemeManager;
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
    private static final float PLAYER_SPEED  = 6f;
    private static final float PLAYER_RADIUS = 14f;
    private static final long  SEND_INTERVAL_MS  = 50;     // max 20 updates/s to server
    private static final long  KEEPALIVE_MS      = 15_000; // keepalive when idle (prevents server eviction)

    /** Fixed game-world size — shared by all clients regardless of resolution. */
    static final int WORLD_W = 3200;
    static final int WORLD_H = 2400;

    // ── State ────────────────────────────────────────────────────────────────
    private final Stage     stage;
    private final UDPClient client;

    /** Viewport size = the client's chosen resolution. */
    private final int viewportW;
    private final int viewportH;

    /** Camera top-left in world coordinates (updated each frame to follow local player). */
    private float cameraX;
    private float cameraY;

    private float   localX;
    private float   localY;
    private float   velY       = 0f;   // vertical velocity (game coords: positive = up)
    private boolean wasJumpHeld = false; // tracks previous frame jump key state for impulse detection
    private int     localScore = 0;

    /** Latest server snapshot: sessionToken → player JsonNode */
    private final Map<String, JsonNode> remotePlayers = new ConcurrentHashMap<>();

    /** Sprite animators — one per player (local + remotes). */
    private final PlayerAnimator              localAnimator        = new PlayerAnimator();
    private final Map<String, PlayerAnimator> remoteAnimators      = new ConcurrentHashMap<>();
    /** Weapon renderers — paired 1-to-1 with each animator. */
    private final WeaponRenderer              localWeaponRenderer  = new WeaponRenderer();
    private final Map<String, WeaponRenderer> remoteWeaponRenderers = new ConcurrentHashMap<>();
    /** Previous remote positions for per-packet velocity estimation. */
    private final Map<String, float[]>        prevRemotePos   = new ConcurrentHashMap<>();

    private final Set<KeyCode> heldKeys = ConcurrentHashMap.newKeySet();
    private long lastSendTime = 0;

    // ── Test Fight Board / Enemy NPC ──────────────────────────────────────────
    private static final String TEST_FIGHT_BOARD = "test_fight_board";
    private float    npcX             = 500f;
    private float    npcY             = 14f;   // PLAYER_RADIUS — stands on floor
    private final PlayerAnimator npcAnimator       = new PlayerAnimator();
    private final WeaponRenderer npcWeaponRenderer = new WeaponRenderer();
    private long     npcLastAttackMs  = 0;
    private boolean  npcHitPending    = false;
    private long     npcHitTimeMs     = 0;
    private PlayerAnimator.State npcCurrentAttack = PlayerAnimator.State.PUNCH;
    private boolean  npcActive        = false;
    private static final long  NPC_ATTACK_INTERVAL_MS = 2_500;
    private static final long  NPC_HIT_DELAY_MS       = 500;
    private static final float NPC_HIT_RANGE          = 70f;
    private static final PlayerAnimator.State[] NPC_ATTACKS = {
        PlayerAnimator.State.PUNCH,     PlayerAnimator.State.CROSS,
        PlayerAnimator.State.HOOK,      PlayerAnimator.State.BODY_KICK,
        PlayerAnimator.State.SIDE_KICK, PlayerAnimator.State.SHOOT
    };
    private final List<DamageText> damageTexts = new ArrayList<>();
    private final Random rng = new Random();

    private static class DamageText {
        final float  worldX;
        final float  worldY;
        final String text;
        final long   birthMs;
        static final long LIFE_MS = 1_500;
        DamageText(float wx, float wy, String text) {
            this.worldX  = wx;
            this.worldY  = wy;
            this.text    = text;
            this.birthMs = System.currentTimeMillis();
        }
        double alpha()  { return Math.max(0.0, 1.0 - (System.currentTimeMillis() - birthMs) / (double) LIFE_MS); }
        float  riseY()  { return (float)((System.currentTimeMillis() - birthMs) / (double) LIFE_MS) * 50f; }
        boolean expired(){ return System.currentTimeMillis() - birthMs > LIFE_MS; }
    }

    private Label pingLabel;
    private Label playerCountLabel;
    private Label posLabel;
    private Label damageLastLabel;
    private Label damageTotalLabel;
    private Label hitStatusLabel;
    private int   totalDamageTaken = 0;
    private Canvas canvas;
    private AnimationTimer gameLoop;
    private TabPane tabPane;
    private Tab     gameTab;
    private boolean gameLoopRunning = false;
    private Timeline pingTimer;
    private AdminPanel adminPanel;

    // ── System message bar ────────────────────────────────────────────────────
    private HBox    systemMsgBar;
    private Label   systemMsgText;
    private Label   systemMsgCountdown;
    private Timeline sysMsgTimer;

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
        this.stage     = stage;
        this.client    = client;
        GameResolution res = AppSettings.getResolution();
        this.viewportW = res.width;
        this.viewportH = res.height;
        this.localX    = 260f;
        this.localY    = 20f;
        ensureTestFightBoard();
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        // ── Left sidebar ─────────────────────────────────────────────────────
        Label gameTitle = new Label(AppSettings.getProgramName().toUpperCase());
        gameTitle.getStyleClass().addAll("section-title", "font-16");
        gameTitle.setWrapText(true);

        Separator sep1 = new Separator();
        sep1.getStyleClass().add("sep");

        Label playingAsLbl = new Label("CHARACTER");
        playingAsLbl.getStyleClass().addAll("text-muted", "font-10");
        String displayName = SessionStore.getCharacterName() != null && !SessionStore.getCharacterName().isBlank()
                ? SessionStore.getCharacterName()
                : SessionStore.getUsername();
        Label nameLabel = new Label(displayName);
        nameLabel.getStyleClass().addAll("text-primary", "bold", "font-14");
        nameLabel.setWrapText(true);

        Separator sep2 = new Separator();
        sep2.getStyleClass().add("sep");

        Label onlineLbl = new Label("ONLINE");
        onlineLbl.getStyleClass().addAll("text-muted", "font-10");
        playerCountLabel = new Label("1 Player");
        playerCountLabel.getStyleClass().addAll("text-info", "bold", "font-13");

        Separator sep3 = new Separator();
        sep3.getStyleClass().add("sep");

        Label pingLbl = new Label("PING");
        pingLbl.getStyleClass().addAll("text-muted", "font-10");
        pingLabel = new Label("-- ms");
        pingLabel.getStyleClass().addAll("text-success", "bold", "font-13");

        Separator sep4 = new Separator();
        sep4.getStyleClass().add("sep");

        Label controlsLbl = new Label("CONTROLS");
        controlsLbl.getStyleClass().addAll("text-muted", "font-10");
        Label controls = new Label("W A S D\nor Arrow Keys\nto move");
        controls.getStyleClass().addAll("text-muted", "font-11");
        controls.setWrapText(true);

        Separator sep5 = new Separator();
        sep5.getStyleClass().add("sep");

        Label posLbl = new Label("POSITION");
        posLbl.getStyleClass().addAll("text-muted", "font-10");
        posLabel = new Label("0, 0");
        posLabel.getStyleClass().addAll("text-primary", "font-11");

        Separator sep6 = new Separator();
        sep6.getStyleClass().add("sep");

        Label damageLbl = new Label("DAMAGE TAKEN");
        damageLbl.getStyleClass().addAll("text-muted", "font-10");
        damageLastLabel = new Label("Last hit: —");
        damageLastLabel.setStyle("-fx-text-fill: #ffff00; -fx-font-weight: bold; -fx-font-size: 12;");
        damageLastLabel.setWrapText(true);
        damageTotalLabel = new Label("Total: 0");
        damageTotalLabel.setStyle("-fx-text-fill: #ff8888; -fx-font-weight: bold; -fx-font-size: 11;");

        VBox sidebar = new VBox(10,
                gameTitle,
                sep1,
                playingAsLbl, nameLabel,
                sep2,
                onlineLbl, playerCountLabel,
                sep3,
                pingLbl, pingLabel,
                sep4,
                controlsLbl, controls,
                sep5,
                posLbl, posLabel,
                sep6,
                damageLbl, damageLastLabel, damageTotalLabel);
        sidebar.setPadding(new Insets(16, 12, 16, 12));
        sidebar.setPrefWidth(160);
        sidebar.setMinWidth(160);
        sidebar.setMaxWidth(160);
        sidebar.getStyleClass().add("app-surface");

        // ── Canvas ───────────────────────────────────────────────────────────
        canvas = new Canvas(viewportW, viewportH);

        // Disconnected overlay (stacked on top of canvas, hidden by default)
        overlayTitleLabel = new Label("SERVER RESTARTING");
        overlayTitleLabel.getStyleClass().add("overlay-title");
        countdownLabel = new Label();
        countdownLabel.getStyleClass().add("overlay-subtitle");
        Button retryNowBtn = new Button("Retry Now");
        retryNowBtn.getStyleClass().add("btn-success");
        retryNowBtn.setOnAction(e -> attemptReconnect());
        disconnectedOverlay = new VBox(16, overlayTitleLabel, countdownLabel, retryNowBtn);
        disconnectedOverlay.setAlignment(Pos.CENTER);
        disconnectedOverlay.getStyleClass().add("overlay-bg");
        disconnectedOverlay.setVisible(false);
        // Make overlay fill the canvas pane
        disconnectedOverlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        StackPane canvasPane = new StackPane(canvas, disconnectedOverlay);
        canvasPane.getStyleClass().add("app-root");

        // ── System message bar (server notices only) ─────────────────────────
        Label statusLabel = new Label("Status: ");
        statusLabel.getStyleClass().add("system-msg-text");

        systemMsgText = new Label();
        systemMsgText.getStyleClass().add("system-msg-text");

        systemMsgCountdown = new Label();
        systemMsgCountdown.getStyleClass().add("system-msg-countdown");

        hitStatusLabel = new Label();
        hitStatusLabel.setStyle("-fx-text-fill: #ff4444; -fx-font-weight: bold; -fx-font-size: 12;");

        Region msgSpacer = new Region();
        HBox.setHgrow(msgSpacer, Priority.ALWAYS);

        systemMsgBar = new HBox(12, statusLabel, systemMsgText, msgSpacer, hitStatusLabel, systemMsgCountdown);
        systemMsgBar.setAlignment(Pos.CENTER_LEFT);
        systemMsgBar.setPadding(new Insets(5, 14, 5, 14));
        systemMsgBar.getStyleClass().add("system-msg-bar");
        systemMsgBar.setVisible(false);
        systemMsgBar.setManaged(false);

        // ── Game tab content (sidebar + canvas side by side) ─────────────────
        HBox gameContent = new HBox(sidebar, canvasPane);
        HBox.setHgrow(canvasPane, Priority.ALWAYS);
        gameContent.getStyleClass().add("app-root");
        VBox.setVgrow(gameContent, Priority.ALWAYS);

        VBox gameTabRoot = new VBox(systemMsgBar, gameContent);
        gameTabRoot.getStyleClass().add("app-root");

        String serverIp = client.getServerHost();
        gameTab = new Tab("🎮 Game " + serverIp, gameTabRoot);
        gameTab.setClosable(false);
        gameTab.getProperties().put("connectionIp", serverIp);

        // ── Settings tab (all users) ──────────────────────────────────────────
        Tab settingsTab = new Tab("⚙ Settings " + serverIp, new SettingsPanel(this::doRestart,
                side -> tabPane.setSide(side),
                iconOnly -> applyTabLabels(iconOnly),
                this::doLogout).buildView());
        settingsTab.setClosable(false);
        settingsTab.getProperties().put("connectionIp", serverIp);

        // ── Role-gated tabs ───────────────────────────────────────────────────
        java.util.List<Tab> tabs = new java.util.ArrayList<>();
        tabs.add(gameTab);
        tabs.add(settingsTab);

        if (SessionStore.isAdmin()) {
            Tab isAdminTab = new Tab("Is Admin " + client.getAdminHost(), buildIsAdminView());
            isAdminTab.setClosable(false);
            isAdminTab.getProperties().put("connectionIp", client.getAdminHost());
            tabs.add(isAdminTab);

            adminPanel = new AdminPanel(client);
            adminPanel.setRestartCallback(this::startReconnectCountdown);
            String adminIp = client.getAdminHost();
            Tab adminTab = new Tab("🛡 Admin " + adminIp, withIpBanner(adminPanel.buildView(), adminIp));
            adminTab.setClosable(false);
            adminTab.getProperties().put("connectionIp", adminIp);
            Tab audioTab = new Tab("🎵 Audio Dev " + adminIp, withIpBanner(new AudioDevScreen(stage).build(), adminIp));
            audioTab.setClosable(false);
            audioTab.getProperties().put("connectionIp", adminIp);
            Tab graphicsTab = new Tab("🎨 Graphics Dev " + adminIp, withIpBanner(new GraphicsDevScreen(stage).build(), adminIp));
            graphicsTab.setClosable(false);
            graphicsTab.getProperties().put("connectionIp", adminIp);
            Tab boardTab = new Tab("🗺 Board Dev " + adminIp, withIpBanner(new BoardDevScreen(stage,
                    () -> tabPane.getSelectionModel().select(gameTab)).build(), adminIp));
            boardTab.setClosable(false);
            boardTab.getProperties().put("connectionIp", adminIp);
            tabs.add(adminTab);
            tabs.add(audioTab);
            tabs.add(graphicsTab);
            tabs.add(boardTab);
        } else if (SessionStore.isGraphicsDev()) {
            String adminIp = client.getAdminHost();
            Tab graphicsTab = new Tab("🎨 Graphics Dev " + adminIp, withIpBanner(new GraphicsDevScreen(stage).build(), adminIp));
            graphicsTab.setClosable(false);
            graphicsTab.getProperties().put("connectionIp", adminIp);
            tabs.add(graphicsTab);
        } else if (SessionStore.isBoardDev()) {
            String adminIp = client.getAdminHost();
            Tab boardTab = new Tab("🗺 Board Dev " + adminIp, withIpBanner(new BoardDevScreen(stage,
                    () -> tabPane.getSelectionModel().select(gameTab)).build(), adminIp));
            boardTab.setClosable(false);
            boardTab.getProperties().put("connectionIp", adminIp);
            tabs.add(boardTab);
        }

        tabPane = new TabPane(tabs.toArray(new Tab[0]));

        // Store full labels so we can toggle icon-only mode without data loss
        for (Tab t : tabPane.getTabs()) t.getProperties().put("fullText", t.getText());
        applyTabLabels(AppSettings.isTabIconOnly());

        // ── Tab pane ─────────────────────────────────────────────────────────
        tabPane.setSide("LEFT".equalsIgnoreCase(AppSettings.getTabSide())
                ? javafx.geometry.Side.LEFT : javafx.geometry.Side.TOP);
        tabPane.getStyleClass().add("tab-pane-dark");
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        VBox root = new VBox(tabPane);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, viewportW + 160, viewportH + 30);
        ThemeManager.apply(scene);
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

        // Update window title to reflect the active tab
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) stage.setTitle(AppSettings.getProgramName()
                    + " v" + com.game.shared.GameVersion.VERSION + " — " + tabFullName(n)
                    + " -- " + tabConnectionIp(n));
        });
        stage.setTitle(AppSettings.getProgramName() + " v" + com.game.shared.GameVersion.VERSION
                + " — " + tabFullName(tabPane.getSelectionModel().getSelectedItem())
                + " -- " + tabConnectionIp(tabPane.getSelectionModel().getSelectedItem()));
        stage.setScene(scene);
        stage.show();

        // ── Join game ────────────────────────────────────────────────────────
        sendPacket(PacketType.GAME_JOIN, PacketSerializer.emptyPayload());

        // ── Game loop ────────────────────────────────────────────────────────
        gameLoop = new AnimationTimer() {
            @Override public void handle(long now) {
                processInput();
                updateNpc();
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
        float speed = AppSettings.getRunSpeed();

        KeyCode jumpKey = keyCodeOf(AppSettings.getKeyJump(), KeyCode.W);

        // ── Ladder detection ──────────────────────────────────────────────────
        boolean onLadder = nearLadder();

        if (onLadder) {
            // Suspend gravity while on ladder; climbUp key/↑ climbs up, climbDown key/↓ climbs down.
            velY        = 0f;
            wasJumpHeld = false;   // reset so jump fires normally after leaving ladder
            float   climbSpeed = speed * 0.75f;
            KeyCode climbUp    = keyCodeOf(AppSettings.getKeyClimbUp(),   KeyCode.W);
            KeyCode climbDown  = keyCodeOf(AppSettings.getKeyClimbDown(), KeyCode.S);
            if (heldKeys.contains(climbUp)   || heldKeys.contains(KeyCode.UP)) {
                float ladderTop = topOfLadder() - 3f;
                if (ladderTop >= 0 && localY - PLAYER_RADIUS < ladderTop) {
                    localY = Math.min(ladderTop + PLAYER_RADIUS, localY + climbSpeed);
                    moved  = true;
                }
            }
            if (heldKeys.contains(climbDown) || heldKeys.contains(KeyCode.DOWN)) {
                localY = Math.max(PLAYER_RADIUS, localY - climbSpeed);
                moved  = true;
            }
        } else {
            // ── Normal vertical physics ───────────────────────────────────────

            // Ground check BEFORE applying this frame's gravity/velocity
            boolean onGround = localY <= PLAYER_RADIUS + 2f || isStandingOnSolid();

            velY -= AppSettings.getGravity();

            // Jump: impulse on key-down (rising edge only, not held)
            boolean jumpHeld = heldKeys.contains(jumpKey) || heldKeys.contains(KeyCode.UP);
            if (jumpHeld && !wasJumpHeld && onGround) {
                velY = AppSettings.getJumpStrength(); // impulse overrides current velocity
            }
            wasJumpHeld = jumpHeld;

            // Apply vertical velocity and clamp to world
            localY += velY;
            if (localY <= PLAYER_RADIUS) { localY = PLAYER_RADIUS; velY = 0f; }
            if (localY >= FLOOR_Y_CANVAS - PLAYER_RADIUS) { localY = FLOOR_Y_CANVAS - PLAYER_RADIUS; velY = 0f; }

            // Solid tile floor collision — snap feet to top of any solid tile entered from above
            if (velY <= 0) {
                int solidRow = solidRowUnderFeet();
                if (solidRow >= 0) {
                    double tileH       = (double) FLOOR_Y_CANVAS / BoardStore.getRows();
                    float  tileTopGameY = (float)(FLOOR_Y_CANVAS - solidRow * tileH);
                    // Only snap if player centre is above the tile top (fell from above, not walked in sideways)
                    if (localY >= tileTopGameY) {
                        localY = tileTopGameY + PLAYER_RADIUS;
                        velY   = 0f;
                    }
                }
            }
        }
        moved = true;

        // ── Horizontal movement ───────────────────────────────────────────────
        // Sprint key multiplies run speed while held
        KeyCode sprintKey = keyCodeOf(AppSettings.getKeySprint(), KeyCode.SHIFT);
        float actualSpeed = heldKeys.contains(sprintKey) ? speed * 1.75f : speed;

        float localVelX = 0;
        if (heldKeys.contains(KeyCode.A) || heldKeys.contains(KeyCode.LEFT)) {
            localX    = Math.max(PLAYER_RADIUS, localX - actualSpeed);
            localVelX = -actualSpeed;
            moved     = true;
        }
        if (heldKeys.contains(KeyCode.D) || heldKeys.contains(KeyCode.RIGHT)) {
            localX    = Math.min(WORLD_W - PLAYER_RADIUS, localX + actualSpeed);
            localVelX = actualSpeed;
            moved     = true;
        }

        localAnimator.update(localVelX, velY, localY <= PLAYER_RADIUS + 2f || isStandingOnSolid());
        updateCamera();

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

    private static KeyCode keyCodeOf(String name, KeyCode fallback) {
        try { return KeyCode.valueOf(name.toUpperCase().replace(" ", "_")); }
        catch (Exception e) { return fallback; }
    }

    private void updateCamera() {
        float playerCY = toCanvasY(localY);
        cameraX = Math.max(0, Math.min(WORLD_W - viewportW, localX - viewportW / 2f));
        cameraY = Math.max(0, Math.min(WORLD_H - viewportH, playerCY - viewportH / 2f));
    }

    /** Returns the board tile at the player's current centre position, or AIR if no board is loaded. */
    private BoardTile tileAtPlayer() {
        if (!BoardStore.isLoaded()) return BoardTile.AIR;
        int    tCols  = BoardStore.getCols();
        int    tRows  = BoardStore.getRows();
        double tileW  = (double) WORLD_W / tCols;
        double tileH  = (double) FLOOR_Y_CANVAS / tRows;
        int    col    = (int)(localX / tileW);
        int    row    = (int)(toCanvasY(localY) / tileH);
        if (row < 0 || row >= tRows || col < 0 || col >= tCols) return BoardTile.AIR;
        return BoardStore.getBoard()[row][col];
    }

    /**
     * Returns true if a LADDER tile is within the player's reachable column range —
     * from one tile below the feet up to one tile above the head. This lets the player
     * grab a ladder from the floor without needing to jump first.
     */
    private boolean nearLadder() {
        if (!BoardStore.isLoaded()) return false;
        int           tCols  = BoardStore.getCols();
        int           tRows  = BoardStore.getRows();
        double        tileW  = (double) WORLD_W / tCols;
        double        tileH  = (double) FLOOR_Y_CANVAS / tRows;
        int           col    = (int)(localX / tileW);
        if (col < 0 || col >= tCols) return false;
        // canvas Y: head is higher on screen (lower value), feet are lower (higher value)
        int rowHead = Math.max(0,         (int)(toCanvasY(localY + PLAYER_RADIUS) / tileH));
        int rowFeet = Math.min(tRows - 1, (int)(toCanvasY(localY - PLAYER_RADIUS) / tileH) + 1);
        BoardTile[][] board = BoardStore.getBoard();
        for (int r = rowHead; r <= rowFeet; r++) {
            if (board[r][col] == BoardTile.LADDER) return true;
        }
        return false;
    }

    /**
     * Returns the game-Y of the top edge of the topmost LADDER tile in the player's column,
     * or -1 if no ladder is present. This is the highest position the player's feet can reach.
     */
    private float topOfLadder() {
        if (!BoardStore.isLoaded()) return -1;
        int    tCols  = BoardStore.getCols();
        int    tRows  = BoardStore.getRows();
        double tileW  = (double) WORLD_W / tCols;
        double tileH  = (double) FLOOR_Y_CANVAS / tRows;
        int    col    = (int)(localX / tileW);
        if (col < 0 || col >= tCols) return -1;
        BoardTile[][] board = BoardStore.getBoard();
        // Scan from top row downward to find the first (topmost) ladder tile
        for (int r = 0; r < tRows; r++) {
            if (board[r][col] == BoardTile.LADDER) {
                // Top edge of this tile in game coords
                return (float)(FLOOR_Y_CANVAS - r * tileH);
            }
        }
        return -1;
    }

    /** Returns the board row of the first solid tile at or just below the player's feet, or -1. */
    private int solidRowUnderFeet() {
        if (!BoardStore.isLoaded()) return -1;
        int    tCols  = BoardStore.getCols();
        int    tRows  = BoardStore.getRows();
        double tileW  = (double) WORLD_W / tCols;
        double tileH  = (double) FLOOR_Y_CANVAS / tRows;
        float  feetCY = toCanvasY(localY - PLAYER_RADIUS);
        int    row    = (int)(feetCY / tileH);
        int    cLeft  = (int)((localX - PLAYER_RADIUS * 0.5f) / tileW);
        int    cRight = (int)((localX + PLAYER_RADIUS * 0.5f) / tileW);
        BoardTile[][] board = BoardStore.getBoard();
        for (int c = Math.max(0, cLeft); c <= Math.min(tCols - 1, cRight); c++) {
            if (row >= 0 && row < tRows && board[row][c].solid) return row;
        }
        return -1;
    }

    /** True when the player's feet are resting on a solid tile surface. */
    private boolean isStandingOnSolid() {
        if (!BoardStore.isLoaded()) return false;
        int    tCols  = BoardStore.getCols();
        int    tRows  = BoardStore.getRows();
        double tileW  = (double) WORLD_W / tCols;
        double tileH  = (double) FLOOR_Y_CANVAS / tRows;
        // Check 1 canvas-pixel below current feet so we detect "resting on" vs "inside"
        float  feetCY = toCanvasY(localY - PLAYER_RADIUS) + 1f;
        int    row    = (int)(feetCY / tileH);
        int    cLeft  = (int)((localX - PLAYER_RADIUS * 0.5f) / tileW);
        int    cRight = (int)((localX + PLAYER_RADIUS * 0.5f) / tileW);
        BoardTile[][] board = BoardStore.getBoard();
        for (int c = Math.max(0, cLeft); c <= Math.min(tCols - 1, cRight); c++) {
            if (row >= 0 && row < tRows && board[row][c].solid) return true;
        }
        return false;
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

    // Floor constants — FLOOR_Y_CANVAS is the canvas Y where the floor surface sits.
    // Game coords have y=0 at the floor, positive y going up toward the sky.
    private static final int   FLOOR_Y_CANVAS = WORLD_H - 40;   // 2360
    private static final int   FLOOR_H        = 40;

    /** Convert game Y (0 = floor, positive = up) to canvas Y (0 = top, positive = down). */
    private static float toCanvasY(float gameY) { return FLOOR_Y_CANVAS - gameY; }
    private static final int   PLANK_W        = 80;
    private static final int   PLANK_GAP      = 2;

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear viewport, then translate so all world drawing uses world coordinates
        gc.clearRect(0, 0, viewportW, viewportH);
        gc.save();
        gc.translate(-cameraX, -cameraY);

        // Background
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, WORLD_W, WORLD_H);

        // Background grid (gives visual reference for scrolling)
        gc.setStroke(Color.web("#22224a"));
        gc.setLineWidth(1);
        for (int gx = 0; gx <= WORLD_W; gx += 100) {
            gc.strokeLine(gx, 0, gx, WORLD_H);
        }
        for (int gy = 0; gy <= WORLD_H; gy += 100) {
            gc.strokeLine(0, gy, WORLD_W, gy);
        }

        // World border
        gc.setStroke(Color.web("#3a3a6a"));
        gc.setLineWidth(2);
        gc.strokeRect(1, 1, WORLD_W - 2, WORLD_H - 2);

        // ── Floor ────────────────────────────────────────────────────────────

        // Floor base fill
        gc.setFill(Color.web("#2c1810"));
        gc.fillRect(0, FLOOR_Y_CANVAS, WORLD_W, FLOOR_H);

        // Floor planks
        for (int x = 0; x < WORLD_W; x += PLANK_W + PLANK_GAP) {
            // Alternating plank shades for depth
            boolean alt = ((x / (PLANK_W + PLANK_GAP)) % 2 == 0);
            gc.setFill(alt ? Color.web("#3d2112") : Color.web("#4a2a16"));
            gc.fillRect(x, FLOOR_Y_CANVAS + 4, PLANK_W, FLOOR_H - 4);

            // Plank highlight (top edge)
            gc.setFill(Color.web("#6b3d1e"));
            gc.fillRect(x, FLOOR_Y_CANVAS + 4, PLANK_W, 3);

            // Plank shadow (right edge)
            gc.setFill(Color.web("#1e0d06"));
            gc.fillRect(x + PLANK_W, FLOOR_Y_CANVAS + 4, PLANK_GAP, FLOOR_H - 4);
        }

        // Floor top edge highlight line
        gc.setFill(Color.web("#7a4a22"));
        gc.fillRect(0, FLOOR_Y_CANVAS, WORLD_W, 4);

        // Floor top glow
        gc.setFill(Color.color(0.48, 0.29, 0.13, 0.25));
        gc.fillRect(0, FLOOR_Y_CANVAS - 8, WORLD_W, 8);

        // ── Board tiles ───────────────────────────────────────────────────────
        if (BoardStore.isLoaded()) {
            BoardTile[][] tiles  = BoardStore.getBoard();
            int           tRows  = BoardStore.getRows();
            int           tCols  = BoardStore.getCols();
            double        tileW  = (double) WORLD_W / tCols;
            double        tileH  = (double) FLOOR_Y_CANVAS / tRows;
            for (int r = 0; r < tRows; r++) {
                for (int c = 0; c < tCols; c++) {
                    BoardTile t = tiles[r][c];
                    if (t == BoardTile.AIR) continue;
                    double tx = c * tileW;
                    double ty = r * tileH;
                    gc.setFill(t.fill);
                    gc.fillRect(tx + 1, ty + 1, tileW - 2, tileH - 2);
                    gc.setStroke(t.border);
                    gc.setLineWidth(1);
                    gc.strokeRect(tx + 1, ty + 1, tileW - 2, tileH - 2);
                }
            }
        }

        // Remote players
        for (Map.Entry<String, JsonNode> entry : remotePlayers.entrySet()) {
            String   token = entry.getKey();
            JsonNode pNode = entry.getValue();
            if (token.equals(SessionStore.getToken()))    continue;
            if (token.equals(SessionStore.getUsername())) continue;
            String username = pNode.get("username").asText();
            float  rx       = (float) pNode.get("x").asDouble();
            float  ry       = (float) pNode.get("y").asDouble();
            int    rs       = pNode.get("score").asInt();
            String dispName = pNode.has("characterName") ? pNode.get("characterName").asText() : username;
            PlayerAnimator anim = remoteAnimators.computeIfAbsent(token, k -> new PlayerAnimator());
            WeaponRenderer rWep = remoteWeaponRenderers.computeIfAbsent(token, k -> new WeaponRenderer());
            float rFeetY = toCanvasY(ry - PLAYER_RADIUS);
            rWep.drawBehindBody(gc, anim, rx, rFeetY, colorFor(username));
            anim.draw(gc, rx, rFeetY, colorFor(username));
            rWep.draw(gc, anim, rx, rFeetY, colorFor(username));
            drawNameLabel(gc, rx, rFeetY, dispName, rs);
        }

        // Enemy NPC (Test Fight Board)
        if (npcActive) {
            Color npcColor  = Color.web("#ff4444");
            float npcFeetY  = toCanvasY(npcY - PLAYER_RADIUS);
            npcWeaponRenderer.drawBehindBody(gc, npcAnimator, npcX, npcFeetY, npcColor);
            npcAnimator.draw(gc, npcX, npcFeetY, npcColor);
            npcWeaponRenderer.draw(gc, npcAnimator, npcX, npcFeetY, npcColor);
            drawNameLabel(gc, npcX, npcFeetY, "ENEMY", 0);
        }

        // Local player (drawn on top)
        String localName  = SessionStore.getCharacterName() != null && !SessionStore.getCharacterName().isBlank()
                ? SessionStore.getCharacterName() : SessionStore.getUsername();
        float localFeetY  = toCanvasY(localY - PLAYER_RADIUS);
        localWeaponRenderer.drawBehindBody(gc, localAnimator, localX, localFeetY, Color.web("#e0e0ff"));
        localAnimator.draw(gc, localX, localFeetY, Color.web("#e0e0ff"));
        localWeaponRenderer.draw(gc, localAnimator, localX, localFeetY, Color.web("#e0e0ff"));
        drawNameLabel(gc, localX, localFeetY, localName, localScore);

        // Damage texts — floating bold yellow numbers
        if (!damageTexts.isEmpty()) {
            gc.setFont(Font.font("System", FontWeight.BOLD, 18));
            for (DamageText dt : damageTexts) {
                double alpha = dt.alpha();
                if (alpha <= 0) continue;
                gc.setFill(Color.color(1.0, 1.0, 0.0, alpha));
                gc.fillText(dt.text, dt.worldX - 10, toCanvasY(dt.worldY) - dt.riseY());
            }
        }

        gc.restore();

        posLabel.setText((int) localX + ", " + (int) localY);
    }

    /** Draw the name+score label centred above the sprite's head. */
    private void drawNameLabel(GraphicsContext gc, float x, float canvasY, String name, int score) {
        String label = name + " (" + score + ")";
        javafx.scene.text.Font nameFont = Font.font("System", FontWeight.BOLD, 11);
        gc.setFont(nameFont);
        javafx.scene.text.Text measurer = new javafx.scene.text.Text(label);
        measurer.setFont(nameFont);
        double textW = measurer.getLayoutBounds().getWidth();
        // Draw a semi-transparent backing pill for readability
        double px = x - textW / 2 - 4, py = canvasY - PlayerAnimator.LABEL_ABOVE - 15;
        gc.setFill(Color.color(0, 0, 0, 0.45));
        gc.fillRoundRect(px, py, textW + 8, 14, 4, 4);
        gc.setFill(Color.WHITE);
        gc.fillText(label, x - (float)(textW / 2), canvasY - PlayerAnimator.LABEL_ABOVE - 3);
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
                        String key = p.has("sessionToken") && !p.get("sessionToken").asText().isEmpty()
                                ? p.get("sessionToken").asText()
                                : p.get("username").asText();
                        snapshot.put(key, p);

                        // Update remote animator from position delta (skip own entry)
                        if (!key.equals(SessionStore.getToken())) {
                            float rx = (float) p.get("x").asDouble();
                            float ry = (float) p.get("y").asDouble();
                            float[] prev = prevRemotePos.getOrDefault(key, new float[]{rx, ry});
                            float rVelX = rx - prev[0];
                            float rVelY = ry - prev[1];
                            remoteAnimators.computeIfAbsent(key, k -> new PlayerAnimator())
                                           .update(rVelX, rVelY, ry <= PLAYER_RADIUS + 4f);
                            prevRemotePos.put(key, new float[]{rx, ry});
                        }
                    }
                    remoteAnimators.keySet().retainAll(snapshot.keySet());
                    prevRemotePos.keySet().retainAll(snapshot.keySet());
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
                int countdown = packet.payload.has("countdown")
                        ? packet.payload.get("countdown").asInt(0) : 0;
                Platform.runLater(() -> showSystemMessage(msg, countdown));
            }
            case ADMIN_SAVE_SETTINGS_RESPONSE -> {
                boolean ok  = packet.payload.has("success") && packet.payload.get("success").asBoolean();
                String  msg = ok ? "Game settings committed to server."
                                 : packet.payload.has("message")
                                     ? packet.payload.get("message").asText("Save failed.")
                                     : "Save failed.";
                Platform.runLater(() -> showSystemMessage(msg, 0));
            }
            case SERVER_SETTINGS -> {
                float g  = (float) packet.payload.get("gravity").asDouble(AppSettings.getGravity());
                float j  = (float) packet.payload.get("jumpStrength").asDouble(AppSettings.getJumpStrength());
                float rs = (float) packet.payload.get("runSpeed").asDouble(AppSettings.getRunSpeed());
                AppSettings.setGravity(g);
                AppSettings.setJumpStrength(j);
                AppSettings.setRunSpeed(rs);
                if (packet.payload.has("allowRememberPassword"))
                    AppSettings.setAllowRememberPassword(packet.payload.get("allowRememberPassword").asBoolean());
                if (packet.payload.has("showTestNpc"))
                    AppSettings.setShowTestNpc(packet.payload.get("showTestNpc").asBoolean());
                if (packet.payload.has("testNpcX"))
                    AppSettings.setTestNpcX((float) packet.payload.get("testNpcX").asDouble());
                if (packet.payload.has("testNpcY"))
                    AppSettings.setTestNpcY((float) packet.payload.get("testNpcY").asDouble());
                if (packet.payload.has("localServerHost"))
                    AppSettings.setServerHost(packet.payload.get("localServerHost").asText());
                if (packet.payload.has("localServerPort"))
                    AppSettings.setServerPort(packet.payload.get("localServerPort").asInt());
                if (packet.payload.has("externalServerHost"))
                    AppSettings.setExternalServerHost(packet.payload.get("externalServerHost").asText());
                if (packet.payload.has("externalServerPort"))
                    AppSettings.setExternalServerPort(packet.payload.get("externalServerPort").asInt());
                if (packet.payload.has("allowExternalAdmin"))
                    AppSettings.setAllowExternalAdmin(packet.payload.get("allowExternalAdmin").asBoolean());
                if (packet.payload.has("allowExternalDev"))
                    AppSettings.setAllowExternalDev(packet.payload.get("allowExternalDev").asBoolean());
                if (packet.payload.has("rebootDelaySecs"))
                    AppSettings.setRebootDelaySecs(packet.payload.get("rebootDelaySecs").asInt());
                if (packet.payload.has("rebootMessage"))
                    AppSettings.setRebootMessage(packet.payload.get("rebootMessage").asText());
                AppSettings.save();
            }
            case FORCE_LOGOUT -> {
                String msg = packet.payload.has("message")
                        ? packet.payload.get("message").asText()
                        : "You have been logged out because your account signed in from another location.";
                Platform.runLater(() -> {
                    if (gameLoop != null) gameLoop.stop();
                    SessionStore.clear();
                    new LoginScreen(stage, client).show();
                    // Brief alert so the user knows why they were kicked
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.WARNING);
                    alert.setTitle("Logged Out");
                    alert.setHeaderText("Session ended");
                    alert.setContentText(msg);
                    alert.show();
                });
            }
            case ERROR -> {
                String msg = packet.payload.get("message").asText("Server error.");
                Platform.runLater(() -> {
                    pingLabel.setText("⚠ " + msg);
                    pingLabel.setStyle("-fx-text-fill: -af-error;");
                });
            }
            default -> { /* ignore */ }
        }
    }

    // ── Reconnect ────────────────────────────────────────────────────────────

    // ── System message bar ────────────────────────────────────────────────────

    private void showSystemMessage(String msg, int countdownSeconds) {
        if (sysMsgTimer != null) sysMsgTimer.stop();
        systemMsgText.setText(msg);
        systemMsgCountdown.setText(countdownSeconds > 0 ? countdownSeconds + "s" : "");
        systemMsgBar.setVisible(true);
        systemMsgBar.setManaged(true);

        if (countdownSeconds > 0) {
            int[] remaining = {countdownSeconds};
            sysMsgTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                remaining[0]--;
                if (remaining[0] > 0) {
                    systemMsgCountdown.setText(remaining[0] + "s");
                } else {
                    sysMsgTimer.stop();
                    systemMsgCountdown.setText("");
                }
            }));
            sysMsgTimer.setCycleCount(countdownSeconds);
            sysMsgTimer.play();
        }
    }

    private void clearSystemMessage() {
        if (sysMsgTimer != null) { sysMsgTimer.stop(); sysMsgTimer = null; }
        systemMsgText.setText("");
        systemMsgCountdown.setText("");
        systemMsgBar.setVisible(false);
        systemMsgBar.setManaged(false);
    }

    // ── Reconnect overlay ────────────────────────────────────────────────────

    /** Called by the heartbeat watchdog when GAME_STATE stops arriving. */
    private void showConnectionLost() {
        Platform.runLater(() -> {
            if (reconnecting) return;
            reconnecting = true;
            if (gameLoopRunning) { gameLoop.stop(); gameLoopRunning = false; }
            overlayTitleLabel.setText("CONNECTION LOST");
            countdownLabel.setText("Attempting to reconnect…");
            disconnectedOverlay.setVisible(true);
            pingLabel.getStyleClass().setAll("text-error", "bold", "font-13");
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
            tabPane.getSelectionModel().select(gameTab);
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
        pingLabel.getStyleClass().setAll("text-success", "bold", "font-13");
        clearSystemMessage();
        if (!gameLoopRunning) { gameLoop.start(); gameLoopRunning = true; }
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private void applyTabLabels(boolean iconOnly) {
        for (Tab t : tabPane.getTabs()) {
            String full = (String) t.getProperties().get("fullText");
            if (full == null) continue;
            t.setText(iconOnly ? full.split(" ", 2)[0] : full);
            t.setTooltip(iconOnly ? new Tooltip(full) : null);
            t.setStyle("");
        }
        tabPane.setStyle("");
    }

    /** Returns the full (icon + text) label for a tab, used in the window title. */
    private static String tabFullName(Tab t) {
        String full = (String) t.getProperties().get("fullText");
        String label = full != null ? full : t.getText();
        return label.replaceAll("[^\\p{ASCII}]", "").trim();
    }

    private static String tabConnectionIp(Tab t) {
        Object ip = t.getProperties().get("connectionIp");
        return ip != null ? ip.toString() : "";
    }

    private void doLogout() {
        if (gameLoopRunning) { gameLoop.stop(); gameLoopRunning = false; }
        pingTimer.stop();
        heartbeatTimer.stop();
        if (countdownTimer != null) countdownTimer.stop();
        if (retryTimer     != null) retryTimer.stop();
        clearSystemMessage();
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

    // ── Test Fight Board ──────────────────────────────────────────────────────

    /** Creates/recreates ~/.game/boards/test_fight_board.csv (44×58, bottom row PLATFORM). */
    private static void ensureTestFightBoard() {
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.home"), ".game/boards");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, TEST_FIGHT_BOARD + ".csv");
            // Regenerate if the file is stale (wrong dimensions)
            if (f.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                    String header = br.readLine();
                    if (header != null && header.startsWith("44,58")) return;
                } catch (Exception ignored) {}
                f.delete();
            }
            int rows = 44, cols = 58;
            try (java.io.PrintWriter pw = new java.io.PrintWriter(f)) {
                pw.println(rows + "," + cols);
                for (int r = 0; r < rows; r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int c = 0; c < cols; c++) {
                        if (c > 0) sb.append(',');
                        sb.append(r == rows - 1 ? "PLATFORM" : "AIR");
                    }
                    pw.println(sb);
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateNpc() {
        if (!AppSettings.isShowTestNpc()) {
            npcActive = false;
            damageTexts.clear();
            return;
        }
        npcActive = true;
        npcX      = AppSettings.getTestNpcX();
        npcY      = AppSettings.getTestNpcY();

        // Face toward player
        float npcVelX = npcX > localX ? -0.4f : 0.4f;
        npcAnimator.update(npcVelX, 0f, true);

        long now = System.currentTimeMillis();

        // Launch new attack on interval
        if (now - npcLastAttackMs > NPC_ATTACK_INTERVAL_MS) {
            npcLastAttackMs  = now;
            npcCurrentAttack = NPC_ATTACKS[rng.nextInt(NPC_ATTACKS.length)];
            npcAnimator.forceState(npcCurrentAttack);
            // Schedule hit check at impact timing
            npcHitPending = true;
            npcHitTimeMs  = now + NPC_HIT_DELAY_MS;
        }

        // Process pending hit
        if (npcHitPending && now >= npcHitTimeMs) {
            npcHitPending = false;
            if (Math.abs(npcX - localX) <= NPC_HIT_RANGE) {
                spawnDamageText(npcCurrentAttack);
            }
        }

        damageTexts.removeIf(DamageText::expired);
    }

    private static final String[] DAMAGE_LABELS = { "5", "8", "10", "12", "15", "18", "20", "25" };

    private static String attackLabel(PlayerAnimator.State s) {
        return switch (s) {
            case PUNCH     -> "Jab";
            case CROSS     -> "Cross";
            case HOOK      -> "Hook";
            case UPPERCUT  -> "Uppercut";
            case HAYMAKER  -> "Haymaker";
            case HEAD_KICK -> "Head Kick";
            case LOW_KICK  -> "Low Kick";
            case BODY_KICK -> "Body Kick";
            case SPINNING_BACK_KICK -> "Spinning Back Kick";
            case SIDE_KICK -> "Side Kick";
            case SHOOT     -> "Shot";
            default        -> s.name();
        };
    }

    private void spawnDamageText(PlayerAnimator.State attack) {
        String dmg    = DAMAGE_LABELS[rng.nextInt(DAMAGE_LABELS.length)];
        String label  = attackLabel(attack);
        float wx = localX + (rng.nextFloat() - 0.5f) * 20f;
        float wy = localY + PLAYER_RADIUS + 10f;
        damageTexts.add(new DamageText(wx, wy, "-" + dmg));
        int amount = Integer.parseInt(dmg);
        totalDamageTaken += amount;
        Platform.runLater(() -> {
            damageLastLabel.setText(label + "  -" + dmg + " hp");
            damageTotalLabel.setText("Total: " + totalDamageTaken + " hp");
            hitStatusLabel.setText("Hit by " + label + "  \u2212" + dmg + " hp  (total: " + totalDamageTaken + ")");
        });
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> hitStatusLabel.setText(""));
        }, "hit-clear").start();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds the content for the "Is Admin" confirmation tab. */
    private javafx.scene.Node buildIsAdminView() {
        Label title = new Label("Admin Access Confirmed");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #4caf50;");

        Label user  = new Label("User:        " + SessionStore.getUsername());
        Label role  = new Label("Role:        Administrator");
        Label admIp = new Label("Admin Host:  " + client.getAdminHost());

        for (Label l : new Label[]{user, role, admIp}) {
            l.setStyle("-fx-font-size: 13px; -fx-text-fill: #cccccc; -fx-font-family: monospace;");
        }

        VBox box = new VBox(12, title, user, role, admIp);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: #1e1e1e;");
        return box;
    }

    /** Wraps a tab's content node with a small IP/connection banner at the very top. */
    private static javafx.scene.Node withIpBanner(javafx.scene.Node content, String ip) {
        Label banner = new Label("Connects to: " + ip);
        banner.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888; -fx-padding: 3 8 3 8;"
                + " -fx-background-color: #1e1e1e; -fx-border-color: #333333;"
                + " -fx-border-width: 0 0 1 0;");
        banner.setMaxWidth(Double.MAX_VALUE);
        VBox wrapper = new VBox(banner, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return wrapper;
    }

    private void sendPacket(PacketType type, ObjectNode payload) {
        client.send(new Packet(type, SessionStore.getToken(), payload));
    }
}
