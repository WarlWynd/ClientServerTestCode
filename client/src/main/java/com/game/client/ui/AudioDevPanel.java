package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.client.ClientSyncClient;
import com.game.client.AudioManager;
import com.game.client.SessionStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.sound.sampled.*;
import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

/**
 * Admin/Dev tab for managing server-side audio assets.
 *
 * Shows expected audio files and their status on the server.
 * Admins can generate WAV stubs (uploaded directly to the server),
 * upload arbitrary files via a file chooser, delete files, or preview
 * anything in the local cache.
 *
 * Clients receive updated files on next restart via ClientSyncClient.
 */
public class AudioDevPanel {

    // Files the game expects — all map to assets/sounds/ on the server
    record Expected(String filename, boolean canGenerate) {}

    private static final List<Expected> EXPECTED = List.of(
        new Expected("menu_theme.mp3",   false),
        new Expected("game_theme.mp3",   false),
        new Expected("login.wav",        true),
        new Expected("player_join.wav",  true),
        new Expected("player_leave.wav", true),
        new Expected("move.wav",         true),
        new Expected("score.wav",        true)
    );

    private static final Map<String, int[]> STUB_TONES = Map.of(
        "login.wav",        new int[]{523, 300},
        "player_join.wav",  new int[]{660, 250},
        "player_leave.wav", new int[]{392, 250},
        "move.wav",         new int[]{600,  60},
        "score.wav",        new int[]{880, 400}
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Label      statusLabel;
    private VBox       rows;
    private MediaPlayer currentPlayer;

    /** Server manifest: filename → entry node (has name, size, sha256) */
    private final Map<String, JsonNode> serverFiles = new LinkedHashMap<>();

    public VBox buildView() {
        Button refreshBtn     = new Button("Refresh");
        Button openCacheBtn   = new Button("Open Cache Folder");
        refreshBtn.setOnAction(e -> fetchManifest());
        openCacheBtn.setOnAction(e -> openCacheFolder());
        styleToolBtn(refreshBtn);
        styleToolBtn(openCacheBtn);

        HBox toolbar = new HBox(8, refreshBtn, openCacheBtn);
        toolbar.setPadding(new Insets(10));
        toolbar.setStyle("-fx-background-color: #0f0f1e;");

        rows = new VBox(4);
        rows.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #1a1a2e; -fx-background: #1a1a2e;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        statusLabel = new Label("Fetching manifest…");
        statusLabel.setStyle("-fx-text-fill: #80c080; -fx-font-size: 11;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setStyle("-fx-background-color: #0a0a18; -fx-border-color: #2a2a4e; -fx-border-width: 1 0 0 0;");

        VBox root = new VBox(toolbar, scroll, statusBar);
        root.setStyle("-fx-background-color: #1a1a2e;");

        fetchManifest(); // initial load
        return root;
    }

    // ── Manifest fetch ────────────────────────────────────────────────────────

    private void fetchManifest() {
        setStatus("Fetching manifest…");
        rows.getChildren().clear();
        String assetUrl = SessionStore.getAssetUrl();
        String token    = SessionStore.getToken();
        if (assetUrl == null) { setStatus("Asset server URL not available."); return; }

        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                HttpClient http = HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(assetUrl + "/assets"))
                        .header("Authorization", "Bearer " + token)
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                Map<String, JsonNode> fetched = new LinkedHashMap<>();
                if (resp.statusCode() == 200) {
                    JsonNode root   = MAPPER.readTree(resp.body());
                    JsonNode sounds = root.get("sounds");
                    if (sounds != null && sounds.isArray()) {
                        for (JsonNode e : sounds) {
                            // Support both {name,size,sha256} objects and legacy bare strings
                            String name = e.isTextual() ? e.asText() : e.get("name").asText();
                            fetched.put(name, e);
                        }
                    }
                }

                Platform.runLater(() -> {
                    serverFiles.clear();
                    serverFiles.putAll(fetched);
                    rebuildRows();
                    setStatus("Ready — " + serverFiles.size() + " file(s) on server.");
                });
            } catch (java.net.ConnectException e) {
                Platform.runLater(() -> setStatus("Cannot reach asset server — is the server running?"));
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("Manifest error: " + e));
            }
        });
    }

    // ── UI rebuild ────────────────────────────────────────────────────────────

    private void rebuildRows() {
        rows.getChildren().clear();

        addSectionHeader("MUSIC");
        for (Expected exp : EXPECTED) {
            if (exp.filename().endsWith(".mp3")) rows.getChildren().add(buildRow(exp));
        }

        addSectionHeader("SFX");
        for (Expected exp : EXPECTED) {
            if (exp.filename().endsWith(".wav")) rows.getChildren().add(buildRow(exp));
        }

        // Any extra files on server not in our expected list
        Set<String> expectedNames = new HashSet<>();
        EXPECTED.forEach(e -> expectedNames.add(e.filename()));
        boolean hasExtra = serverFiles.keySet().stream().anyMatch(n -> !expectedNames.contains(n));
        if (hasExtra) {
            addSectionHeader("OTHER (on server)");
            for (String name : serverFiles.keySet()) {
                if (!expectedNames.contains(name)) {
                    rows.getChildren().add(buildExtraRow(name, serverFiles.get(name)));
                }
            }
        }
    }

    private void addSectionHeader(String label) {
        Label hdr = new Label(label);
        hdr.setStyle("-fx-text-fill: #a0a0e0; -fx-font-weight: bold; -fx-font-size: 12;");
        boolean first = rows.getChildren().isEmpty();
        VBox.setMargin(hdr, new Insets(first ? 0 : 14, 0, 4, 0));
        rows.getChildren().add(hdr);
    }

    private HBox buildRow(Expected exp) {
        JsonNode entry  = serverFiles.get(exp.filename());
        boolean  onServer = entry != null;

        Label icon = new Label(onServer ? "✓" : "✗");
        icon.setStyle("-fx-text-fill: " + (onServer ? "#7ed321" : "#e94560") + "; -fx-font-weight: bold;");
        icon.setMinWidth(16);

        Label name = new Label(exp.filename());
        name.setStyle("-fx-text-fill: #e0e0e0; -fx-font-family: monospace;");
        name.setMinWidth(180);

        String sizeStr = (onServer && entry.isObject() && entry.has("size"))
                ? formatSize(entry.get("size").asLong()) : (onServer ? "on server" : "NOT ON SERVER");
        Label info = new Label(sizeStr);
        info.setStyle("-fx-text-fill: " + (onServer ? "#707090" : "#b06060") + "; -fx-font-size: 11;");
        info.setMinWidth(90);

        HBox row = new HBox(8, icon, name, info);
        row.setAlignment(Pos.CENTER_LEFT);

        if (onServer) {
            Button previewBtn = new Button("▶ Preview");
            styleRowBtn(previewBtn, "#0f3460");
            previewBtn.setOnAction(e -> previewFile(exp.filename()));
            Button deleteBtn = new Button("Delete");
            styleRowBtn(deleteBtn, "#4a1010");
            deleteBtn.setOnAction(e -> deleteFromServer(exp.filename()));
            row.getChildren().addAll(previewBtn, deleteBtn);
        } else {
            if (exp.canGenerate()) {
                Button genBtn = new Button("Generate & Upload");
                styleRowBtn(genBtn, "#1a3a00");
                genBtn.setOnAction(e -> generateAndUpload(exp.filename()));
                row.getChildren().add(genBtn);
            }
            Button uploadBtn = new Button("Choose File…");
            styleRowBtn(uploadBtn, "#2a2a00");
            uploadBtn.setOnAction(e -> chooseAndUpload(exp.filename(), row.getScene().getWindow()));
            row.getChildren().add(uploadBtn);
        }

        row.setPadding(new Insets(5, 8, 5, 8));
        row.setStyle("-fx-background-color: #12122a; -fx-background-radius: 4;");
        return row;
    }

    private HBox buildExtraRow(String name, JsonNode entry) {
        Label icon = new Label("·");
        icon.setStyle("-fx-text-fill: #707090;");
        icon.setMinWidth(16);

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-text-fill: #c0c0c0; -fx-font-family: monospace;");
        nameLabel.setMinWidth(180);

        Label info = new Label(entry.isObject() && entry.has("size") ? formatSize(entry.get("size").asLong()) : "on server");
        info.setStyle("-fx-text-fill: #707090; -fx-font-size: 11;");
        info.setMinWidth(90);

        Button deleteBtn = new Button("Delete");
        styleRowBtn(deleteBtn, "#4a1010");
        deleteBtn.setOnAction(e -> deleteFromServer(name));

        HBox row = new HBox(8, icon, nameLabel, info, deleteBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 8, 5, 8));
        row.setStyle("-fx-background-color: #10101e; -fx-background-radius: 4;");
        return row;
    }

    // ── Server operations ─────────────────────────────────────────────────────

    private void generateAndUpload(String filename) {
        int[] tone      = STUB_TONES.getOrDefault(filename, new int[]{440, 200});
        int   freq      = tone[0];
        int   durationMs = tone[1];
        int   sampleRate = 44100;
        int   frames    = sampleRate * durationMs / 1000;

        try {
            byte[] pcm = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                double t   = (double) i / sampleRate;
                double env = 1.0 - (double) i / frames;
                short  s   = (short)(Math.sin(2 * Math.PI * freq * t) * env * Short.MAX_VALUE * 0.6);
                pcm[i * 2]     = (byte)(s & 0xFF);
                pcm[i * 2 + 1] = (byte)(s >> 8);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
            try (AudioInputStream ais = new AudioInputStream(
                        new ByteArrayInputStream(pcm), fmt, frames)) {
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
            }

            uploadToServer(filename, baos.toByteArray(), "audio/wav");
        } catch (Exception ex) {
            setStatus("Generation failed: " + ex.getMessage());
        }
    }

    private void chooseAndUpload(String suggestedName, Window owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose audio file to upload as " + suggestedName);
        String ext = suggestedName.substring(suggestedName.lastIndexOf('.') + 1).toLowerCase();
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(ext.toUpperCase() + " files", "*." + ext));
        var chosen = fc.showOpenDialog(owner);
        if (chosen == null) return;
        try {
            byte[] data = Files.readAllBytes(chosen.toPath());
            uploadToServer(suggestedName, data, ext.equals("mp3") ? "audio/mpeg" : "audio/wav");
        } catch (Exception ex) {
            setStatus("Read error: " + ex.getMessage());
        }
    }

    private void uploadToServer(String filename, byte[] data, String contentType) {
        setStatus("Uploading " + filename + "…");
        String assetUrl = SessionStore.getAssetUrl();
        String token    = SessionStore.getToken();
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                HttpClient http = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(assetUrl + "/assets/sounds/" + filename))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    AudioManager.invalidate(filename);
                    Platform.runLater(() -> { setStatus("Uploaded: " + filename); fetchManifest(); });
                } else {
                    Platform.runLater(() -> setStatus("Upload failed: HTTP " + resp.statusCode()));
                }
            } catch (Exception ex) {
                Platform.runLater(() -> setStatus("Upload error: " + ex.getMessage()));
            }
        });
    }

    private void deleteFromServer(String filename) {
        setStatus("Deleting " + filename + "…");
        String assetUrl = SessionStore.getAssetUrl();
        String token    = SessionStore.getToken();
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                HttpClient http = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(assetUrl + "/assets/sounds/" + filename))
                        .header("Authorization", "Bearer " + token)
                        .DELETE().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Platform.runLater(() -> { setStatus("Deleted: " + filename); fetchManifest(); });
                } else {
                    Platform.runLater(() -> setStatus("Delete failed: HTTP " + resp.statusCode()));
                }
            } catch (Exception ex) {
                Platform.runLater(() -> setStatus("Delete error: " + ex.getMessage()));
            }
        });
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    private void previewFile(String filename) {
        stopCurrent();
        Path cached = ClientSyncClient.BASE_DIR.resolve("sounds").resolve(filename);

        if (filename.endsWith(".wav")) {
            Path src = Files.exists(cached) ? cached : null;
            if (src == null) { setStatus("Not in local cache — restart the client to sync."); return; }
            Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    AudioInputStream ais = AudioSystem.getAudioInputStream(src.toFile());
                    Clip clip = AudioSystem.getClip();
                    clip.open(ais);
                    clip.start();
                    setStatus("Playing: " + filename);
                    clip.addLineListener(ev -> {
                        if (ev.getType() == LineEvent.Type.STOP) { clip.close(); setStatus("Ready"); }
                    });
                } catch (Exception ex) { setStatus("Playback error: " + ex.getMessage()); }
            });
        } else {
            if (!Files.exists(cached)) { setStatus("Not in local cache — restart the client to sync."); return; }
            try {
                Media media = new Media(cached.toUri().toString());
                currentPlayer = new MediaPlayer(media);
                currentPlayer.setOnEndOfMedia(() -> { setStatus("Ready"); stopCurrent(); });
                currentPlayer.play();
                setStatus("Playing: " + filename);
            } catch (Exception ex) { setStatus("Playback error: " + ex.getMessage()); }
        }
    }

    private void stopCurrent() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer.dispose();
            currentPlayer = null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void openCacheFolder() {
        try {
            Files.createDirectories(ClientSyncClient.BASE_DIR.resolve("sounds"));
            Desktop.getDesktop().open(ClientSyncClient.BASE_DIR.resolve("sounds").toFile());
        } catch (Exception ex) {
            setStatus("Cannot open folder: " + ex.getMessage());
        }
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return                          String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static void styleToolBtn(Button b) {
        b.setStyle("-fx-background-color: #2a2a4e; -fx-text-fill: #e0e0e0; -fx-background-radius: 4; -fx-cursor: hand;");
    }

    private static void styleRowBtn(Button b, String bg) {
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: #e0e0e0; -fx-font-size: 11; -fx-background-radius: 3; -fx-cursor: hand;");
    }
}
