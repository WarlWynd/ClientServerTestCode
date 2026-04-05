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
 * whose SHA-256 differs from the locally cached copy.
 */
public final class FileSyncClient {

    private static final Logger       log    = LoggerFactory.getLogger(FileSyncClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FileSyncClient() {}

    public record SyncResult(int checked, int downloaded, int failed, String error) {
        public boolean hasError()   { return error != null; }
        public boolean allCurrent() { return downloaded == 0 && failed == 0 && error == null; }
    }

    /**
     * Syncs all files listed in the server manifest into installDir/{type}/.
     *
     * @param assetBaseUrl     base URL of the asset HTTP server
     * @param installDir       root directory to download files into
     * @param progressCallback called with (filesProcessed, totalFiles, currentFileName) — may be null
     * @param fileCallback     called with (filename, isNewDownload) after each file — may be null
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    public static SyncResult sync(String assetBaseUrl,
                                  Path installDir,
                                  TriConsumer<Integer, Integer, String> progressCallback,
                                  BiConsumer<String, Boolean> fileCallback) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(assetBaseUrl + "/client-manifest"))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 404) {
                return new SyncResult(0, 0, 0, null); // no manifest = nothing to sync
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
                String displayName = type + "/" + name;

                if (progressCallback != null) progressCallback.accept(idx, total, displayName);

                Path localDir  = installDir.resolve(type);
                Files.createDirectories(localDir);
                Path localFile = localDir.resolve(name);

                // Skip if local SHA-256 matches server
                if (!serverHash.isEmpty() && Files.exists(localFile)) {
                    if (sha256(Files.readAllBytes(localFile)).equals(serverHash)) {
                        if (fileCallback != null)    fileCallback.accept(displayName, false);
                        if (progressCallback != null) progressCallback.accept(++idx, total, null);
                        continue;
                    }
                }

                // Download
                HttpRequest dlReq = HttpRequest.newBuilder()
                        .uri(URI.create(assetBaseUrl + "/assets/" + type + "/" + name))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<byte[]> dlResp = http.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());

                if (dlResp.statusCode() == 200) {
                    Files.write(localFile, dlResp.body(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    downloaded++;
                    log.info("Downloaded {}", displayName);
                    if (fileCallback != null) fileCallback.accept(displayName, true);
                } else {
                    failed++;
                    log.warn("Failed to download {}: HTTP {}", displayName, dlResp.statusCode());
                    if (fileCallback != null) fileCallback.accept("FAILED: " + displayName, false);
                }

                if (progressCallback != null) progressCallback.accept(++idx, total, null);
            }

            return new SyncResult(total, downloaded, failed, null);

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            return new SyncResult(0, 0, 0, "Cannot reach server at " + assetBaseUrl);
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
