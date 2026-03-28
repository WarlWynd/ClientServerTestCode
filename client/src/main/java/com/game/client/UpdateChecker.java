package com.game.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);
    private static final String RELEASES_API =
            "https://api.github.com/repos/WarlWynd/ClientServerTestCode/releases/latest";
    private static final ObjectMapper mapper = new ObjectMapper();

    public record UpdateInfo(String latestVersion, String downloadUrl) {}

    /**
     * Returns an {@link UpdateInfo} if a newer release exists on GitHub, or {@code null} if
     * the client is already up to date (or the check could not be completed).
     */
    public static UpdateInfo checkForUpdate(String currentVersion) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_API))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MultiplayerGameClient")
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("GitHub API returned {} — skipping update check", resp.statusCode());
                return null;
            }

            JsonNode root = mapper.readTree(resp.body());
            String tagName = root.get("tag_name").asText();
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            if (!isNewer(latestVersion, currentVersion)) {
                log.info("Client is up to date ({})", currentVersion);
                return null;
            }

            for (JsonNode asset : root.get("assets")) {
                if ("MultiplayerGame-windows.zip".equals(asset.get("name").asText())) {
                    log.info("Update available: {} -> {}", currentVersion, latestVersion);
                    return new UpdateInfo(latestVersion, asset.get("browser_download_url").asText());
                }
            }

            log.warn("Update {} found but no Windows asset attached", latestVersion);
            return null;

        } catch (Exception e) {
            log.warn("Update check failed: {} — continuing without update", e.getMessage());
            return null;
        }
    }

    /**
     * Downloads the update zip, extracts it to a temp directory, then launches a batch
     * script that waits for this process to exit before swapping the files and relaunching.
     *
     * @param onProgress callback with values 0.0–1.0 as the download progresses
     * @return {@code true} if the update was started (app should exit), {@code false} if
     *         running outside a jpackage installation (dev mode — update skipped)
     */
    public static boolean applyUpdate(UpdateInfo info, Consumer<Double> onProgress) throws Exception {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null) {
            log.info("Not running as a packaged app — skipping self-update");
            return false;
        }
        Path appDir = Path.of(appPath).getParent();

        Path tempDir = Files.createTempDirectory("multiplayer-update-");
        Path zipFile = tempDir.resolve("update.zip");

        // ── Download ────────────────────────────────────────────────────────────
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(info.downloadUrl()))
                .header("User-Agent", "MultiplayerGameClient")
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1);

        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(zipFile)) {
            byte[] buf = new byte[32_768];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (contentLength > 0) onProgress.accept((double) downloaded / contentLength);
            }
        }
        onProgress.accept(1.0);

        // ── Extract ─────────────────────────────────────────────────────────────
        Path extractDir = tempDir.resolve("extracted");
        unzip(zipFile, extractDir);
        // The zip contains a root "MultiplayerGame/" folder
        Path newAppDir = extractDir.resolve("MultiplayerGame");

        // ── Write updater script ────────────────────────────────────────────────
        // Waits until the exe is no longer locked, copies new files, relaunches.
        Path scriptPath = tempDir.resolve("update.bat");
        String exePath = appDir.resolve("MultiplayerGame.exe").toString();
        String script = """
                @echo off
                :wait
                timeout /t 2 /nobreak >nul
                2>nul (
                    ren "%s" "_upd.tmp" && ren "%s\\_upd.tmp" "MultiplayerGame.exe"
                )
                if errorlevel 1 goto wait
                xcopy /E /Y /I "%s" "%s\\"
                start "" "%s"
                del "%%~f0"
                """.formatted(exePath, appDir, newAppDir, appDir, exePath);
        Files.writeString(scriptPath, script);

        new ProcessBuilder("cmd", "/c", "start", "/min", "", scriptPath.toString()).start();
        log.info("Update script launched — exiting for replacement");
        return true;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static void unzip(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    throw new IOException("Zip path traversal blocked: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** Returns true if {@code latest} is a higher semver than {@code current}. */
    static boolean isNewer(String latest, String current) {
        int[] l = parseVersion(latest);
        int[] c = parseVersion(current);
        for (int i = 0; i < Math.max(l.length, c.length); i++) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { nums[i] = 0; }
        }
        return nums;
    }
}
