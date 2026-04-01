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
import java.security.MessageDigest;

/**
 * Downloads changed or missing audio assets from the server's asset HTTP endpoint
 * into the local cache directory (~/.game/audio/).
 *
 * Call sync() in a background thread after login. AudioManager reads from this
 * cache dir first so files become available immediately after sync.
 */
public final class AssetSyncClient {

    private static final Logger log = LoggerFactory.getLogger(AssetSyncClient.class);

    public static final Path CACHE_DIR =
            Paths.get(System.getProperty("user.home"), ".game", "audio");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssetSyncClient() {}

    /**
     * Fetches the asset manifest and downloads any sounds whose SHA-256 differs
     * from the locally cached copy. Runs synchronously — call from a background thread.
     */
    public static void sync(String assetBaseUrl, String sessionToken) {
        try {
            Files.createDirectories(CACHE_DIR);
            HttpClient http = HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .build();

            // Fetch manifest
            HttpRequest manifestReq = HttpRequest.newBuilder()
                    .uri(URI.create(assetBaseUrl + "/assets"))
                    .header("Authorization", "Bearer " + sessionToken)
                    .GET().build();

            HttpResponse<String> manifestResp =
                    http.send(manifestReq, HttpResponse.BodyHandlers.ofString());

            if (manifestResp.statusCode() != 200) {
                log.warn("Asset manifest fetch returned HTTP {}", manifestResp.statusCode());
                return;
            }

            JsonNode root   = MAPPER.readTree(manifestResp.body());
            JsonNode sounds = root.get("sounds");
            if (sounds == null || !sounds.isArray()) return;

            int downloaded = 0;
            for (JsonNode entry : sounds) {
                // Support both new format {name,size,sha256} and legacy bare string format
                String name       = entry.isTextual() ? entry.asText() : entry.get("name").asText();
                String serverHash = (!entry.isTextual() && entry.has("sha256")) ? entry.get("sha256").asText() : "";
                Path   local      = CACHE_DIR.resolve(name);

                // Skip if local file matches server checksum
                if (Files.exists(local) && sha256(Files.readAllBytes(local)).equals(serverHash)) {
                    continue;
                }

                HttpRequest dlReq = HttpRequest.newBuilder()
                        .uri(URI.create(assetBaseUrl + "/assets/sounds/" + name))
                        .GET().build();

                HttpResponse<byte[]> dlResp =
                        http.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());

                if (dlResp.statusCode() == 200) {
                    Files.write(local, dlResp.body(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    AudioManager.invalidate(name);
                    log.info("Audio synced: {}", name);
                    downloaded++;
                } else {
                    log.warn("Failed to download {}: HTTP {}", name, dlResp.statusCode());
                }
            }

            if (downloaded > 0) log.info("Asset sync complete — {} file(s) updated.", downloaded);
            else                log.info("Asset sync complete — all files up to date.");

        } catch (java.net.ConnectException e) {
            log.warn("Asset sync skipped — asset server not reachable at {}", assetBaseUrl);
        } catch (Exception e) {
            log.warn("Asset sync failed: {}", e.toString(), e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
