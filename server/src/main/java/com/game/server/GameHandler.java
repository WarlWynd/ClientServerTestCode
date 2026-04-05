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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all connected players and handles game-related packets.
 *
 * State is kept in two parallel ConcurrentHashMaps keyed by session token:
 *   - players : token → PlayerState  (position, score, etc.)
 *   - clients  : token → ClientAddr  (IP + port, for broadcasting)
 *
 * After every mutation a full GAME_STATE snapshot is broadcast to all clients.
 * For a larger game you'd switch to delta-compression and fixed-rate ticks, but
 * this is clean and correct for a prototype.
 */
public class GameHandler {

    private static final Logger log = LoggerFactory.getLogger(GameHandler.class);

    private record ClientAddr(InetAddress address, int port) {}

    /** session token → live player state */
    private final Map<String, PlayerState> players  = new ConcurrentHashMap<>();
    /** session token → client network address */
    private final Map<String, ClientAddr>  clients  = new ConcurrentHashMap<>();

    private final CharacterRepository charRepo = new CharacterRepository();
    private final UserRepository      userRepo = new UserRepository();

    // ── Packet handlers ──────────────────────────────────────────────────────

    public void handleJoin(DatagramSocket socket, Packet in, Session session,
                           InetAddress addr, int port) throws Exception {
        String charName = charRepo.getCharacterName(session.userId());
        players.put(session.token(), new PlayerState(session.userId(), session.username(), charName, addr.getHostAddress()));
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
        if (state == null) return; // player not in game yet — ignore

        float x     = in.payload.has("x")     ? (float) in.payload.get("x").asDouble()     : state.x;
        float y     = in.payload.has("y")     ? (float) in.payload.get("y").asDouble()     : state.y;
        int   score = in.payload.has("score") ?          in.payload.get("score").asInt()   : state.score;

        // Clamp to world bounds (x: 0–3200, y: 0–2360 with y=0 at floor, y=2360 at sky)
        x = Math.max(0f, Math.min(3200f, x));
        y = Math.max(0f, Math.min(2360f, y));

        state.update(x, y, score);

        // Update client address in case of NAT rebinding
        clients.put(session.token(), new ClientAddr(
                clients.get(session.token()).address(), // keep existing — or update if you prefer
                clients.get(session.token()).port()));

        broadcastGameState(socket);
    }

    public void handlePing(DatagramSocket socket, Packet in,
                           InetAddress addr, int port) throws Exception {
        Packet pong = new Packet(PacketType.PONG, in.sessionToken,
                PacketSerializer.emptyPayload());
        pong.timestamp = in.timestamp; // echo client's send time so RTT = now - timestamp
        byte[] data = PacketSerializer.serialize(pong);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }

    // ── Called externally on timeout / logout ────────────────────────────────

    public void removePlayer(String sessionToken, DatagramSocket socket) {
        PlayerState p = players.remove(sessionToken);
        clients.remove(sessionToken);
        if (p != null) {
            log.info("EVICT  user='{}' (timeout/logout) players_online={}", p.username, players.size());
            try { broadcastGameState(socket); } catch (Exception ignored) {}
        }
    }

    // ── Broadcast ────────────────────────────────────────────────────────────

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
        ObjectNode root      = PacketSerializer.mapper().createObjectNode();
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
            node.put("score",         p.score);
        }

        root.put("playerCount", players.size());
        root.put("timestamp",   System.currentTimeMillis());
        return root;
    }

    /** Removes a player by username. Returns the username if found, empty otherwise. */
    public Optional<String> kickByUsername(String username, DatagramSocket socket) {
        String token = players.entrySet().stream()
                .filter(e -> e.getValue().username.equals(username))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (token == null) return Optional.empty();
        removePlayer(token, socket);
        return Optional.of(username);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public Map<String, PlayerState> getPlayers() { return players; }
}
