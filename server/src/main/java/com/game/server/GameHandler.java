package com.game.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.SessionRepository;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all connected players and handles game-related packets.
 *
 * State is kept in two parallel ConcurrentHashMaps keyed by session token:
 *   - players : token -> PlayerState  (position, score, etc.)
 *   - clients : token -> ClientAddr   (IP + port, for broadcasting)
 *
 * After every mutation a full GAME_STATE snapshot is broadcast to all clients.
 */
public class GameHandler {

    private static final Logger log = LoggerFactory.getLogger(GameHandler.class);

    private final SessionRepository sessionRepo = new SessionRepository();

    private record ClientAddr(InetAddress address, int port) {}

    /** session token -> live player state */
    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    /** session token -> client network address */
    private final Map<String, ClientAddr>  clients = new ConcurrentHashMap<>();

    // -- Packet handlers --

    public void handleJoin(DatagramSocket socket, Packet in, Session session,
                           InetAddress addr, int port) throws Exception {
        players.put(session.token(), new PlayerState(session.userId(), session.username()));
        clients.put(session.token(), new ClientAddr(addr, port));
        log.info("GAME_JOIN  user='{}' players_online={}", session.username(), players.size());
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

        float x     = in.payload.has("x")     ? (float) in.payload.get("x").asDouble()   : state.x;
        float y     = in.payload.has("y")     ? (float) in.payload.get("y").asDouble()   : state.y;
        int   score = in.payload.has("score") ?         in.payload.get("score").asInt()  : state.score;

        x = Math.max(0f, Math.min(800f, x));
        y = Math.max(0f, Math.min(600f, y));

        state.update(x, y, score);

        clients.put(session.token(), new ClientAddr(
                clients.get(session.token()).address(),
                clients.get(session.token()).port()));

        broadcastGameState(socket);
    }

    public void handlePing(DatagramSocket socket, Packet in,
                           InetAddress addr, int port) throws Exception {
        Packet pong = new Packet(PacketType.PONG, in.sessionToken,
                PacketSerializer.emptyPayload());
        byte[] data = PacketSerializer.serialize(pong);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }

    // -- Called externally on timeout / logout --

    public void removePlayer(String sessionToken, DatagramSocket socket) {
        PlayerState p = players.remove(sessionToken);
        clients.remove(sessionToken);
        if (p != null) {
            log.info("EVICT  user='{}' (timeout/logout) players_online={}", p.username, players.size());
            try { broadcastGameState(socket); } catch (Exception ignored) {}
        }
    }

    // -- Admin: kick by username --

    public Optional<String> kickByUsername(String username, DatagramSocket socket) {
        for (Map.Entry<String, PlayerState> entry : players.entrySet()) {
            if (entry.getValue().username.equals(username)) {
                String token = entry.getKey();
                removePlayer(token, socket);
                sessionRepo.invalidate(token);
                log.info("KICK  user='{}' by admin", username);
                return Optional.of(token);
            }
        }
        log.warn("KICK  user='{}' not found", username);
        return Optional.empty();
    }

    // -- Broadcast --

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

        for (PlayerState p : players.values()) {
            ObjectNode node = playersArr.addObject();
            node.put("userId",   p.userId);
            node.put("username", p.username);
            node.put("x",        p.x);
            node.put("y",        p.y);
            node.put("score",    p.score);
        }

        root.put("playerCount", players.size());
        root.put("timestamp",   System.currentTimeMillis());
        return root;
    }

    // -- Accessors --

    public Map<String, PlayerState> getPlayers() { return players; }
}
