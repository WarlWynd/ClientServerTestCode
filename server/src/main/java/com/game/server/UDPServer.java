package com.game.server;

import com.game.server.db.SessionRepository;
import com.game.server.model.Session;
import com.game.shared.GameVersion;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Core UDP server.
 *
 * Receive loop runs on the calling thread.  Each incoming datagram is
 * dispatched to a virtual-thread worker (Java 21+ preview, available in 23)
 * so packet handling never blocks the receive loop.
 *
 * Scheduled tasks:
 *   - every 5 min : purge expired DB sessions
 *   - every 30 sec: evict players whose last PLAYER_UPDATE was > 30 s ago
 */
public class UDPServer {

    private static final Logger log = LoggerFactory.getLogger(UDPServer.class);

    private static final int  RECV_BUFFER_SIZE   = 4096;    // bytes
    private static final long PLAYER_TIMEOUT_MS  = 30_000;  // 30 s

    private final int port;
    private final AuthHandler        authHandler    = new AuthHandler();
    private final GameHandler        gameHandler    = new GameHandler();
    private final CharacterHandler   charHandler    = new CharacterHandler();
    private final AdminPacketHandler adminHandler   = new AdminPacketHandler(authHandler, gameHandler);
    private final SessionRepository sessionRepo = new SessionRepository();

    private DatagramSocket         socket;
    private volatile boolean       running = false;
    private ExecutorService        workers;
    private ScheduledExecutorService scheduler;

    public UDPServer(int port) {
        this.port = port;
    }

    public AuthHandler getAuthHandler() { return authHandler; }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() throws Exception {
        socket    = new DatagramSocket(port);
        running   = true;
        workers   = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = Executors.newScheduledThreadPool(1,
                r -> Thread.ofPlatform().name("scheduler").daemon(true).unstarted(r));

        scheduler.scheduleAtFixedRate(this::periodicCleanup,  5, 5,  TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(this::evictIdlePlayers, 30, 30, TimeUnit.SECONDS);

        log.info("*** UDP game server listening on port {} — ready", port);

        byte[] recvBuf = new byte[RECV_BUFFER_SIZE];
        while (running) {
            DatagramPacket dp = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(dp);                         // blocks until a datagram arrives

            // copy before handing off to worker thread
            byte[]      data      = dp.getData().clone();
            int         length    = dp.getLength();
            InetAddress addr      = dp.getAddress();
            int         clientPort = dp.getPort();

            workers.submit(() -> dispatch(data, length, addr, clientPort));
        }
    }

    public void stop() {
        running = false;
        if (socket    != null) socket.close();
        if (workers   != null) workers.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
        log.info("Server stopped.");
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    private void dispatch(byte[] data, int length, InetAddress addr, int port) {
        try {
            byte[] trimmed = new byte[length];
            System.arraycopy(data, 0, trimmed, 0, length);
            Packet packet = PacketSerializer.deserialize(trimmed);

            switch (packet.type) {
                case VERSION_CHECK     -> handleVersionCheck(socket, packet, addr, port);
                case LOGIN_REQUEST    -> authHandler.handleLogin(socket, packet, addr, port);
                case REGISTER_REQUEST -> authHandler.handleRegister(socket, packet, addr, port);
                case LOGOUT_REQUEST   -> {
                    authHandler.handleLogout(socket, packet, addr, port);
                    if (packet.sessionToken != null)
                        gameHandler.removePlayer(packet.sessionToken, socket);
                }
                case PING             -> gameHandler.handlePing(socket, packet, addr, port);
                default               -> dispatchAuthenticated(packet, addr, port);
            }
        } catch (Exception e) {
            log.error("Dispatch error from {}:{} — {}", addr.getHostAddress(), port, e.getMessage(), e);
        }
    }

    private void dispatchAuthenticated(Packet packet, InetAddress addr, int port) throws Exception {
        if (packet.sessionToken == null) {
            log.warn("Unauthenticated {} from {}:{} — ignored", packet.type, addr.getHostAddress(), port);
            return;
        }

        Optional<Session> sessionOpt = authHandler.validateSession(packet.sessionToken);
        if (sessionOpt.isEmpty()) {
            log.warn("Invalid/expired session {} from {}:{}", packet.sessionToken, addr.getHostAddress(), port);
            sendError(socket, "Session invalid or expired. Please log in again.", addr, port);
            return;
        }

        Session session = sessionOpt.get();
        switch (packet.type) {
            case CHARACTER_CREATE_REQUEST -> charHandler.handleCreate(socket, packet, session, addr, port);
            case GAME_JOIN     -> gameHandler.handleJoin(socket, packet, session, addr, port);
            case GAME_LEAVE    -> gameHandler.handleLeave(socket, packet, session);
            case PLAYER_UPDATE -> gameHandler.handlePlayerUpdate(socket, packet, session);
            case ADMIN_USER_LIST_REQUEST,
                 ADMIN_KICK_REQUEST,
                 ADMIN_BAN_REQUEST,
                 ADMIN_SET_ADMIN_REQUEST,
                 ADMIN_RESTART_REQUEST,
                 ADMIN_DEPLOY_REQUEST   -> adminHandler.dispatch(socket, packet, session, addr, port);
            default            -> log.warn("Unhandled packet type: {} from {}", packet.type, session.username());
        }
    }

    // ── Scheduled tasks ──────────────────────────────────────────────────────

    private void periodicCleanup() {
        try {
            int purged = sessionRepo.purgeExpired();
            if (purged > 0) log.info("Purged {} expired sessions.", purged);
        } catch (Exception e) {
            log.error("Periodic cleanup failed: {}", e.getMessage());
        }
    }

    private void evictIdlePlayers() {
        long now = System.currentTimeMillis();
        gameHandler.getPlayers().forEach((token, state) -> {
            if (now - state.lastSeen > PLAYER_TIMEOUT_MS) {
                log.info("Evicting idle player '{}'", state.username);
                gameHandler.removePlayer(token, socket);
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void handleVersionCheck(DatagramSocket socket, Packet packet,
                                     InetAddress addr, int port) throws Exception {
        String clientVersion = packet.payload.has("version")
                ? packet.payload.get("version").asText() : "unknown";

        boolean compatible = GameVersion.VERSION.equals(clientVersion);

        var payload = PacketSerializer.mapper().createObjectNode();
        payload.put("compatible",     compatible);
        payload.put("serverVersion",  GameVersion.VERSION);
        payload.put("clientVersion",  clientVersion);
        if (!compatible) {
            payload.put("message", "Version mismatch! Client: " + clientVersion
                    + " — Server requires: " + GameVersion.VERSION);
        }

        Packet response = new Packet(PacketType.VERSION_RESPONSE, null, payload);
        byte[] data     = PacketSerializer.serialize(response);
        socket.send(new DatagramPacket(data, data.length, addr, port));

        log.info("VERSION_CHECK from {}:{} — client={} compatible={}",
                addr.getHostAddress(), port, clientVersion, compatible);
    }

    private void sendError(DatagramSocket socket, String message,
                           InetAddress addr, int port) throws Exception {
        var payload = PacketSerializer.mapper().createObjectNode();
        payload.put("message", message);
        Packet p    = new Packet(PacketType.ERROR, null, payload);
        byte[] data = PacketSerializer.serialize(p);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
