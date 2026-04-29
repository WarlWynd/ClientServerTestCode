package com.game.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.CharacterRepository;
import com.game.server.db.InventoryRepository;
import com.game.server.db.ServerSettingsRepository;
import com.game.server.db.UserRepository;
import com.game.server.db.WorldRepository;
import com.game.server.model.PlayerState;
import com.game.server.model.Session;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import com.game.shared.WorldConstants;
import com.game.shared.WorldDef;
import com.game.shared.WorldObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all connected players and handles game-related packets.
 *
 * State is kept in two parallel ConcurrentHashMaps keyed by session token:
 *   players : token → PlayerState  (3D position, score, stats)
 *   clients : token → ClientAddr   (IP + port for broadcasting)
 *
 * Active world: loaded lazily from the worlds table on first GAME_JOIN.
 * Falls back to a flat default world if the table is empty.
 * A WORLD_DEF packet (metadata only — no heightmap) is unicast to each
 * joining client so they know the world dimensions, spawn point, and objects.
 * Full heightmap delivery is handled separately (Phase 5).
 */
public class GameHandler {

    private static final Logger log = LoggerFactory.getLogger(GameHandler.class);

    private record ClientAddr(InetAddress address, int port) {}

    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<String, ClientAddr>  clients = new ConcurrentHashMap<>();

    private final CharacterRepository      charRepo     = new CharacterRepository();
    private final UserRepository           userRepo     = new UserRepository();
    private final InventoryRepository      invRepo      = new InventoryRepository();
    private final ServerSettingsRepository settingsRepo = new ServerSettingsRepository();
    private final WorldRepository          worldRepo    = new WorldRepository();

    private volatile ServerSettingsRepository.Settings currentSettings = null;
    private volatile WorldDef                          activeWorld     = null;

    // ── Settings ──────────────────────────────────────────────────────────────

    public ServerSettingsRepository.Settings getSettings() {
        if (currentSettings == null) currentSettings = settingsRepo.load();
        return currentSettings;
    }

    public void updateSettings(float gravity, float jumpStrength, float runSpeed,
                               boolean allowRememberPassword, boolean showTestNpc,
                               float testNpcX, float testNpcY,
                               String localServerHost, int localServerPort,
                               String externalServerHost, int externalServerPort,
                               boolean allowExternalAdmin, boolean allowExternalDev,
                               int rebootDelaySecs, String rebootMessage,
                               Long startingBoardId,
                               DatagramSocket socket) throws Exception {
        currentSettings = new ServerSettingsRepository.Settings(
                gravity, jumpStrength, runSpeed,
                allowRememberPassword, showTestNpc, testNpcX, testNpcY,
                localServerHost, localServerPort,
                externalServerHost, externalServerPort,
                allowExternalAdmin, allowExternalDev,
                rebootDelaySecs, rebootMessage,
                startingBoardId);
        broadcastSettings(socket);
    }

    public void registerLoginAddress(String token, InetAddress addr, int port) {
        clients.put(token, new ClientAddr(addr, port));
    }

    // ── World ─────────────────────────────────────────────────────────────────

    /** Returns the active world, loading it from DB on first call. Thread-safe. */
    public WorldDef getActiveWorld() {
        if (activeWorld == null) {
            synchronized (this) {
                if (activeWorld == null) {
                    try {
                        WorldDef loaded = worldRepo.findFirst();
                        activeWorld = (loaded != null)
                                ? loaded
                                : WorldDef.createFlat("Default World", WorldConstants.TERRAIN_SIZE);
                        log.info("Active world: '{}' ({}×{} heightmap)",
                                activeWorld.name, activeWorld.heightmapSize, activeWorld.heightmapSize);
                    } catch (Exception e) {
                        log.warn("Failed to load world from DB, using flat default: {}", e.getMessage());
                        activeWorld = WorldDef.createFlat("Default World", WorldConstants.TERRAIN_SIZE);
                    }
                }
            }
        }
        return activeWorld;
    }

    /** Replaces the active world (called by admin tools when a world is selected). */
    public void setActiveWorld(WorldDef world) {
        this.activeWorld = world;
        log.info("Active world changed to '{}'", world.name);
    }

    // ── Packet handlers ──────────────────────────────────────────────────────

    public void handleJoin(DatagramSocket socket, Packet in, Session session,
                           InetAddress addr, int port) throws Exception {
        String charName = charRepo.getCharacterName(session.userId());
        PlayerState ps = new PlayerState(session.userId(), session.username(), charName, addr.getHostAddress());
        charRepo.loadStats(session.userId(), ps);
        players.put(session.token(), ps);
        clients.put(session.token(), new ClientAddr(addr, port));
        log.info("GAME_JOIN  user='{}' players_online={}", session.username(), players.size());
        sendSettings(socket, addr, port);
        sendWorldDef(socket, addr, port);
        broadcastGameState(socket);
    }

    public void handleLeave(DatagramSocket socket, Packet in, Session session) throws Exception {
        players.remove(session.token());
        clients.remove(session.token());
        log.info("GAME_LEAVE user='{}' players_online={}", session.username(), players.size());
        broadcastGameState(socket);
    }

    public void handlePlayerUpdate(DatagramSocket socket, Packet in, Session session) throws Exception {
        PlayerState state = players.get(session.token());
        if (state == null) return;

        float x     = in.payload.has("x")     ? (float) in.payload.get("x").asDouble()     : state.x;
        float y     = in.payload.has("y")     ? (float) in.payload.get("y").asDouble()     : state.y;
        float z     = in.payload.has("z")     ? (float) in.payload.get("z").asDouble()     : state.z;
        int   score = in.payload.has("score") ?          in.payload.get("score").asInt()   : state.score;

        WorldDef world    = getActiveWorld();
        float    halfSize = world.worldSize() / 2f;
        x = Math.max(-halfSize, Math.min(halfSize, x));
        z = Math.max(-halfSize, Math.min(halfSize, z));
        y = Math.max(0f,        Math.min(world.yScale, y));

        state.update(x, y, z, score);
        broadcastGameState(socket);
    }

    public void handlePing(DatagramSocket socket, Packet in,
                           InetAddress addr, int port) throws Exception {
        Packet pong = new Packet(PacketType.PONG, in.sessionToken,
                PacketSerializer.emptyPayload());
        pong.timestamp = in.timestamp;
        byte[] data = PacketSerializer.serialize(pong);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }

    // ── Force logout / eviction ───────────────────────────────────────────────

    public void forceLogoutTokens(List<String> tokens, DatagramSocket socket) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("message", "You have been logged out because your account signed in from another location.");
        byte[] data;
        try {
            data = PacketSerializer.serialize(new Packet(PacketType.FORCE_LOGOUT, null, payload));
        } catch (Exception e) {
            log.error("Failed to serialize FORCE_LOGOUT: {}", e.getMessage());
            return;
        }
        for (String token : tokens) {
            ClientAddr ca = clients.get(token);
            if (ca != null) {
                try {
                    socket.send(new DatagramPacket(data, data.length, ca.address(), ca.port()));
                    log.info("FORCE_LOGOUT sent to token={}", token);
                } catch (Exception e) {
                    log.warn("Could not send FORCE_LOGOUT to {}: {}", token, e.getMessage());
                }
            }
            removePlayer(token, socket);
        }
    }

    public void removePlayer(String sessionToken, DatagramSocket socket) {
        PlayerState p = players.remove(sessionToken);
        clients.remove(sessionToken);
        if (p != null) {
            long userId = userRepo.getUserId(p.username);
            if (userId > 0) invRepo.removeNoLogItems(userId);
            log.info("EVICT  user='{}' (timeout/logout) players_online={}", p.username, players.size());
            try { broadcastGameState(socket); } catch (Exception ignored) {}
        }
    }

    // ── World delivery ────────────────────────────────────────────────────────

    /**
     * Sends a WORLD_DEF packet to a single client containing world metadata:
     * name, dimensions, scales, spawn, texture layers, and placed objects.
     *
     * The heightmap is NOT included — it exceeds UDP limits. Heightmap
     * delivery is handled separately via chunked packets (Phase 5).
     */
    private void sendWorldDef(DatagramSocket socket, InetAddress addr, int port) throws Exception {
        WorldDef world = getActiveWorld();
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("id",            world.id);
        payload.put("name",          world.name);
        payload.put("heightmapSize", world.heightmapSize);
        payload.put("xzScale",       world.xzScale);
        payload.put("yScale",        world.yScale);
        payload.put("spawnX",        world.spawnX);
        payload.put("spawnY",        world.spawnY);
        payload.put("spawnZ",        world.spawnZ);

        ArrayNode layers = payload.putArray("textureLayers");
        if (world.textureLayers != null)
            for (String l : world.textureLayers) layers.add(l);

        ArrayNode objects = payload.putArray("objects");
        if (world.objects != null) {
            for (WorldObject obj : world.objects) {
                ObjectNode o = objects.addObject();
                o.put("instanceId", obj.instanceId);
                o.put("modelId",    obj.modelId);
                o.put("x",         obj.x);
                o.put("y",         obj.y);
                o.put("z",         obj.z);
                o.put("yaw",       obj.yaw);
                o.put("scale",     obj.scale);
            }
        }

        byte[] data = PacketSerializer.serialize(new Packet(PacketType.WORLD_DEF, null, payload));
        socket.send(new DatagramPacket(data, data.length, addr, port));
        log.debug("WORLD_DEF sent to {}:{} — world='{}'", addr.getHostAddress(), port, world.name);
    }

    // ── Settings delivery ─────────────────────────────────────────────────────

    private void sendSettings(DatagramSocket socket, InetAddress addr, int port) throws Exception {
        byte[] data = PacketSerializer.serialize(
                new Packet(PacketType.SERVER_SETTINGS, null, buildSettingsPayload()));
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }

    private void broadcastSettings(DatagramSocket socket) throws Exception {
        byte[] data = PacketSerializer.serialize(
                new Packet(PacketType.SERVER_SETTINGS, null, buildSettingsPayload()));
        for (ClientAddr ci : clients.values()) {
            socket.send(new DatagramPacket(data, data.length, ci.address(), ci.port()));
        }
    }

    private ObjectNode buildSettingsPayload() {
        ServerSettingsRepository.Settings s = getSettings();
        ObjectNode node = PacketSerializer.mapper().createObjectNode();
        node.put("gravity",               s.gravity());
        node.put("jumpStrength",          s.jumpStrength());
        node.put("runSpeed",              s.runSpeed());
        node.put("allowRememberPassword", s.allowRememberPassword());
        node.put("showTestNpc",           s.showTestNpc());
        node.put("testNpcX",              s.testNpcX());
        node.put("testNpcY",              s.testNpcY());
        node.put("localServerHost",       s.localServerHost());
        node.put("localServerPort",       s.localServerPort());
        node.put("externalServerHost",    s.externalServerHost());
        node.put("externalServerPort",    s.externalServerPort());
        node.put("allowExternalAdmin",    s.allowExternalAdmin());
        node.put("allowExternalDev",      s.allowExternalDev());
        node.put("rebootDelaySecs",       s.rebootDelaySecs());
        node.put("rebootMessage",         s.rebootMessage());
        if (s.startingBoardId() != null) node.put("startingBoardId", s.startingBoardId());
        return node;
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    public void broadcastNotice(DatagramSocket socket, String message) throws Exception {
        broadcastNotice(socket, message, 0);
    }

    public void broadcastNotice(DatagramSocket socket, String message, int countdown) throws Exception {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("message", message);
        if (countdown > 0) payload.put("countdown", countdown);
        byte[] data = PacketSerializer.serialize(new Packet(PacketType.SERVER_NOTICE, null, payload));
        for (ClientAddr ci : clients.values()) {
            socket.send(new DatagramPacket(data, data.length, ci.address(), ci.port()));
        }
        log.info("SERVER_NOTICE broadcast to {} client(s): {}", clients.size(), message);
    }

    private void broadcastGameState(DatagramSocket socket) throws Exception {
        byte[] data = PacketSerializer.serialize(
                new Packet(PacketType.GAME_STATE, null, buildSnapshot()));
        for (ClientAddr ci : clients.values()) {
            socket.send(new DatagramPacket(data, data.length, ci.address(), ci.port()));
        }
    }

    private ObjectNode buildSnapshot() {
        ObjectNode root       = PacketSerializer.mapper().createObjectNode();
        ArrayNode  playersArr = root.putArray("players");

        for (Map.Entry<String, PlayerState> e : players.entrySet()) {
            PlayerState p    = e.getValue();
            ObjectNode  node = playersArr.addObject();
            node.put("sessionToken",  e.getKey());
            node.put("userId",        p.userId);
            node.put("username",      p.username);
            node.put("characterName", p.characterName);
            node.put("x",             p.x);
            node.put("y",             p.y);
            node.put("z",             p.z);
            node.put("score",         p.score);
        }

        root.put("playerCount", players.size());
        root.put("timestamp",   System.currentTimeMillis());
        return root;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Map<String, PlayerState> getPlayers() { return players; }

    public Optional<String> kickByUsername(String username, DatagramSocket socket) {
        String token = players.entrySet().stream()
                .filter(e -> e.getValue().username.equals(username))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (token == null) return Optional.empty();
        removePlayer(token, socket);
        return Optional.of(username);
    }
}
