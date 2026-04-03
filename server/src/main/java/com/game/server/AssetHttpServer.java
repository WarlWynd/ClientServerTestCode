package com.game.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server for serving and managing game assets.
 *
 * Endpoints:
 *   GET  /assets              — list all assets as JSON  (dev token required)
 *   GET  /assets/{type}/{file} — download a file         (public)
 *   POST /assets/{type}/{file} — upload/replace a file   (dev token required)
 *   DELETE /assets/{type}/{file} — delete a file         (dev token required)
 *
 * Auth: pass the UDP session token in the Authorization header.
 *   Authorization: Bearer <token>
 */
public class AssetHttpServer {

    private static final Logger log = LoggerFactory.getLogger(AssetHttpServer.class);

    private static final List<String> ALLOWED_TYPES = List.of("sounds", "graphics");

    private final int         port;
    private final Path        assetsRoot;
    private final AuthHandler authHandler;
    private HttpServer        server;

    public AssetHttpServer(int port, String assetsDir, AuthHandler authHandler) {
        this.port        = port;
        this.assetsRoot  = Path.of(assetsDir).toAbsolutePath();
        this.authHandler = authHandler;
    }

    public void start() throws IOException {
        // Create asset subdirectories if they don't exist
        for (String type : ALLOWED_TYPES) {
            Files.createDirectories(assetsRoot.resolve(type));
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/assets",          this::handle);
        server.createContext("/client-manifest", this::handleClientManifest);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("Asset HTTP server listening on port {}", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
        log.info("Asset HTTP server stopped.");
    }

    // -- Request routing --

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path   = ex.getRequestURI().getPath(); // e.g. /assets or /assets/sounds/jump.wav

            // Strip leading /assets
            String rest = path.replaceFirst("^/assets", "").replaceFirst("^/", "");
            String[] parts = rest.isEmpty() ? new String[0] : rest.split("/", 2);

            if (method.equals("GET") && parts.length == 0) {
                handleList(ex);
            } else if (parts.length == 2) {
                String type     = parts[0];
                String filename = parts[1];
                if (!ALLOWED_TYPES.contains(type) || filename.contains("..") || filename.contains("/")) {
                    respond(ex, 400, "Invalid path");
                    return;
                }
                switch (method) {
                    case "GET"    -> handleDownload(ex, type, filename);
                    case "POST"   -> handleUpload(ex, type, filename);
                    case "DELETE" -> handleDelete(ex, type, filename);
                    default       -> respond(ex, 405, "Method Not Allowed");
                }
            } else {
                respond(ex, 400, "Bad request");
            }
        } catch (Exception e) {
            log.error("HTTP handler error: {}", e.getMessage(), e);
            respond(ex, 500, "Internal Server Error");
        }
    }

    // -- Handlers --

    private void handleList(HttpExchange ex) throws IOException {
        if (!authorised(ex)) { respond(ex, 401, "Unauthorised"); return; }

        StringBuilder json = new StringBuilder("{");
        boolean firstType = true;
        for (String type : ALLOWED_TYPES) {
            if (!firstType) json.append(",");
            firstType = false;
            json.append("\"").append(type).append("\":[");
            Path dir = assetsRoot.resolve(type);
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
            }
            boolean firstFile = true;
            for (Path f : files) {
                if (!firstFile) json.append(",");
                firstFile = false;
                byte[] data = Files.readAllBytes(f);
                json.append("{")
                    .append("\"name\":\"").append(f.getFileName()).append("\",")
                    .append("\"size\":").append(data.length).append(",")
                    .append("\"sha256\":\"").append(sha256(data)).append("\"")
                    .append("}");
            }
            json.append("]");
        }
        json.append("}");

        byte[] body = json.toString().getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void handleClientManifest(HttpExchange ex) throws IOException {
        try {
            // Read optional software version file
            Path versionFile = assetsRoot.resolve("client-version.txt");
            String softwareVersion = Files.exists(versionFile)
                    ? Files.readString(versionFile).trim() : null;

            StringBuilder json = new StringBuilder("{");
            if (softwareVersion != null && !softwareVersion.isEmpty()) {
                json.append("\"softwareVersion\":\"").append(softwareVersion).append("\",");
            }
            json.append("\"files\":[");
            boolean first = true;
            for (String type : ALLOWED_TYPES) {
                Path dir = assetsRoot.resolve(type);
                if (!Files.isDirectory(dir)) continue;
                List<Path> files = new ArrayList<>();
                try (var stream = Files.list(dir)) {
                    stream.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
                for (Path f : files) {
                    if (!first) json.append(",");
                    first = false;
                    byte[] data = Files.readAllBytes(f);
                    json.append("{")
                        .append("\"type\":\"").append(type).append("\",")
                        .append("\"name\":\"").append(f.getFileName()).append("\",")
                        .append("\"size\":").append(data.length).append(",")
                        .append("\"sha256\":\"").append(sha256(data)).append("\"")
                        .append("}");
                }
            }
            json.append("]}");

            byte[] body = json.toString().getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        } catch (Exception e) {
            log.error("Client manifest error: {}", e.getMessage(), e);
            respond(ex, 500, "Internal Server Error");
        }
    }

    private void handleDownload(HttpExchange ex, String type, String filename) throws IOException {
        Path file = assetsRoot.resolve(type).resolve(filename);
        if (!Files.exists(file)) { respond(ex, 404, "Not found"); return; }

        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(filename));
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.getResponseBody().close();
    }

    private void handleUpload(HttpExchange ex, String type, String filename) throws IOException {
        if (!authorised(ex)) { respond(ex, 401, "Unauthorised"); return; }

        Path file = assetsRoot.resolve(type).resolve(filename);
        byte[] data = ex.getRequestBody().readAllBytes();
        Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("ASSET UPLOAD  {}/{} ({} bytes)", type, filename, data.length);
        respond(ex, 200, "{\"success\":true}");
    }

    private void handleDelete(HttpExchange ex, String type, String filename) throws IOException {
        if (!authorised(ex)) { respond(ex, 401, "Unauthorised"); return; }

        Path file = assetsRoot.resolve(type).resolve(filename);
        if (!Files.exists(file)) { respond(ex, 404, "Not found"); return; }
        Files.delete(file);
        log.info("ASSET DELETE  {}/{}", type, filename);
        respond(ex, 200, "{\"success\":true}");
    }

    // -- Helpers --

    private boolean authorised(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        String token = auth.substring(7).trim();
        return authHandler.isDevSession(token) || authHandler.isAdminSession(token);
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] data = body.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        ex.getResponseBody().close();
    }

    private String contentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".wav"))  return "audio/wav";
        if (lower.endsWith(".ogg"))  return "audio/ogg";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        return "application/octet-stream";
    }
}
