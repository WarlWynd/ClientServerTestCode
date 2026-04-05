package com.game.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.CharacterRepository;
import com.game.server.db.UserRepository;
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
import java.util.Map;

/**
 * Handles all ADMIN_* packets. Every handler verifies the session is admin
 * before doing anything.
 */
public class AdminPacketHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminPacketHandler.class);

    private final AuthHandler         authHandler;
    private final GameHandler         gameHandler;
    private final UserRepository      userRepo  = new UserRepository();
    private final CharacterRepository charRepo  = new CharacterRepository();

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
            case ADMIN_USER_LIST_REQUEST  -> handleUserList(socket, session, addr, port);
            case ADMIN_KICK_REQUEST       -> handleKick(socket, packet, session, addr, port);
            case ADMIN_BAN_REQUEST        -> handleBan(socket, packet, session, addr, port);
            case ADMIN_SET_ADMIN_REQUEST  -> handleSetAdmin(socket, packet, session, addr, port);
            case ADMIN_RESTART_REQUEST    -> handleRestart(socket, packet, session, addr, port);
            case ADMIN_DEPLOY_REQUEST     -> handleDeploy(socket, packet, session, addr, port);
            default -> log.warn("Unhandled admin packet type: {}", packet.type);
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

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
            node.put("isAdmin",      userRepo.isAdmin(p.username));
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
            case ADMIN_USER_LIST_REQUEST -> PacketType.ADMIN_USER_LIST_RESPONSE;
            case ADMIN_KICK_REQUEST      -> PacketType.ADMIN_KICK_RESPONSE;
            case ADMIN_BAN_REQUEST       -> PacketType.ADMIN_BAN_RESPONSE;
            case ADMIN_SET_ADMIN_REQUEST -> PacketType.ADMIN_SET_ADMIN_RESPONSE;
            case ADMIN_RESTART_REQUEST   -> PacketType.ADMIN_RESTART_RESPONSE;
            case ADMIN_DEPLOY_REQUEST    -> PacketType.ADMIN_DEPLOY_RESPONSE;
            default                      -> PacketType.ERROR;
        };
    }

    private void sendResponse(DatagramSocket socket, ObjectNode payload,
                              PacketType type, InetAddress addr, int port) throws Exception {
        byte[] data = PacketSerializer.serialize(new Packet(type, null, payload));
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
