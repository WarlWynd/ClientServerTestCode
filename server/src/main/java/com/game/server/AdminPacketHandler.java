package com.game.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.model.PlayerState;
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

/**
 * Handles admin-specific UDP packets.
 * Every request is validated against the admin session set before processing.
 */
public class AdminPacketHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminPacketHandler.class);

    private final AuthHandler authHandler;
    private final GameHandler gameHandler;

    public AdminPacketHandler(AuthHandler authHandler, GameHandler gameHandler) {
        this.authHandler = authHandler;
        this.gameHandler = gameHandler;
    }

    // -- ADMIN_USER_LIST_REQUEST --

    public void handleUserListRequest(DatagramSocket socket, Packet in,
                                      InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (!authHandler.isAdminSession(in.sessionToken)) {
            out.put("success", false);
            out.put("message", "Unauthorised");
            send(socket, PacketType.ADMIN_USER_LIST_RESPONSE, in.sessionToken, out, addr, port);
            log.warn("ADMIN_USER_LIST rejected — not an admin token from {}:{}", addr.getHostAddress(), port);
            return;
        }

        out.put("success", true);
        ArrayNode playersArr = out.putArray("players");

        for (Map.Entry<String, PlayerState> entry : gameHandler.getPlayers().entrySet()) {
            PlayerState p = entry.getValue();
            ObjectNode  node = playersArr.addObject();
            node.put("username", p.username);
            node.put("joinedAt", p.joinedAt);
            node.put("x",        p.x);
            node.put("y",        p.y);
            node.put("score",    p.score);
        }

        send(socket, PacketType.ADMIN_USER_LIST_RESPONSE, in.sessionToken, out, addr, port);
    }

    // -- ADMIN_KICK_REQUEST --

    public void handleKickRequest(DatagramSocket socket, Packet in,
                                  InetAddress addr, int port) throws Exception {
        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (!authHandler.isAdminSession(in.sessionToken)) {
            out.put("success", false);
            out.put("message", "Unauthorised");
            send(socket, PacketType.ADMIN_KICK_RESPONSE, in.sessionToken, out, addr, port);
            log.warn("ADMIN_KICK rejected — not an admin token from {}:{}", addr.getHostAddress(), port);
            return;
        }

        String username = in.payload.has("username") ? in.payload.get("username").asText() : "";
        if (username.isBlank()) {
            out.put("success", false);
            out.put("message", "No username specified");
            send(socket, PacketType.ADMIN_KICK_RESPONSE, in.sessionToken, out, addr, port);
            return;
        }

        Optional<String> kicked = gameHandler.kickByUsername(username, socket);
        if (kicked.isPresent()) {
            out.put("success",  true);
            out.put("username", username);
            log.info("ADMIN_KICK  user='{}' by admin at {}:{}", username, addr.getHostAddress(), port);
        } else {
            out.put("success", false);
            out.put("message", "Player '" + username + "' not found in game");
        }

        send(socket, PacketType.ADMIN_KICK_RESPONSE, in.sessionToken, out, addr, port);
    }

    // -- Helper --

    private void send(DatagramSocket socket, PacketType type, String token,
                      ObjectNode payload, InetAddress addr, int port) throws Exception {
        Packet p    = new Packet(type, token, payload);
        byte[] data = PacketSerializer.serialize(p);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
