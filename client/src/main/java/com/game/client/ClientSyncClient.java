package com.game.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fetches the server's /client-manifest and downloads any files whose
 * SHA-256 differs from the locally cached copy in ~/.game/{type}/.
 *
 * Call sync() from a background thread (e.g. from ClientSyncScreen).
 */
public final class ClientSyncClient {

    private static final Logger log = LoggerFactory.getLogger(ClientSyncClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public  static final Path BASE_DIR = Paths.get(System.getProperty("user.home"), ".game");

    private ClientSyncClient() {}

    public static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public record SyncResult(int checked, int downloaded, int failed, String error, String serverVersion) {
        public boolean hasError()    { return error != null; }
        public boolean allCurrent()  { return downloaded == 0 && failed == 0 && error == null; }
    }

    /**
     * Fetches /client-manifest and syncs all listed files into ~/.game/{type}/.
     *
     * @param assetBaseUrl      base URL of the asset HTTP server, e.g. "http://192.168.1.153:9877"
     * @param progressCallback  called with (filesProcessed, totalFiles) after each file — may be null
     * @param onVersionReceived called with the server's softwareVersion as soon as the manifest is
     *                          parsed, before any downloads begin — may be null
     */
    public static SyncResult sync(String assetBaseUrl,
                                  BiConsumer<Integer, Integer> progressCallback,
                                  Consumer<String> onVersionReceived) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(assetBaseUrl + "/client-manifest"))
                    .GET().build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return new SyncResult(0, 0, 0, null, null); // no manifest = nothing to sync
            }
            if (resp.statusCode() != 200) {
                return new SyncResult(0, 0, 0, "Server returned HTTP " + resp.statusCode(), null);
            }

            JsonNode root           = MAPPER.readTree(resp.body());
            String   serverVersion  = root.has("softwareVersion") ? root.get("softwareVersion").asText() : null;
            JsonNode files          = root.get("files");

            if (onVersionReceived != null && serverVersion != null) {
                onVersionReceived.accept(serverVersion);
            }

            if (files == null || !files.isArray()) {
                return new SyncResult(0, 0, 0, "Invalid manifest format", serverVersion);
            }

            int total      = files.size();
            int downloaded = 0;
            int failed     = 0;
            int idx        = 0;

            for (JsonNode entry : files) {
                String type       = entry.get("type").asText();
                String name       = entry.get("name").asText();
                String serverHash = entry.has("sha256") ? entry.get("sha256").asText() : "";

                Path localDir  = BASE_DIR.resolve(type);
                Files.createDirectories(localDir);
                Path localFile = localDir.resolve(name);

                // Skip if local file matches server SHA-256
                if (!serverHash.isEmpty() && Files.exists(localFile)) {
                    String localHash = sha256(Files.readAllBytes(localFile));
                    if (localHash.equals(serverHash)) {
                        idx++;
                        if (progressCallback != null) progressCallback.accept(idx, total);
                        continue;
                    }
                }

                // Download
                HttpRequest dlReq = HttpRequest.newBuilder()
                        .uri(URI.create(assetBaseUrl + "/assets/" + type + "/" + name))
                        .GET().build();
                HttpResponse<byte[]> dlResp = http.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());

                if (dlResp.statusCode() == 200) {
                    Files.write(localFile, dlResp.body(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    if ("sounds".equals(type)) AudioManager.invalidate(name);
                    downloaded++;
                    log.info("Synced {}/{}", type, name);
                } else {
                    failed++;
                    log.warn("Failed to download {}/{}: HTTP {}", type, name, dlResp.statusCode());
                }

                idx++;
                if (progressCallback != null) progressCallback.accept(idx, total);
            }

            return new SyncResult(total, downloaded, failed, null, serverVersion);

        } catch (java.net.ConnectException e) {
            return new SyncResult(0, 0, 0, "Cannot reach asset server", null);
        } catch (Exception e) {
            log.warn("Client sync failed: {}", e.toString(), e);
            return new SyncResult(0, 0, 0, e.toString(), null);
        }
    }
}
