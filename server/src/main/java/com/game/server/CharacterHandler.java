package com.game.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.CharacterRepository;
import com.game.server.model.Session;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class CharacterHandler {

    private static final Logger log = LoggerFactory.getLogger(CharacterHandler.class);

    private final CharacterRepository charRepo = new CharacterRepository();

    public void handleCreate(DatagramSocket socket, Packet in, Session session,
                             InetAddress addr, int port) throws Exception {
        String name = in.payload.has("characterName")
                ? in.payload.get("characterName").asText().trim() : "";

        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (name.isEmpty() || name.length() < 2 || name.length() > 50) {
            out.put("success", false);
            out.put("message", "Character name must be 2–50 characters.");
        } else if (!name.matches("[A-Za-z][A-Za-z0-9 '-]*")) {
            out.put("success", false);
            out.put("message", "Name may only contain letters, numbers, spaces, hyphens, and apostrophes.");
        } else if (charRepo.isBlacklisted(name)) {
            out.put("success", false);
            out.put("message", "That name is not allowed.");
        } else if (charRepo.hasCharacter(session.userId())) {
            out.put("success", false);
            out.put("message", "You already have a character.");
        } else if (!charRepo.isNameAvailable(name)) {
            out.put("success", false);
            out.put("message", "That name is already taken.");
        } else if (charRepo.createCharacter(session.userId(), name)) {
            out.put("success",       true);
            out.put("characterName", name);
            out.put("message",       "Character created!");
            log.info("CHARACTER created name='{}' user='{}'", name, session.username());
        } else {
            out.put("success", false);
            out.put("message", "That name is already taken.");
        }

        Packet response = new Packet(PacketType.CHARACTER_CREATE_RESPONSE, in.sessionToken, out);
        byte[] data     = PacketSerializer.serialize(response);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
