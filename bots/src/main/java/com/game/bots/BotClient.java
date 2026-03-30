package com.game.bots;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A single bot that autonomously cycles through:
 *   IDLE → (register once) → LOGGING_IN → ONLINE → logout → IDLE → …
 *
 * Each bot owns its own DatagramSocket (ephemeral port) so the server can
 * route responses back correctly.
 */
public class BotClient {

    public enum State { STOPPED, IDLE, REGISTERING, LOGGING_IN, ONLINE }

    private static final Random RAND    = new Random();
    private static final float  WORLD_W = 800f;
    private static final float  WORLD_H = 600f;

    private final String username;
    private final String password;
    private final String serverHost;
    private final int    serverPort;

    private volatile State   state       = State.STOPPED;
    private volatile boolean active      = false;
    private volatile boolean registered  = false;
    private volatile long    onlineSince = 0;
    private volatile String  token       = null;
    private volatile float   x           = WORLD_W / 2;
    private volatile float   y           = WORLD_H / 2;

    private DatagramSocket socket;
    private Thread         loopThread;

    /** Set by the main loop before each request; completed by the receive loop. */
    private volatile CompletableFuture<Packet> pendingResponse;

    public BotClient(String username, String password, String serverHost, int serverPort) {
        this.username   = username;
        this.password   = password;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void start() {
        if (active) return;
        active     = true;
        state      = State.IDLE;
        loopThread = Thread.ofVirtual().name("bot-" + username).start(this::mainLoop);
    }

    public void stop() {
        active = false;
        if (loopThread != null) loopThread.interrupt();
        // Best-effort logout
        if (token != null) {
            sendRaw(PacketType.GAME_LEAVE,     PacketSerializer.emptyPayload());
            sendRaw(PacketType.LOGOUT_REQUEST, PacketSerializer.emptyPayload());
        }
        closeSocket();
        token       = null;
        onlineSince = 0;
        state       = State.STOPPED;
    }

    public State   getState()       { return state; }
    public String  getUsername()    { return username; }
    public long    getOnlineSince() { return onlineSince; }
    public boolean isActive()       { return active; }

    // ── Main loop ────────────────────────────────────────────────────────────

    private void mainLoop() {
        try {
            openSocket();
        } catch (Exception e) {
            state = State.STOPPED;
            active = false;
            return;
        }

        try {
            while (active) {

                // ── IDLE: random wait before next session ────────────────────
                state = State.IDLE;
                sleepRandom(5_000, 20_000);
                if (!active) break;

                // ── REGISTER: only on first cycle ────────────────────────────
                if (!registered) {
                    state = State.REGISTERING;
                    doRegister();  // errors/timeouts are non-fatal; proceed to login
                    if (!active) break;
                    registered = true;
                }

                // ── LOGIN ────────────────────────────────────────────────────
                state = State.LOGGING_IN;
                if (!doLogin()) {
                    sleepRandom(3_000, 8_000);
                    continue;
                }
                if (!active) break;

                // ── ONLINE ───────────────────────────────────────────────────
                state       = State.ONLINE;
                onlineSince = System.currentTimeMillis();
                x = 50 + RAND.nextFloat() * (WORLD_W - 100);
                y = 50 + RAND.nextFloat() * (WORLD_H - 100);

                sendRaw(PacketType.GAME_JOIN, PacketSerializer.emptyPayload());

                long sessionEnd = System.currentTimeMillis()
                        + 30_000 + RAND.nextLong(150_000); // 30 s – 3 min

                while (active && System.currentTimeMillis() < sessionEnd) {
                    moveRandomly();
                    ObjectNode p = PacketSerializer.mapper().createObjectNode();
                    p.put("x",     x);
                    p.put("y",     y);
                    p.put("score", 0);
                    sendRaw(PacketType.PLAYER_UPDATE, p);
                    sleepRandom(80, 200);
                }

                // ── LOGOUT ───────────────────────────────────────────────────
                sendRaw(PacketType.GAME_LEAVE,     PacketSerializer.emptyPayload());
                sendRaw(PacketType.LOGOUT_REQUEST, PacketSerializer.emptyPayload());
                token       = null;
                onlineSince = 0;
            }
        } catch (InterruptedException ignored) {
            // stop() interrupted us
        } finally {
            if (token != null) {
                sendRaw(PacketType.GAME_LEAVE,     PacketSerializer.emptyPayload());
                sendRaw(PacketType.LOGOUT_REQUEST, PacketSerializer.emptyPayload());
            }
            closeSocket();
            token       = null;
            onlineSince = 0;
            state       = State.STOPPED;
            active      = false;
        }
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private void doRegister() throws InterruptedException {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("password", password);

        CompletableFuture<Packet> future = new CompletableFuture<>();
        pendingResponse = future;
        sendRaw(PacketType.REGISTER_REQUEST, payload);

        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception ignored) {
            // timeout or error — proceed anyway; login will tell us if there's a real problem
        } finally {
            pendingResponse = null;
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────

    private boolean doLogin() throws InterruptedException {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("password", password);

        CompletableFuture<Packet> future = new CompletableFuture<>();
        pendingResponse = future;
        sendRaw(PacketType.LOGIN_REQUEST, payload);

        try {
            Packet resp = future.get(5, TimeUnit.SECONDS);
            if (resp.type == PacketType.LOGIN_RESPONSE
                    && resp.payload.get("success").asBoolean()) {
                token = resp.payload.get("sessionToken").asText();
                return true;
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception ignored) {}
        finally {
            pendingResponse = null;
        }
        return false;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void openSocket() throws Exception {
        InetAddress addr = InetAddress.getByName(serverHost);
        socket = new DatagramSocket();
        // Store addr for sends
        this.serverAddr = addr;
        Thread.ofVirtual().name("bot-recv-" + username).start(this::receiveLoop);
    }

    private InetAddress serverAddr;

    private void closeSocket() {
        if (socket != null && !socket.isClosed()) socket.close();
    }

    private void sendRaw(PacketType type, ObjectNode payload) {
        if (socket == null || socket.isClosed()) return;
        try {
            byte[] data = PacketSerializer.serialize(new Packet(type, token, payload));
            socket.send(new DatagramPacket(data, data.length, serverAddr, serverPort));
        } catch (Exception ignored) {}
    }

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (active && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.receive(dp);
                byte[] trimmed = new byte[dp.getLength()];
                System.arraycopy(dp.getData(), 0, trimmed, 0, dp.getLength());
                Packet pkt = PacketSerializer.deserialize(trimmed);

                // Complete any pending synchronous request
                CompletableFuture<Packet> future = pendingResponse;
                if (future != null && !future.isDone()) future.complete(pkt);
                // All other packets (GAME_STATE, PONG, etc.) are silently dropped
            } catch (Exception ignored) {}
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void moveRandomly() {
        x = Math.max(14, Math.min(WORLD_W - 14, x + (RAND.nextFloat() - 0.5f) * 8f));
        y = Math.max(14, Math.min(WORLD_H - 14, y + (RAND.nextFloat() - 0.5f) * 8f));
    }

    private void sleepRandom(long minMs, long maxMs) throws InterruptedException {
        Thread.sleep(minMs + (long)(RAND.nextDouble() * (maxMs - minMs)));
    }
}
