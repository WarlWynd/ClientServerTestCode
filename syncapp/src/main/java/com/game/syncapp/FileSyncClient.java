package com.game.syncapp;

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

/**
 * Fetches /client-manifest from the asset server and downloads any files
 * whose SHA-256 differs from the locally cached copy in ~/.game/{type}/.
 */
public final class FileSyncClient {

    private static final Logger       log    = LoggerFactory.getLogger(FileSyncClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public  static final Path         BASE_DIR = Paths.get(System.getProperty("user.home"), ".game");

    private FileSyncClient() {}

    public record SyncResult(int checked, int downloaded, int failed, String error) {
        public boolean hasError()   { return error != null; }
        public boolean allCurrent() { return downloaded == 0 && failed == 0 && error == null; }
    }

    /**
     * Syncs all files listed in the server manifest into ~/.game/{type}/.
     *
     * @param assetBaseUrl     base URL of the asset HTTP server
     * @param progressCallback called with (filesProcessed, totalFiles) after each file
     */
    public static SyncResult sync(String assetBaseUrl,
                                  BiConsumer<Integer, Integer> progressCallback) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(assetBaseUrl + "/client-manifest"))
                    .GET().build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 404) {
                return new SyncResult(0, 0, 0, null);
            }
            if (resp.statusCode() != 200) {
                return new SyncResult(0, 0, 0, "Server returned HTTP " + resp.statusCode());
            }

            JsonNode root  = MAPPER.readTree(resp.body());
            JsonNode files = root.get("files");

            if (files == null || !files.isArray()) {
                return new SyncResult(0, 0, 0, "Invalid manifest format");
            }

            int total = files.size(), downloaded = 0, failed = 0, idx = 0;

            for (JsonNode entry : files) {
                String type       = entry.get("type").asText();
                String name       = entry.get("name").asText();
                String serverHash = entry.has("sha256") ? entry.get("sha256").asText() : "";

                Path localDir  = BASE_DIR.resolve(type);
                Files.createDirectories(localDir);
                Path localFile = localDir.resolve(name);

                if (!serverHash.isEmpty() && Files.exists(localFile)) {
                    if (sha256(Files.readAllBytes(localFile)).equals(serverHash)) {
                        if (progressCallback != null) progressCallback.accept(++idx, total);
                        continue;
                    }
                }

                HttpRequest dlReq = HttpRequest.newBuilder()
                        .uri(URI.create(assetBaseUrl + "/assets/" + type + "/" + name))
                        .GET().build();
                HttpResponse<byte[]> dlResp = http.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());

                if (dlResp.statusCode() == 200) {
                    Files.write(localFile, dlResp.body(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    downloaded++;
                    log.info("Synced {}/{}", type, name);
                } else {
                    failed++;
                    log.warn("Failed to download {}/{}: HTTP {}", type, name, dlResp.statusCode());
                }

                if (progressCallback != null) progressCallback.accept(++idx, total);
            }

            return new SyncResult(total, downloaded, failed, null);

        } catch (java.net.ConnectException e) {
            return new SyncResult(0, 0, 0, "Cannot reach asset server at " + assetBaseUrl);
        } catch (Exception e) {
            log.warn("Sync failed: {}", e.toString(), e);
            return new SyncResult(0, 0, 0, e.getMessage());
        }
    }

    static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
