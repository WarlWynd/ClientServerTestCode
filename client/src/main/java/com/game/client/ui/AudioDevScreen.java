package com.game.client.ui;

import com.game.client.AudioManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Audio Developer Tab.
 *
 * Features:
 *   - Browse and preview files in the audio/music and audio/sfx resource directories
 *   - Import audio files from anywhere on disk into the correct directory
 *   - Play / pause / stop controls with a seek slider and duration display
 *   - Separate volume sliders for Music and SFX
 *   - Delete files from the resource directories
 *   - Status bar showing current action
 *
 * Audio resource root is resolved relative to the project working directory:
 *   client/src/main/resources/audio/
 */
public class AudioDevScreen {

    // ── Audio resource root (relative to project working dir) ────────────────
    private static final String AUDIO_ROOT =
            "client/src/main/resources/audio";
    private static final String MUSIC_DIR = AUDIO_ROOT + "/music";
    private static final String SFX_DIR   = AUDIO_ROOT + "/sfx";

    private static final String[] SUPPORTED_EXTENSIONS = {
            "*.mp3", "*.wav", "*.ogg", "*.aac", "*.m4a"
    };

    // ── State ────────────────────────────────────────────────────────────────
    private final Stage        stage;
    private final AudioManager audio = AudioManager.getInstance();

    private MediaPlayer  previewPlayer;
    private File         selectedFile;
    private boolean      isPlaying = false;

    // ── UI components ────────────────────────────────────────────────────────
    private ListView<String> musicList;
    private ListView<String> sfxList;
    private Label            nowPlayingLabel;
    private Label            timeLabel;
    private Slider           seekSlider;
    private Slider           musicVolSlider;
    private Slider           sfxVolSlider;
    private Button           playPauseBtn;
    private Button           stopBtn;
    private Label            statusLabel;
    private ToggleGroup      categoryToggle;

    public AudioDevScreen(Stage stage) {
        this.stage = stage;
    }

    // ── Build the tab content ─────────────────────────────────────────────────

    public javafx.scene.Node build() {
        // ── Left panel: file browser ─────────────────────────────────────────
        VBox leftPanel = buildFileBrowser();
        leftPanel.setPrefWidth(280);

        // ── Right panel: player + controls ───────────────────────────────────
        VBox rightPanel = buildPlayerPanel();

        // ── Main layout ───────────────────────────────────────────────────────
        HBox content = new HBox(12, leftPanel, rightPanel);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        content.setPadding(new Insets(12));
        content.setStyle("-fx-background-color: #1a1a2e;");

        // ── Status bar ────────────────────────────────────────────────────────
        statusLabel = new Label("Ready.");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");
        statusLabel.setPadding(new Insets(4, 12, 4, 12));

        VBox root = new VBox(content, statusLabel);
        VBox.setVgrow(content, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1a1a2e;");

        refreshLists();
        return root;
    }

    // ── File Browser ─────────────────────────────────────────────────────────

    private VBox buildFileBrowser() {
        Label title = styledLabel("Audio Files", 14, true);

        // Category toggle
        ToggleButton musicToggle = new ToggleButton("🎵 Music");
        ToggleButton sfxToggle   = new ToggleButton("🔊 SFX");
        categoryToggle = new ToggleGroup();
        musicToggle.setToggleGroup(categoryToggle);
        sfxToggle.setToggleGroup(categoryToggle);
        musicToggle.setSelected(true);
        styleToggleButton(musicToggle);
        styleToggleButton(sfxToggle);

        HBox toggleBar = new HBox(4, musicToggle, sfxToggle);

        categoryToggle.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) refreshLists();
        });

        // Music list
        musicList = new ListView<>();
        styleList(musicList);
        musicList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) previewSelected(musicList, MUSIC_DIR);
            else onFileSelected(musicList, MUSIC_DIR);
        });

        // SFX list
        sfxList = new ListView<>();
        styleList(sfxList);
        sfxList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) previewSelected(sfxList, SFX_DIR);
            else onFileSelected(sfxList, SFX_DIR);
        });

        // Show/hide lists based on toggle
        musicToggle.setOnAction(e -> { musicList.setVisible(true); sfxList.setVisible(false); });
        sfxToggle.setOnAction(e -> { musicList.setVisible(false); sfxList.setVisible(true); });
        sfxList.setVisible(false);

        StackPane listPane = new StackPane(musicList, sfxList);
        VBox.setVgrow(listPane, Priority.ALWAYS);

        // Action buttons
        Button importBtn  = actionButton("⬆ Import", "#0f3460");
        Button previewBtn = actionButton("▶ Preview", "#1a5276");
        Button deleteBtn  = actionButton("🗑 Delete",  "#7b241c");
        Button refreshBtn = actionButton("↻ Refresh", "#1e8449");

        importBtn.setOnAction(e  -> importFile());
        previewBtn.setOnAction(e -> {
            ListView<String> active = activeList();
            String dir = isMusic() ? MUSIC_DIR : SFX_DIR;
            previewSelected(active, dir);
        });
        deleteBtn.setOnAction(e  -> deleteSelected());
        refreshBtn.setOnAction(e -> refreshLists());

        HBox btnRow1 = new HBox(4, importBtn, refreshBtn);
        HBox btnRow2 = new HBox(4, previewBtn, deleteBtn);

        VBox panel = new VBox(8, title, toggleBar, listPane, btnRow1, btnRow2);
        panel.setPadding(new Insets(8));
        panel.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return panel;
    }

    // ── Player Panel ──────────────────────────────────────────────────────────

    private VBox buildPlayerPanel() {
        Label title = styledLabel("Preview Player", 14, true);

        // Now playing
        nowPlayingLabel = new Label("No file selected");
        nowPlayingLabel.setStyle("-fx-text-fill: #e94560; -fx-font-weight: bold;");
        nowPlayingLabel.setWrapText(true);

        // Seek slider
        seekSlider = new Slider(0, 100, 0);
        seekSlider.setStyle("-fx-control-inner-background: #0f3460;");
        seekSlider.setMaxWidth(Double.MAX_VALUE);
        seekSlider.setOnMousePressed(e  -> { if (previewPlayer != null) previewPlayer.pause(); });
        seekSlider.setOnMouseReleased(e -> {
            if (previewPlayer != null) {
                previewPlayer.seek(Duration.seconds(seekSlider.getValue()));
                if (isPlaying) previewPlayer.play();
            }
        });

        timeLabel = new Label("0:00 / 0:00");
        timeLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");

        // Transport controls
        playPauseBtn = actionButton("▶ Play", "#e94560");
        stopBtn      = actionButton("⏹ Stop", "#555577");
        playPauseBtn.setPrefWidth(100);
        stopBtn.setPrefWidth(100);

        playPauseBtn.setOnAction(e -> togglePlayPause());
        stopBtn.setOnAction(e      -> stopPreview());

        HBox transport = new HBox(8, playPauseBtn, stopBtn);
        transport.setAlignment(Pos.CENTER_LEFT);

        // ── Volume controls ───────────────────────────────────────────────────
        Label volTitle = styledLabel("Volume Controls", 13, true);

        Label musicVolLabel = styledLabel("🎵 Music", 12, false);
        musicVolSlider = new Slider(0, 1, audio.getMusicVolume());
        musicVolSlider.setMaxWidth(Double.MAX_VALUE);
        musicVolSlider.valueProperty().addListener((obs, o, n) -> {
            audio.setMusicVolume(n.doubleValue());
            if (previewPlayer != null) previewPlayer.setVolume(n.doubleValue());
        });

        CheckBox musicMuteCheck = new CheckBox("Mute");
        styleCheck(musicMuteCheck);
        musicMuteCheck.setOnAction(e -> audio.setMusicMuted(musicMuteCheck.isSelected()));

        HBox musicVolRow = new HBox(8, musicVolLabel, musicVolSlider, musicMuteCheck);
        musicVolRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(musicVolSlider, Priority.ALWAYS);

        Label sfxVolLabel = styledLabel("🔊 SFX  ", 12, false);
        sfxVolSlider = new Slider(0, 1, audio.getSfxVolume());
        sfxVolSlider.setMaxWidth(Double.MAX_VALUE);
        sfxVolSlider.valueProperty().addListener((obs, o, n) ->
                audio.setSfxVolume(n.doubleValue()));

        CheckBox sfxMuteCheck = new CheckBox("Mute");
        styleCheck(sfxMuteCheck);
        sfxMuteCheck.setOnAction(e -> audio.setSfxMuted(sfxMuteCheck.isSelected()));

        HBox sfxVolRow = new HBox(8, sfxVolLabel, sfxVolSlider, sfxMuteCheck);
        sfxVolRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sfxVolSlider, Priority.ALWAYS);

        // ── Directory info ────────────────────────────────────────────────────
        Label dirTitle = styledLabel("Resource Directories", 13, true);
        Label musicDirLabel = new Label("🎵 " + new File(MUSIC_DIR).getAbsolutePath());
        Label sfxDirLabel   = new Label("🔊 " + new File(SFX_DIR).getAbsolutePath());
        musicDirLabel.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        sfxDirLabel.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");
        musicDirLabel.setWrapText(true);
        sfxDirLabel.setWrapText(true);

        // ── Assemble ──────────────────────────────────────────────────────────
        Separator sep1 = new Separator();
        sep1.setStyle("-fx-background-color: #3a3a6a;");
        Separator sep2 = new Separator();
        sep2.setStyle("-fx-background-color: #3a3a6a;");

        VBox panel = new VBox(10,
                title,
                nowPlayingLabel,
                seekSlider, timeLabel,
                transport,
                sep1,
                volTitle, musicVolRow, sfxVolRow,
                sep2,
                dirTitle, musicDirLabel, sfxDirLabel
        );
        panel.setPadding(new Insets(8));
        panel.setStyle("-fx-background-color: #16213e; -fx-background-radius: 6;");
        return panel;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void importFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Audio File");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Audio Files", SUPPORTED_EXTENSIONS));

        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        // Ask music or sfx
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Import Audio");
        alert.setHeaderText("Where should this file go?");
        alert.setContentText(file.getName());
        ButtonType musicBtn = new ButtonType("🎵 Music");
        ButtonType sfxBtn   = new ButtonType("🔊 SFX");
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(musicBtn, sfxBtn, cancelBtn);
        styleAlert(alert);

        alert.showAndWait().ifPresent(result -> {
            String destDir = result == musicBtn ? MUSIC_DIR : SFX_DIR;
            if (result == cancelBtn) return;
            try {
                Path dest = Paths.get(destDir, file.getName());
                Files.createDirectories(dest.getParent());
                Files.copy(file.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                status("✓ Imported: " + file.getName() + " → " + destDir);
                refreshLists();
            } catch (IOException e) {
                status("✗ Import failed: " + e.getMessage());
            }
        });
    }

    private void previewSelected(ListView<String> list, String dir) {
        String name = list.getSelectionModel().getSelectedItem();
        if (name == null || name.startsWith("#")) return;
        File file = new File(dir, name);
        if (!file.exists()) { status("File not found: " + file.getAbsolutePath()); return; }
        selectedFile = file;
        loadAndPlay(file);
    }

    private void onFileSelected(ListView<String> list, String dir) {
        String name = list.getSelectionModel().getSelectedItem();
        if (name == null || name.startsWith("#")) return;
        selectedFile = new File(dir, name);
        nowPlayingLabel.setText(name);
        status("Selected: " + name);
    }

    private void deleteSelected() {
        ListView<String> list = activeList();
        String name = list.getSelectionModel().getSelectedItem();
        if (name == null || name.startsWith("#")) return;

        String dir = isMusic() ? MUSIC_DIR : SFX_DIR;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + name + "\"?", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Confirm Delete");
        styleAlert(confirm);
        confirm.showAndWait().ifPresent(r -> {
            if (r == ButtonType.YES) {
                stopPreview();
                try {
                    Files.deleteIfExists(Paths.get(dir, name));
                    status("✓ Deleted: " + name);
                    refreshLists();
                } catch (IOException e) {
                    status("✗ Delete failed: " + e.getMessage());
                }
            }
        });
    }

    private void loadAndPlay(File file) {
        stopPreview();
        try {
            Media media = new Media(file.toURI().toString());
            previewPlayer = new MediaPlayer(media);
            previewPlayer.setVolume(musicVolSlider.getValue());

            previewPlayer.setOnReady(() -> {
                Duration total = previewPlayer.getTotalDuration();
                seekSlider.setMax(total.toSeconds());
                nowPlayingLabel.setText(file.getName());
                previewPlayer.play();
                isPlaying = true;
                playPauseBtn.setText("⏸ Pause");
                status("▶ Playing: " + file.getName());
            });

            previewPlayer.currentTimeProperty().addListener((obs, o, n) ->
                    Platform.runLater(() -> {
                        if (!seekSlider.isValueChanging()) {
                            seekSlider.setValue(n.toSeconds());
                        }
                        Duration total = previewPlayer.getTotalDuration();
                        timeLabel.setText(formatTime(n) + " / " + formatTime(total));
                    }));

            previewPlayer.setOnEndOfMedia(() -> {
                isPlaying = false;
                playPauseBtn.setText("▶ Play");
                status("Finished: " + file.getName());
            });

        } catch (Exception e) {
            status("✗ Cannot play: " + e.getMessage());
        }
    }

    private void togglePlayPause() {
        if (previewPlayer == null) {
            if (selectedFile != null) loadAndPlay(selectedFile);
            return;
        }
        if (isPlaying) {
            previewPlayer.pause();
            isPlaying = false;
            playPauseBtn.setText("▶ Play");
        } else {
            previewPlayer.play();
            isPlaying = true;
            playPauseBtn.setText("⏸ Pause");
        }
    }

    private void stopPreview() {
        if (previewPlayer != null) {
            previewPlayer.stop();
            previewPlayer.dispose();
            previewPlayer = null;
        }
        isPlaying = false;
        Platform.runLater(() -> {
            playPauseBtn.setText("▶ Play");
            seekSlider.setValue(0);
            timeLabel.setText("0:00 / 0:00");
        });
    }

    // ── List management ───────────────────────────────────────────────────────

    private void refreshLists() {
        populateList(musicList, MUSIC_DIR);
        populateList(sfxList, SFX_DIR);
        status("Refreshed audio directories.");
    }

    private void populateList(ListView<String> list, String dirPath) {
        list.getItems().clear();
        File dir = new File(dirPath);
        if (!dir.exists()) {
            list.getItems().add("# Directory not found");
            list.getItems().add("# " + dir.getAbsolutePath());
            return;
        }
        File[] files = dir.listFiles(f ->
                f.isFile() && !f.getName().equals("README.txt") &&
                isAudioFile(f.getName()));

        if (files == null || files.length == 0) {
            list.getItems().add("# No audio files yet");
            list.getItems().add("# Use Import to add files");
            return;
        }
        for (File f : files) list.getItems().add(f.getName());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isMusic() {
        Toggle t = categoryToggle.getSelectedToggle();
        return t == null || ((ToggleButton) t).getText().contains("Music");
    }

    private ListView<String> activeList() {
        return isMusic() ? musicList : sfxList;
    }

    private boolean isAudioFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") ||
               lower.endsWith(".ogg") || lower.endsWith(".aac") ||
               lower.endsWith(".m4a");
    }

    private String formatTime(Duration d) {
        if (d == null || d.isUnknown()) return "0:00";
        int secs  = (int) d.toSeconds();
        int mins  = secs / 60;
        int remsec = secs % 60;
        return mins + ":" + String.format("%02d", remsec);
    }

    private void status(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    // ── Styling helpers ───────────────────────────────────────────────────────

    private Label styledLabel(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0;" +
                   "-fx-font-size: " + size + ";" +
                   (bold ? "-fx-font-weight: bold;" : ""));
        return l;
    }

    private Button actionButton(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + ";" +
                   "-fx-text-fill: white;" +
                   "-fx-background-radius: 4;" +
                   "-fx-font-size: 11;" +
                   "-fx-padding: 5 10 5 10;");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private void styleList(ListView<String> list) {
        list.setStyle("-fx-background-color: #0f0f1e;" +
                      "-fx-border-color: #3a3a6a;" +
                      "-fx-border-radius: 4;" +
                      "-fx-control-inner-background: #0f0f1e;" +
                      "-fx-text-fill: #e0e0e0;");
        list.setPrefHeight(200);
    }

    private void styleToggleButton(ToggleButton b) {
        b.setStyle("-fx-background-color: #0f3460;" +
                   "-fx-text-fill: white;" +
                   "-fx-background-radius: 4;" +
                   "-fx-font-size: 11;");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
    }

    private void styleCheck(CheckBox c) {
        c.setStyle("-fx-text-fill: #a0a0c0;");
    }

    private void styleAlert(Alert alert) {
        alert.getDialogPane().setStyle(
                "-fx-background-color: #1a1a2e; -fx-text-fill: #e0e0e0;");
    }
}
