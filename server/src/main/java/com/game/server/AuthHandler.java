package com.game.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all authentication-related UDP packets:
 * LOGIN_REQUEST, REGISTER_REQUEST, LOGOUT_REQUEST.
 */
public class AuthHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    private final String            serverVersion;
    private final int               httpPort;
    private final String            externalIp;
    private final UserRepository    userRepo    = new UserRepository();
    private final SessionRepository sessionRepo = new SessionRepository();

    /** In-memory set of session tokens that belong to admin users. */
    private final Set<String> adminSessions = ConcurrentHashMap.newKeySet();
    /** In-memory set of session tokens that belong to developer users. */
    private final Set<String> devSessions   = ConcurrentHashMap.newKeySet();

    public AuthHandler(String serverVersion, int httpPort) {
        this.serverVersion = serverVersion;
        this.httpPort      = httpPort;
        this.externalIp    = fetchExternalIp();
    }

    private static String fetchExternalIp() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            String ip = http.send(req, HttpResponse.BodyHandlers.ofString()).body().trim();
            log.info("External IP: {}", ip);
            return ip;
        } catch (Exception e) {
            log.warn("Could not resolve external IP: {}", e.getMessage());
            return null;
        }
    }

    /** Returns the external IP for LAN/loopback addresses, otherwise the actual source IP. */
    private String effectiveIp(InetAddress addr) {
        if (externalIp != null
                && (addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress())) {
            return externalIp;
        }
        return addr.getHostAddress();
    }

    // -- Packet handlers --

    public void handleLogin(DatagramSocket socket, Packet in,
                            InetAddress addr, int port) throws Exception {
        String username      = in.payload.get("username").asText();
        String password      = in.payload.get("password").asText();
        String clientVersion = in.payload.has("clientVersion")
                ? in.payload.get("clientVersion").asText() : "";

        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (!serverVersion.equals(clientVersion)) {
            out.put("success", false);
            out.put("message", "Client version " + clientVersion
                    + " does not match server version " + serverVersion
                    + ". Please update your client.");
            log.warn("LOGIN  version mismatch client='{}' server='{}' from {}:{}",
                    clientVersion, serverVersion, addr.getHostAddress(), port);
            send(socket, PacketType.LOGIN_RESPONSE, null, out, addr, port);
            return;
        }

        if (userRepo.isBanned(username)) {
            out.put("success", false);
            out.put("message", "This account has been banned.");
            log.warn("LOGIN  banned user='{}' from {}:{}", username, addr.getHostAddress(), port);
            send(socket, PacketType.LOGIN_RESPONSE, null, out, addr, port);
            return;
        }

        Optional<User> user = userRepo.authenticate(username, password);

        if (user.isPresent()) {
            Session session = sessionRepo.create(user.get().id(), user.get().username(), effectiveIp(addr));
            out.put("success",      true);
            out.put("sessionToken", session.token());
            out.put("username",     session.username());
            out.put("isAdmin",      user.get().isAdmin());
            out.put("isDeveloper",  user.get().isDeveloper());
            out.put("assetPort",    httpPort);
            if (user.get().isAdmin()) {
                adminSessions.add(session.token());
                log.info("ADMIN LOGIN  user='{}' from {}:{}", username, addr.getHostAddress(), port);
            } else if (user.get().isDeveloper()) {
                devSessions.add(session.token());
                log.info("DEV LOGIN  user='{}' from {}:{}", username, addr.getHostAddress(), port);
            } else {
                log.info("LOGIN  ok  user='{}' from {}:{}", username, addr.getHostAddress(), port);
            }
        } else {
            out.put("success", false);
            out.put("message", "Invalid username or password.");
            log.warn("LOGIN  fail user='{}' from {}:{}", username, addr.getHostAddress(), port);
        }

        send(socket, PacketType.LOGIN_RESPONSE, null, out, addr, port);
    }

    public void handleRegister(DatagramSocket socket, Packet in,
                               InetAddress addr, int port) throws Exception {
        String username = in.payload.get("username").asText().trim();
        String password = in.payload.get("password").asText();

        ObjectNode out = PacketSerializer.mapper().createObjectNode();

        if (username.length() < 3 || username.length() > 50) {
            out.put("success", false);
            out.put("message", "Username must be 3-50 characters.");
        } else if (!username.matches("[A-Za-z0-9_]+")) {
            out.put("success", false);
            out.put("message", "Username may only contain letters, digits, and underscores.");
        } else if (password.length() < 6) {
            out.put("success", false);
            out.put("message", "Password must be at least 6 characters.");
        } else if (userRepo.register(username, password)) {
            out.put("success", true);
            out.put("message", "Account created! You can now log in.");
            log.info("REGISTER ok  user='{}'", username);
        } else {
            out.put("success", false);
            out.put("message", "Username is already taken.");
        }

        send(socket, PacketType.REGISTER_RESPONSE, null, out, addr, port);
    }

    public void handleLogout(DatagramSocket socket, Packet in,
                             InetAddress addr, int port) throws Exception {
        if (in.sessionToken != null) {
            adminSessions.remove(in.sessionToken);
            devSessions.remove(in.sessionToken);
            sessionRepo.invalidate(in.sessionToken);
            log.info("LOGOUT token={}", in.sessionToken);
        }
        ObjectNode out = PacketSerializer.mapper().createObjectNode();
        out.put("success", true);
        send(socket, PacketType.LOGOUT_RESPONSE, null, out, addr, port);
    }

    // -- Session validation --

    public Optional<Session> validateSession(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return sessionRepo.validate(token);
    }

    /** Returns true if the given token belongs to an admin user. */
    public boolean isAdminSession(String token) {
        return token != null && adminSessions.contains(token);
    }

    /** Returns true if the given token belongs to a developer user. */
    public boolean isDevSession(String token) {
        return token != null && devSessions.contains(token);
    }

    // -- Helpers --

    private void send(DatagramSocket socket, PacketType type, String token,
                      ObjectNode payload, InetAddress addr, int port) throws Exception {
        Packet p    = new Packet(type, token, payload);
        byte[] data = PacketSerializer.serialize(p);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
