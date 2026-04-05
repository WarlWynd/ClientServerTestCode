package com.game.syncapp;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Standalone client file sync application.
 *
 * Distributed to end users. Connects to the asset server, downloads any
 * missing or changed client files, and reports the result.
 *
 * Configuration is loaded from syncapp.properties on the classpath.
 */
public class SyncApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(SyncApp.class);

    // ── Config ────────────────────────────────────────────────────────────────
    private static final Properties CONFIG    = loadConfig();
    private static final String APP_NAME      = cfg("app.name",    "Adventure Friends Sync");
    private static final String VERSION       = cfg("app.version", "1.0.0");
    private static final String CORP_NAME     = cfg("corp.name",   "");
    private static final String ASSET_URL     = cfg("asset.url",   "http://localhost:9877");
    private static final Path   INSTALL_DIR   = resolveInstallDir(cfg("install.dir", "${user.home}/.adventure-friends"));

    // ── UI nodes ──────────────────────────────────────────────────────────────
    private Label       statusLabel;
    private Label       fileLabel;
    private ProgressBar progressBar;
    private TextArea    logArea;
    private Button      closeBtn;
    private Button      launchBtn;

    @Override
    public void start(Stage stage) {
        stage.setTitle(APP_NAME + " v" + VERSION);
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> Platform.exit());

        // ── Header ────────────────────────────────────────────────────────────
        Text title = new Text(APP_NAME);
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-fill: #e0e0e0;");

        if (!CORP_NAME.isBlank()) {
            Label corpLbl = new Label(CORP_NAME);
            corpLbl.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 11;");
            // added below in vbox
        }

        Label versionLbl = new Label("Version " + VERSION);
        versionLbl.setStyle("-fx-text-fill: #7090c0; -fx-font-size: 11;");

        Label installLbl = new Label("Installing to: " + INSTALL_DIR);
        installLbl.setStyle("-fx-text-fill: #505070; -fx-font-size: 10;");
        installLbl.setWrapText(true);
        installLbl.setMaxWidth(400);

        Separator sep1 = new Separator();
        sep1.setStyle("-fx-background-color: #2a2a4a;");

        // ── Progress ──────────────────────────────────────────────────────────
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setPrefHeight(14);
        progressBar.setStyle("-fx-accent: #4a90d9;");

        fileLabel = new Label("Connecting to server…");
        fileLabel.setStyle("-fx-text-fill: #8080a0; -fx-font-size: 10;");
        fileLabel.setMaxWidth(400);

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(400);
        statusLabel.setAlignment(Pos.CENTER);

        // ── Log area ──────────────────────────────────────────────────────────
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(140);
        logArea.setStyle("""
                -fx-background-color: #0f0f1e;
                -fx-text-fill: #a0c0a0;
                -fx-font-family: monospace;
                -fx-font-size: 11;
                -fx-border-color: #2a2a4a;
                -fx-border-radius: 4;
                """);

        // ── Launch / Close buttons ────────────────────────────────────────────
        launchBtn = new Button("Launch Game");
        launchBtn.setPrefWidth(140);
        launchBtn.setStyle("""
                -fx-background-color: #3a6abf;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 28 8 28;
                """);
        launchBtn.setVisible(false);
        launchBtn.setOnAction(e -> launchGame());

        closeBtn = new Button("Close");
        closeBtn.setPrefWidth(120);
        closeBtn.setStyle("""
                -fx-background-color: #2a6a4a;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 28 8 28;
                """);
        closeBtn.setVisible(false);
        closeBtn.setOnAction(e -> Platform.exit());

        // ── Layout ────────────────────────────────────────────────────────────
        VBox corpRow = new VBox(2);
        corpRow.setAlignment(Pos.CENTER);
        corpRow.getChildren().add(title);
        if (!CORP_NAME.isBlank()) {
            Label c = new Label(CORP_NAME);
            c.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 11;");
            corpRow.getChildren().add(c);
        }
        corpRow.getChildren().add(versionLbl);

        HBox btnRow = new HBox(10, launchBtn, closeBtn);
        btnRow.setAlignment(Pos.CENTER);

        VBox root = new VBox(10,
                corpRow,
                installLbl,
                sep1,
                progressBar,
                fileLabel,
                statusLabel,
                logArea,
                btnRow);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24, 30, 24, 30));
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setScene(new Scene(root, 480, 420));
        stage.show();

        startSync();
    }

    private void startSync() {
        appendLog("Connecting to " + ASSET_URL + " …");
        Thread.ofVirtual().name("sync").start(() -> {
            FileSyncClient.SyncResult result = FileSyncClient.sync(
                ASSET_URL,
                INSTALL_DIR,
                (done, total, filename) -> Platform.runLater(() -> {
                    progressBar.setProgress(total > 0 ? (double) done / total : 0);
                    fileLabel.setText(filename != null ? "Checking: " + filename : done + " / " + total);
                }),
                (filename, isNew) -> Platform.runLater(() ->
                    appendLog((isNew ? "  Downloaded: " : "  Up to date: ") + filename))
            );
            Platform.runLater(() -> onSyncDone(result));
        });
    }

    private void onSyncDone(FileSyncClient.SyncResult result) {
        progressBar.setProgress(1.0);
        fileLabel.setText("");

        String color, msg;

        if (result.hasError()) {
            if (result.error().contains("Cannot reach")) {
                color = "#c0a040";
                msg   = "Could not reach the server. Check your internet connection and try again.";
            } else {
                color = "#c04040";
                msg   = "Error: " + result.error();
            }
            appendLog("ERROR: " + result.error());
        } else if (result.allCurrent()) {
            color = "#60c060";
            msg   = result.checked() == 0
                    ? "Nothing to sync — no client files found on server."
                    : "All " + result.checked() + " file(s) are up to date.";
            appendLog("Sync complete — all files current.");
        } else {
            color = "#60c060";
            msg   = result.downloaded() + " file(s) updated";
            if (result.failed() > 0) msg += ", " + result.failed() + " failed";
            msg += " (checked " + result.checked() + " total).";
            appendLog("Sync complete — " + result.downloaded() + " downloaded, "
                    + result.failed() + " failed.");
        }

        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        closeBtn.setVisible(true);

        // Show Launch button if the client JAR was downloaded
        java.nio.file.Path clientJar = INSTALL_DIR.resolve("client").resolve("game-client.jar");
        if (java.nio.file.Files.exists(clientJar)) {
            launchBtn.setVisible(true);
        }

        log.info("Sync done: {}", msg);
    }

    private void launchGame() {
        java.nio.file.Path clientJar = INSTALL_DIR.resolve("client").resolve("game-client.jar");
        try {
            new ProcessBuilder(
                    ProcessHandle.current().info().command().orElse("java"),
                    "--enable-preview",
                    "-jar", clientJar.toAbsolutePath().toString()
            ).inheritIO().start();
            Platform.exit();
        } catch (Exception e) {
            log.error("Failed to launch game: {}", e.getMessage(), e);
            appendLog("ERROR: Could not launch game — " + e.getMessage());
        }
    }

    private void appendLog(String line) {
        logArea.appendText(line + "\n");
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream in = SyncApp.class.getResourceAsStream("/syncapp.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        return p;
    }

    private static String cfg(String key, String def) {
        return CONFIG.getProperty(key, def);
    }

    private static Path resolveInstallDir(String raw) {
        String resolved = raw
                .replace("${user.home}", System.getProperty("user.home"))
                .replace("${appdata}",   System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")));
        return Path.of(resolved);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
