package com.game.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.CharacterRepository;
import com.game.server.db.SessionRepository;
import com.game.server.db.UserRepository;
import com.game.server.model.Session;
import com.game.server.model.User;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Handles all authentication-related UDP packets:
 * LOGIN_REQUEST, REGISTER_REQUEST, LOGOUT_REQUEST.
 */
public class AuthHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    final         UserRepository    userRepo    = new UserRepository();
    private final SessionRepository sessionRepo = new SessionRepository();
    private final CharacterRepository charRepo  = new CharacterRepository();

    // ── Packet handlers ──────────────────────────────────────────────────────

    public void handleLogin(DatagramSocket socket, Packet in,
                            InetAddress addr, int port) throws Exception {
        String email    = in.payload.get("email").asText();
        String password = in.payload.get("password").asText();

        Optional<User> user = userRepo.authenticate(email, password);
        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (user.isPresent()) {
            Session session = sessionRepo.create(user.get().id(), user.get().username());
            out.put("success",      true);
            out.put("sessionToken", session.token());
            out.put("username",     session.username());
            out.put("isAdmin",       user.get().isAdmin());
            out.put("isGraphicsDev", user.get().isGraphicsDev());
            String charName = charRepo.getCharacterName(user.get().id());
            out.put("hasCharacter",   charName != null);
            if (charName != null) out.put("characterName", charName);
            log.info("LOGIN  ok  user='{}' admin={} graphicsDev={} from {}:{}",
                    user.get().username(), user.get().isAdmin(), user.get().isGraphicsDev(),
                    addr.getHostAddress(), port);
        } else {
            out.put("success", false);
            out.put("message", "Invalid username or password.");
            log.warn("LOGIN  fail email='{}' from {}:{}", email, addr.getHostAddress(), port);
        }

        send(socket, PacketType.LOGIN_RESPONSE, null, out, addr, port);
    }

    public void handleRegister(DatagramSocket socket, Packet in,
                               InetAddress addr, int port) throws Exception {
        String username = in.payload.get("username").asText().trim();
        String password = in.payload.get("password").asText();
        String email    = in.payload.has("email") ? in.payload.get("email").asText().trim() : "";

        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (username.length() < 3 || username.length() > 50) {
            out.put("success", false);
            out.put("message", "Username must be 3–50 characters.");
        } else if (!username.matches("[A-Za-z0-9_]+")) {
            out.put("success", false);
            out.put("message", "Username may only contain letters, digits, and underscores.");
        } else if (password.length() < 6) {
            out.put("success", false);
            out.put("message", "Password must be at least 6 characters.");
        } else if (email.isEmpty() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            out.put("success", false);
            out.put("message", "A valid email address is required.");
        } else if (userRepo.register(username, password, email)) {
            out.put("success", true);
            out.put("message", "Account created! You can now log in.");
            log.info("REGISTER ok  user='{}' email='{}'", username, email);
        } else {
            out.put("success", false);
            out.put("message", "Username or email address is already taken.");
        }

        send(socket, PacketType.REGISTER_RESPONSE, null, out, addr, port);
    }

    public void handleLogout(DatagramSocket socket, Packet in,
                             InetAddress addr, int port) throws Exception {
        if (in.sessionToken != null) {
            sessionRepo.invalidate(in.sessionToken);
            log.info("LOGOUT token={}", in.sessionToken);
        }
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        out.put("success", true);
        send(socket, PacketType.LOGOUT_RESPONSE, null, out, addr, port);
    }

    // ── Session validation (used by UDPServer for game packets) ─────────────

    public Optional<Session> validateSession(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return sessionRepo.validate(token);
    }

    public boolean isAdminSession(String token) {
        return validateSession(token)
                .map(s -> userRepo.isAdmin(s.username()))
                .orElse(false);
    }

    public boolean isDevSession(String token) {
        return isAdminSession(token);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void send(DatagramSocket socket, PacketType type, String token,
                      ObjectNode payload, InetAddress addr, int port) throws Exception {
        Packet p    = new Packet(type, token, payload);
        byte[] data = PacketSerializer.serialize(p);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
