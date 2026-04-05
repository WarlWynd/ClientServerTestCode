package com.game.syncapp;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static final String BASE_CSS  = "/styles/base.css";
    private static final String DARK_CSS  = "/styles/dark.css";

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
        title.getStyleClass().add("title-text");

        Label versionLbl = new Label("Version " + VERSION);
        versionLbl.getStyleClass().addAll("text-info", "font-11");

        Label installLbl = new Label("Installing to: " + INSTALL_DIR);
        installLbl.getStyleClass().addAll("text-muted", "font-10");
        installLbl.setWrapText(true);
        installLbl.setMaxWidth(400);

        Separator sep1 = new Separator();
        sep1.getStyleClass().add("sep-dark");

        // ── Progress ──────────────────────────────────────────────────────────
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setPrefHeight(14);
        progressBar.getStyleClass().add("progress-accent");

        fileLabel = new Label("Connecting to server…");
        fileLabel.getStyleClass().addAll("text-secondary", "font-10");
        fileLabel.setMaxWidth(400);

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(400);
        statusLabel.setAlignment(Pos.CENTER);

        // ── Log area ──────────────────────────────────────────────────────────
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(140);
        logArea.getStyleClass().add("log-area");

        // ── Launch / Close buttons ────────────────────────────────────────────
        launchBtn = new Button("Launch Game");
        launchBtn.setPrefWidth(140);
        launchBtn.getStyleClass().add("btn-info");
        launchBtn.setVisible(false);
        launchBtn.setOnAction(e -> launchGame());

        closeBtn = new Button("Close");
        closeBtn.setPrefWidth(120);
        closeBtn.getStyleClass().add("btn-success");
        closeBtn.setVisible(false);
        closeBtn.setOnAction(e -> Platform.exit());

        // ── Layout ────────────────────────────────────────────────────────────
        VBox corpRow = new VBox(2);
        corpRow.setAlignment(Pos.CENTER);
        corpRow.getChildren().add(title);
        if (!CORP_NAME.isBlank()) {
            Label c = new Label(CORP_NAME);
            c.getStyleClass().addAll("badge-muted", "font-11");
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
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 480, 420);
        applyTheme(scene);
        stage.setScene(scene);
        stage.show();

        // Force the TextArea's inner content pane to use the dark background.
        // Must run after show() so the skin and content node exist.
        Platform.runLater(() -> {
            javafx.scene.Node content = logArea.lookup(".content");
            if (content != null) content.setStyle("-fx-background-color: -af-surface;");
        });

        startSync();
    }

    private void applyTheme(Scene scene) {
        scene.getStylesheets().clear();
        addStylesheet(scene, BASE_CSS);
        addStylesheet(scene, DARK_CSS);
    }

    private void addStylesheet(Scene scene, String resource) {
        var url = SyncApp.class.getResource(resource);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
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

        String afColor, msg;

        if (result.hasError()) {
            if (result.error().contains("Cannot reach")) {
                afColor = "-af-warning";
                msg     = "Could not reach the server. Check your internet connection and try again.";
            } else {
                afColor = "-af-error";
                msg     = "Error: " + result.error();
            }
            appendLog("ERROR: " + result.error());
        } else if (result.allCurrent()) {
            afColor = "-af-success";
            msg     = result.checked() == 0
                    ? "Nothing to sync — no client files found on server."
                    : "All " + result.checked() + " file(s) are up to date.";
            appendLog("Sync complete — all files current.");
        } else {
            afColor = "-af-success";
            msg     = result.downloaded() + " file(s) updated";
            if (result.failed() > 0) msg += ", " + result.failed() + " failed";
            msg += " (checked " + result.checked() + " total).";
            appendLog("Sync complete — " + result.downloaded() + " downloaded, "
                    + result.failed() + " failed.");
        }

        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: " + afColor + "; -fx-font-weight: bold;");
        closeBtn.setVisible(true);

        // Show Launch button if the client JAR was downloaded
        java.nio.file.Path clientJar = INSTALL_DIR.resolve("client").resolve("game-client.jar");
        if (java.nio.file.Files.exists(clientJar)) {
            launchBtn.setVisible(true);
        }

        // Copy this sync app's JAR to ~/.adventure-friends/sync/ so game-client.jar can find it
        copySelfToInstallDir();

        log.info("Sync done: {}", msg);
    }

    private void copySelfToInstallDir() {
        try {
            // Find the JAR this class was loaded from
            java.net.URI uri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            java.nio.file.Path self = java.nio.file.Path.of(uri);
            if (!self.toString().endsWith(".jar")) return; // running from class files (dev mode)

            java.nio.file.Path dest = INSTALL_DIR.resolve("sync").resolve("syncapp.jar");
            java.nio.file.Files.createDirectories(dest.getParent());
            // Only bootstrap if the installed copy doesn't exist yet.
            // Once the server has syncapp.jar in its manifest, the sync step
            // keeps the installed copy up to date — don't overwrite it here.
            if (!java.nio.file.Files.exists(dest)) {
                java.nio.file.Files.copy(self, dest,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("SyncApp bootstrapped to {}", dest);
            }
        } catch (Exception e) {
            log.warn("Could not copy SyncApp to install dir: {}", e.getMessage());
        }
    }

    private void launchGame() {
        java.nio.file.Path clientJar = INSTALL_DIR.resolve("client").resolve("game-client.jar");
        try {
            String java = ProcessHandle.current().info().command().orElse("java");

            // Forward the current JVM's module-path and add-opens so JavaFX is available
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            // Carry over module-path, add-modules, add-opens from the current JVM
            for (int i = 0; i < jvmArgs.size(); i++) {
                String a = jvmArgs.get(i);
                if (a.startsWith("--module-path") || a.startsWith("--add-modules")
                        || a.startsWith("--add-opens") || a.startsWith("--add-reads")) {
                    cmd.add(a);
                } else if (a.equals("--module-path") || a.equals("--add-modules")) {
                    cmd.add(a);
                    if (i + 1 < jvmArgs.size()) cmd.add(jvmArgs.get(++i));
                }
            }
            cmd.add("--enable-preview");
            cmd.add("-jar");
            cmd.add(clientJar.toAbsolutePath().toString());
            cmd.add("--synced"); // tell the client to skip the sync redirect

            Process proc = new ProcessBuilder(cmd).inheritIO().start();
            // Give the process a moment to fail fast on startup errors
            Thread.sleep(500);
            if (proc.isAlive()) {
                Platform.exit();
            } else {
                appendLog("ERROR: Game process exited immediately (exit code " + proc.exitValue() + ")");
            }
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
