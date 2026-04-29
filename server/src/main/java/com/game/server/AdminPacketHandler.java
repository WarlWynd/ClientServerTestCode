package com.game.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.BoardRepository;
import com.game.server.db.CharacterRepository;
import com.game.server.db.ServerSettingsRepository;
import com.game.server.db.UserRepository;
import com.game.server.db.WorldRepository;
import com.game.shared.WorldConstants;
import com.game.shared.WorldDef;
import com.game.server.model.PlayerState;
import com.game.server.model.Session;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all ADMIN_* packets. Every handler verifies the session is admin
 * before doing anything.
 */
public class AdminPacketHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminPacketHandler.class);

    private static final int FLOATS_PER_CHUNK = 12_000;

    private final AuthHandler              authHandler;
    private final GameHandler              gameHandler;
    private final UserRepository           userRepo     = new UserRepository();
    private final CharacterRepository      charRepo     = new CharacterRepository();
    private final ServerSettingsRepository settingsRepo = new ServerSettingsRepository();
    private final BoardRepository          boardRepo    = new BoardRepository();
    private final WorldRepository          worldRepo    = new WorldRepository();

    // token → in-progress push (index → chunk floats)
    private final Map<String, PushState> pushStates = new ConcurrentHashMap<>();

    private static final class PushState {
        final long worldId;
        final Map<Integer, float[]> chunks = new ConcurrentHashMap<>();
        PushState(long worldId) { this.worldId = worldId; }
    }

    public AdminPacketHandler(AuthHandler authHandler, GameHandler gameHandler) {
        this.authHandler = authHandler;
        this.gameHandler = gameHandler;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    public void dispatch(DatagramSocket socket, Packet packet, Session session,
                         InetAddress addr, int port) throws Exception {
        if (!authHandler.isAdminSession(packet.sessionToken)) {
            sendResponse(socket, denied(), packetTypeFor(packet.type), addr, port);
            log.warn("ADMIN rejected — non-admin user '{}' sent {}", session.username(), packet.type);
            return;
        }

        switch (packet.type) {
            case ADMIN_CONNECT_REQUEST    -> handleAdminConnect(socket, session, addr, port);
            case ADMIN_USER_LIST_REQUEST  -> handleUserList(socket, session, addr, port);
            case ADMIN_KICK_REQUEST       -> handleKick(socket, packet, session, addr, port);
            case ADMIN_BAN_REQUEST        -> handleBan(socket, packet, session, addr, port);
            case ADMIN_SET_ADMIN_REQUEST  -> handleSetAdmin(socket, packet, session, addr, port);
            case ADMIN_SET_DEV_REQUEST    -> handleSetDev(socket, packet, session, addr, port);
            case ADMIN_RESTART_REQUEST      -> handleRestart(socket, packet, session, addr, port);
            case ADMIN_DEPLOY_REQUEST       -> handleDeploy(socket, packet, session, addr, port);
            case ADMIN_SAVE_SETTINGS_REQUEST -> handleSaveSettings(socket, packet, session, addr, port);
            case ADMIN_GET_BOARDS_REQUEST    -> handleGetBoards(socket, session, addr, port);
            case ADMIN_WORLD_LIST_REQUEST    -> handleWorldList(socket, session, addr, port);
            case ADMIN_WORLD_NEW_REQUEST     -> handleWorldNew(socket, packet, session, addr, port);
            case ADMIN_WORLD_DELETE_REQUEST  -> handleWorldDelete(socket, packet, session, addr, port);
            case ADMIN_WORLD_PULL_REQUEST    -> handleWorldPull(socket, packet, session, addr, port);
            case ADMIN_WORLD_PUSH_CHUNK      -> handleWorldPushChunk(socket, packet, session, addr, port);
            case ADMIN_WORLD_PUSH_DONE       -> handleWorldPushDone(socket, packet, session, addr, port);
            default -> log.warn("Unhandled admin packet type: {}", packet.type);
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void handleAdminConnect(DatagramSocket socket, Session session,
                                    InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        out.put("success", true);
        out.put("username", session.username());
        sendResponse(socket, out, PacketType.ADMIN_CONNECT_RESPONSE, addr, port);
        log.info("ADMIN_CONNECT ok  user='{}'  from {}:{}", session.username(), addr.getHostAddress(), port);
    }

    private void handleUserList(DatagramSocket socket, Session session,
                                InetAddress addr, int port) throws Exception {
        Map<String, PlayerState> players = gameHandler.getPlayers();

        ObjectNode out      = PacketSerializer.mapper().createObjectNode();
        ArrayNode  arr      = out.putArray("players");

        for (PlayerState p : players.values()) {
            ObjectNode node = arr.addObject();
            node.put("username",      p.username);
            node.put("email",         userRepo.getEmail(p.username));
            String charName = charRepo.getCharacterName(userRepo.getUserId(p.username));
            node.put("characterName", charName != null ? charName : "—");
            node.put("ip",           p.ip);
            node.put("joinedAt",     p.joinedAt);
            node.put("x",            p.x);
            node.put("y",            p.y);
            node.put("score",        p.score);
            node.put("isAdmin",       userRepo.isAdmin(p.username));
            node.put("isGraphicsDev", userRepo.isGraphicsDev(p.username));
            node.put("isBoardDev",    userRepo.isBoardDev(p.username));
            node.put("isAudioDev",    userRepo.isAudioDev(p.username));
        }

        out.put("success", true);
        sendResponse(socket, out, PacketType.ADMIN_USER_LIST_RESPONSE, addr, port);
        log.debug("ADMIN_USER_LIST sent {} players to '{}'", players.size(), session.username());
    }

    private void handleKick(DatagramSocket socket, Packet in, Session session,
                            InetAddress addr, int port) throws Exception {
        String target = in.payload.get("username").asText();
        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        gameHandler.kickByUsername(target, socket).ifPresentOrElse(
            kicked -> {
                out.put("success",  true);
                out.put("username", kicked);
                log.info("ADMIN_KICK  '{}' kicked by '{}'", kicked, session.username());
            },
            () -> {
                out.put("success", false);
                out.put("message", "Player '" + target + "' not found.");
            }
        );
        sendResponse(socket, out, PacketType.ADMIN_KICK_RESPONSE, addr, port);
    }

    private void handleBan(DatagramSocket socket, Packet in, Session session,
                           InetAddress addr, int port) throws Exception {
        String  target = in.payload.get("username").asText();
        boolean ban    = !in.payload.has("ban") || in.payload.get("ban").asBoolean();
        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        boolean updated = userRepo.setBanned(target, ban);
        if (updated) {
            out.put("success",  true);
            out.put("username", target);
            if (ban) gameHandler.kickByUsername(target, socket);
            log.info("ADMIN_BAN  '{}' ban={} by '{}'", target, ban, session.username());
        } else {
            out.put("success", false);
            out.put("message", "User '" + target + "' not found.");
        }
        sendResponse(socket, out, PacketType.ADMIN_BAN_RESPONSE, addr, port);
    }

    private void handleSetAdmin(DatagramSocket socket, Packet in, Session session,
                                InetAddress addr, int port) throws Exception {
        String  target  = in.payload.get("username").asText();
        boolean isAdmin = in.payload.has("isAdmin") && in.payload.get("isAdmin").asBoolean();
        ObjectNode out  = PacketSerializer.mapper().createObjectNode();

        boolean updated = userRepo.setAdmin(target, isAdmin);
        if (updated) {
            out.put("success",  true);
            out.put("username", target);
            out.put("isAdmin",  isAdmin);
            log.info("ADMIN_SET_ADMIN  '{}' isAdmin={} by '{}'", target, isAdmin, session.username());
        } else {
            out.put("success", false);
            out.put("message", "User '" + target + "' not found.");
        }
        sendResponse(socket, out, PacketType.ADMIN_SET_ADMIN_RESPONSE, addr, port);
    }

    private void handleSetDev(DatagramSocket socket, Packet in, Session session,
                              InetAddress addr, int port) throws Exception {
        String target = in.payload.get("username").asText();
        String role   = in.payload.has("role") ? in.payload.get("role").asText("") : "";
        boolean grant = in.payload.has("grant") && in.payload.get("grant").asBoolean();
        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        boolean updated = switch (role) {
            case "graphicsDev" -> userRepo.setGraphicsDev(target, grant);
            case "boardDev"    -> userRepo.setBoardDev(target, grant);
            case "audioDev"    -> userRepo.setAudioDev(target, grant);
            default            -> false;
        };
        if (updated) {
            out.put("success",  true);
            out.put("username", target);
            out.put("role",     role);
            out.put("grant",    grant);
            log.info("ADMIN_SET_DEV  '{}' role={} grant={} by '{}'", target, role, grant, session.username());
        } else {
            out.put("success", false);
            out.put("message", "User '" + target + "' not found or unknown role '" + role + "'.");
        }
        sendResponse(socket, out, PacketType.ADMIN_SET_DEV_RESPONSE, addr, port);
    }

    private void handleGetBoards(DatagramSocket socket, Session session,
                                 InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        try {
            java.util.List<BoardRepository.BoardRecord> boards = boardRepo.findAll();
            ArrayNode arr = out.putArray("boards");
            for (BoardRepository.BoardRecord b : boards) {
                ObjectNode n = arr.addObject();
                n.put("id",   b.id());
                n.put("name", b.name());
            }
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage());
            log.warn("ADMIN_GET_BOARDS failed for '{}': {}", session.username(), e.getMessage());
        }
        sendResponse(socket, out, PacketType.ADMIN_GET_BOARDS_RESPONSE, addr, port);
    }

    private void handleSaveSettings(DatagramSocket socket, Packet in, Session session,
                                    InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        try {
            float   gravity               = (float) in.payload.get("gravity").asDouble();
            float   jumpStrength          = (float) in.payload.get("jumpStrength").asDouble();
            float   runSpeed              = (float) in.payload.get("runSpeed").asDouble();
            boolean allowRememberPassword = in.payload.has("allowRememberPassword")
                                            && in.payload.get("allowRememberPassword").asBoolean();
            boolean showTestNpc           = !in.payload.has("showTestNpc")
                                            || in.payload.get("showTestNpc").asBoolean();
            float   testNpcX              = in.payload.has("testNpcX")
                                            ? (float) in.payload.get("testNpcX").asDouble() : 1200f;
            float   testNpcY              = in.payload.has("testNpcY")
                                            ? (float) in.payload.get("testNpcY").asDouble() : 0f;
            String  localServerHost       = in.payload.has("localServerHost")
                                            ? in.payload.get("localServerHost").asText("localhost") : "localhost";
            int     localServerPort       = in.payload.has("localServerPort")
                                            ? in.payload.get("localServerPort").asInt(9876) : 9876;
            String  externalServerHost    = in.payload.has("externalServerHost")
                                            ? in.payload.get("externalServerHost").asText("") : "";
            int     externalServerPort    = in.payload.has("externalServerPort")
                                            ? in.payload.get("externalServerPort").asInt(9876) : 9876;
            boolean allowExternalAdmin    = in.payload.has("allowExternalAdmin")
                                            && in.payload.get("allowExternalAdmin").asBoolean();
            boolean allowExternalDev      = in.payload.has("allowExternalDev")
                                            && in.payload.get("allowExternalDev").asBoolean();
            int     rebootDelaySecs       = in.payload.has("rebootDelaySecs")
                                            ? in.payload.get("rebootDelaySecs").asInt(60) : 60;
            String  rebootMessage         = in.payload.has("rebootMessage")
                                            ? in.payload.get("rebootMessage").asText("") : "";
            Long    startingBoardId       = in.payload.has("startingBoardId")
                                            && !in.payload.get("startingBoardId").isNull()
                                            && in.payload.get("startingBoardId").asLong(0) > 0
                                            ? in.payload.get("startingBoardId").asLong() : null;

            boolean saved = settingsRepo.save(gravity, jumpStrength, runSpeed,
                    allowRememberPassword, showTestNpc, testNpcX, testNpcY,
                    localServerHost, localServerPort,
                    externalServerHost, externalServerPort,
                    allowExternalAdmin, allowExternalDev,
                    rebootDelaySecs, rebootMessage,
                    startingBoardId,
                    session.username());
            if (saved) {
                gameHandler.updateSettings(gravity, jumpStrength, runSpeed,
                        allowRememberPassword, showTestNpc, testNpcX, testNpcY,
                        localServerHost, localServerPort,
                        externalServerHost, externalServerPort,
                        allowExternalAdmin, allowExternalDev,
                        rebootDelaySecs, rebootMessage,
                        startingBoardId,
                        socket);
                out.put("success",              true);
                out.put("gravity",              gravity);
                out.put("jumpStrength",         jumpStrength);
                out.put("runSpeed",             runSpeed);
                out.put("allowRememberPassword",allowRememberPassword);
                out.put("showTestNpc",          showTestNpc);
                out.put("testNpcX",             testNpcX);
                out.put("testNpcY",             testNpcY);
                out.put("localServerHost",      localServerHost);
                out.put("localServerPort",      localServerPort);
                out.put("externalServerHost",   externalServerHost);
                out.put("externalServerPort",   externalServerPort);
                out.put("allowExternalAdmin",   allowExternalAdmin);
                out.put("allowExternalDev",     allowExternalDev);
                out.put("rebootDelaySecs",      rebootDelaySecs);
                out.put("rebootMessage",        rebootMessage);
                log.info("ADMIN_SAVE_SETTINGS by '{}'", session.username());
            } else {
                out.put("success", false);
                out.put("message", "Failed to save settings to database.");
            }
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "Invalid settings payload: " + e.getMessage());
            log.warn("ADMIN_SAVE_SETTINGS bad payload from '{}': {}", session.username(), e.getMessage());
        }
        sendResponse(socket, out, PacketType.ADMIN_SAVE_SETTINGS_RESPONSE, addr, port);
    }

    // ── World handlers ────────────────────────────────────────────────────────

    private void handleWorldList(DatagramSocket socket, Session session,
                                 InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        try {
            ArrayNode arr = out.putArray("worlds");
            for (WorldRepository.WorldSummary s : worldRepo.findAll()) {
                ObjectNode n = arr.addObject();
                n.put("id",           s.id());
                n.put("name",         s.name());
                n.put("heightmapSize",s.heightmapSize());
                n.put("xzScale",      s.xzScale());
                n.put("yScale",       s.yScale());
            }
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage());
            log.warn("ADMIN_WORLD_LIST failed for '{}': {}", session.username(), e.getMessage());
        }
        sendResponse(socket, out, PacketType.ADMIN_WORLD_LIST_RESPONSE, addr, port);
    }

    private void handleWorldNew(DatagramSocket socket, Packet in, Session session,
                                InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        try {
            String name    = in.payload.has("name")    ? in.payload.get("name").asText("New World") : "New World";
            int    size    = in.payload.has("size")    ? in.payload.get("size").asInt(WorldConstants.TERRAIN_SIZE) : WorldConstants.TERRAIN_SIZE;
            float  xzScale = in.payload.has("xzScale") ? (float)in.payload.get("xzScale").asDouble(WorldConstants.TERRAIN_XZ_SCALE) : WorldConstants.TERRAIN_XZ_SCALE;
            float  yScale  = in.payload.has("yScale")  ? (float)in.payload.get("yScale").asDouble(WorldConstants.TERRAIN_Y_SCALE)  : WorldConstants.TERRAIN_Y_SCALE;

            WorldDef w = WorldDef.createFlat(name, size);
            w.xzScale = xzScale;
            w.yScale  = yScale;
            long id = worldRepo.save(w, session.userId());

            out.put("success",      true);
            out.put("id",           id);
            out.put("name",         w.name);
            out.put("heightmapSize",w.heightmapSize);
            out.put("xzScale",      w.xzScale);
            out.put("yScale",       w.yScale);
            log.info("ADMIN_WORLD_NEW '{}' (size={}) by '{}'", name, size, session.username());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage());
            log.warn("ADMIN_WORLD_NEW failed for '{}': {}", session.username(), e.getMessage());
        }
        sendResponse(socket, out, PacketType.ADMIN_WORLD_NEW_RESPONSE, addr, port);
    }

    private void handleWorldDelete(DatagramSocket socket, Packet in, Session session,
                                   InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        try {
            long id = in.payload.get("id").asLong();
            // Admins may delete any world — no owner check
            boolean deleted = worldRepo.deleteAny(id);
            out.put("success", deleted);
            out.put("id",      id);
            if (!deleted) out.put("message", "World " + id + " not found.");
            log.info("ADMIN_WORLD_DELETE id={} by '{}'", id, session.username());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage());
            log.warn("ADMIN_WORLD_DELETE failed for '{}': {}", session.username(), e.getMessage());
        }
        sendResponse(socket, out, PacketType.ADMIN_WORLD_DELETE_RESPONSE, addr, port);
    }

    private void handleWorldPull(DatagramSocket socket, Packet in, Session session,
                                 InetAddress addr, int port) throws Exception {
        long id = in.payload.get("id").asLong();
        Thread.ofVirtual().start(() -> {
            try {
                WorldDef w = worldRepo.findById(id);
                if (w == null) {
                    ObjectNode err = PacketSerializer.mapper().createObjectNode();
                    err.put("success", false);
                    err.put("message", "World " + id + " not found.");
                    sendResponse(socket, err, PacketType.ADMIN_WORLD_PULL_CHUNK, addr, port);
                    return;
                }
                float[] hm     = w.heightmap;
                int     total  = (int) Math.ceil((double) hm.length / FLOATS_PER_CHUNK);
                for (int i = 0; i < total; i++) {
                    int from = i * FLOATS_PER_CHUNK;
                    int to   = Math.min(from + FLOATS_PER_CHUNK, hm.length);
                    float[] slice = Arrays.copyOfRange(hm, from, to);

                    ByteBuffer buf = ByteBuffer.allocate(slice.length * 4).order(ByteOrder.BIG_ENDIAN);
                    for (float f : slice) buf.putFloat(f);
                    String b64 = Base64.getEncoder().encodeToString(buf.array());

                    ObjectNode chunk = PacketSerializer.mapper().createObjectNode();
                    chunk.put("id",    id);
                    chunk.put("index", i);
                    chunk.put("total", total);
                    chunk.put("data",  b64);
                    if (i == 0) {
                        chunk.put("name",         w.name);
                        chunk.put("heightmapSize", w.heightmapSize);
                        chunk.put("xzScale",       w.xzScale);
                        chunk.put("yScale",        w.yScale);
                        chunk.put("spawnX",        w.spawnX);
                        chunk.put("spawnY",        w.spawnY);
                        chunk.put("spawnZ",        w.spawnZ);
                    }
                    sendResponse(socket, chunk, PacketType.ADMIN_WORLD_PULL_CHUNK, addr, port);
                }
                log.debug("ADMIN_WORLD_PULL streamed {} chunks for world id={}", total, id);
            } catch (Exception e) {
                log.warn("ADMIN_WORLD_PULL error id={}: {}", id, e.getMessage());
            }
        });
    }

    private void handleWorldPushChunk(DatagramSocket socket, Packet in, Session session,
                                      InetAddress addr, int port) {
        try {
            long   worldId = in.payload.get("id").asLong();
            int    index   = in.payload.get("index").asInt();
            String b64     = in.payload.get("data").asText();

            byte[]  raw    = Base64.getDecoder().decode(b64);
            float[] floats = new float[raw.length / 4];
            ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(floats);

            pushStates.computeIfAbsent(session.token(), k -> new PushState(worldId))
                      .chunks.put(index, floats);
        } catch (Exception e) {
            log.warn("ADMIN_WORLD_PUSH_CHUNK error from '{}': {}", session.username(), e.getMessage());
        }
    }

    private void handleWorldPushDone(DatagramSocket socket, Packet in, Session session,
                                     InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        try {
            long   worldId    = in.payload.get("id").asLong();
            int    total      = in.payload.get("totalChunks").asInt();
            PushState state   = pushStates.remove(session.token());

            if (state == null || state.chunks.size() < total) {
                int got = state == null ? 0 : state.chunks.size();
                out.put("success", false);
                out.put("message", "Incomplete push: expected " + total + " chunks, got " + got);
                sendResponse(socket, out, PacketType.ADMIN_WORLD_PUSH_RESPONSE, addr, port);
                return;
            }

            // Reassemble heightmap
            int totalFloats = state.chunks.values().stream().mapToInt(a -> a.length).sum();
            float[] hm = new float[totalFloats];
            int pos = 0;
            for (int i = 0; i < total; i++) {
                float[] chunk = state.chunks.get(i);
                System.arraycopy(chunk, 0, hm, pos, chunk.length);
                pos += chunk.length;
            }

            // Build WorldDef from push metadata
            WorldDef w = worldRepo.findById(worldId);
            if (w == null) {
                out.put("success", false);
                out.put("message", "World " + worldId + " not found.");
                sendResponse(socket, out, PacketType.ADMIN_WORLD_PUSH_RESPONSE, addr, port);
                return;
            }
            if (in.payload.has("name"))   w.name   = in.payload.get("name").asText(w.name);
            if (in.payload.has("spawnX")) w.spawnX = (float) in.payload.get("spawnX").asDouble();
            if (in.payload.has("spawnY")) w.spawnY = (float) in.payload.get("spawnY").asDouble();
            if (in.payload.has("spawnZ")) w.spawnZ = (float) in.payload.get("spawnZ").asDouble();
            w.heightmap = hm;

            worldRepo.updateAny(worldId, w);
            gameHandler.setActiveWorld(w);

            out.put("success", true);
            out.put("id",      worldId);
            out.put("name",    w.name);
            log.info("ADMIN_WORLD_PUSH saved world id={} '{}' by '{}'", worldId, w.name, session.username());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage());
            log.warn("ADMIN_WORLD_PUSH_DONE failed for '{}': {}", session.username(), e.getMessage());
        }
        sendResponse(socket, out, PacketType.ADMIN_WORLD_PUSH_RESPONSE, addr, port);
    }

    private static final int    DEFAULT_SHUTDOWN_DELAY   = 60;
    private static final String DEFAULT_SHUTDOWN_MESSAGE = "The server will reboot in %d seconds.";
    private static final int    REMINDER_INTERVAL_SECS   = 5;

    private void handleRestart(DatagramSocket socket, Packet packet, Session session,
                               InetAddress addr, int port) throws Exception {
        int    delay   = getDelay(packet);
        String message = getMessage(packet, delay);

        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        out.put("success", true);
        out.put("delay", delay);
        sendResponse(socket, out, PacketType.ADMIN_RESTART_RESPONSE, addr, port);
        log.info("*** RESTART command received from '{}' — sending shutdown notice, restarting in {}s",
                session.username(), delay);
        Thread.ofVirtual().start(() -> {
            try {
                runShutdownCountdown(socket, message, delay);
            } catch (Exception ignored) {}
            log.info("*** Restarting now (exit 42)");
            System.exit(42);
        });
    }

    private void handleDeploy(DatagramSocket socket, Packet packet, Session session,
                              InetAddress addr, int port) throws Exception {
        int    delay   = getDelay(packet);
        String message = getMessage(packet, delay);

        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        out.put("success", true);
        out.put("delay", delay);
        sendResponse(socket, out, PacketType.ADMIN_DEPLOY_RESPONSE, addr, port);
        log.info("*** DEPLOY command received from '{}' — sending shutdown notice, deploying in {}s",
                session.username(), delay);
        Thread.ofVirtual().start(() -> {
            try {
                runShutdownCountdown(socket, message, delay);
            } catch (Exception ignored) {}
            log.info("*** Deploying now (exit 43)");
            System.exit(43);
        });
    }

    private void runShutdownCountdown(DatagramSocket socket, String initialMessage, int totalSeconds)
            throws Exception {
        int remaining = totalSeconds;

        // Initial broadcast with the (possibly custom) message
        gameHandler.broadcastNotice(socket, initialMessage, remaining);
        log.info("*** Shutdown notice broadcast: \"{}\" — {}s until shutdown", initialMessage, remaining);

        while (remaining > REMINDER_INTERVAL_SECS) {
            Thread.sleep(REMINDER_INTERVAL_SECS * 1000L);
            remaining -= REMINDER_INTERVAL_SECS;
            String reminder = String.format(DEFAULT_SHUTDOWN_MESSAGE, remaining);
            gameHandler.broadcastNotice(socket, reminder, remaining);
            log.info("*** Reboot reminder sent: {}s remaining", remaining);
        }

        // Sleep for the final remainder
        Thread.sleep(remaining * 1000L);
    }

    private static int getDelay(Packet packet) {
        if (packet.payload != null && packet.payload.has("delay")) {
            int d = packet.payload.get("delay").asInt(DEFAULT_SHUTDOWN_DELAY);
            return d > 0 ? d : DEFAULT_SHUTDOWN_DELAY;
        }
        return DEFAULT_SHUTDOWN_DELAY;
    }

    private static String getMessage(Packet packet, int delay) {
        if (packet.payload != null && packet.payload.has("message")) {
            String m = packet.payload.get("message").asText("").trim();
            if (!m.isEmpty()) return m;
        }
        return String.format(DEFAULT_SHUTDOWN_MESSAGE, delay);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode denied() {
        ObjectNode n = PacketSerializer.mapper().createObjectNode();
        n.put("success", false);
        n.put("message", "Not authorized.");
        return n;
    }

    private PacketType packetTypeFor(PacketType request) {
        return switch (request) {
            case ADMIN_CONNECT_REQUEST   -> PacketType.ADMIN_CONNECT_RESPONSE;
            case ADMIN_USER_LIST_REQUEST -> PacketType.ADMIN_USER_LIST_RESPONSE;
            case ADMIN_KICK_REQUEST      -> PacketType.ADMIN_KICK_RESPONSE;
            case ADMIN_BAN_REQUEST       -> PacketType.ADMIN_BAN_RESPONSE;
            case ADMIN_SET_ADMIN_REQUEST -> PacketType.ADMIN_SET_ADMIN_RESPONSE;
            case ADMIN_SET_DEV_REQUEST   -> PacketType.ADMIN_SET_DEV_RESPONSE;
            case ADMIN_RESTART_REQUEST        -> PacketType.ADMIN_RESTART_RESPONSE;
            case ADMIN_DEPLOY_REQUEST         -> PacketType.ADMIN_DEPLOY_RESPONSE;
            case ADMIN_SAVE_SETTINGS_REQUEST  -> PacketType.ADMIN_SAVE_SETTINGS_RESPONSE;
            case ADMIN_GET_BOARDS_REQUEST     -> PacketType.ADMIN_GET_BOARDS_RESPONSE;
            case ADMIN_WORLD_LIST_REQUEST     -> PacketType.ADMIN_WORLD_LIST_RESPONSE;
            case ADMIN_WORLD_NEW_REQUEST      -> PacketType.ADMIN_WORLD_NEW_RESPONSE;
            case ADMIN_WORLD_DELETE_REQUEST   -> PacketType.ADMIN_WORLD_DELETE_RESPONSE;
            case ADMIN_WORLD_PULL_REQUEST     -> PacketType.ADMIN_WORLD_PULL_CHUNK;
            case ADMIN_WORLD_PUSH_DONE        -> PacketType.ADMIN_WORLD_PUSH_RESPONSE;
            default                           -> PacketType.ERROR;
        };
    }

    private void sendResponse(DatagramSocket socket, ObjectNode payload,
                              PacketType type, InetAddress addr, int port) throws Exception {
        byte[] data = PacketSerializer.serialize(new Packet(type, null, payload));
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
