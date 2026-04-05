package com.game.client;

import com.game.client.ui.VersionCheckScreen;
import com.game.shared.GameVersion;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX Application entry point.
 *
 * When launched without --synced, defers to the SyncApp for file sync
 * and exits. The SyncApp's "Launch Game" button re-launches this JAR
 * with --synced, at which point we go straight to the game.
 *
 * Start with:
 *   ./gradlew :client:run          (Gradle passes --synced automatically)
 *   java -jar game-client.jar --synced
 */
public class ClientMain extends Application {

    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

    private UDPClient udpClient;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // If launched without --synced, hand off to SyncApp and exit
        if (!getParameters().getRaw().contains("--synced")) {
            launchSyncApp();
            Platform.exit();
            return;
        }

        ClientConfig config = new ClientConfig();
        primaryStage.setResizable(false);
        primaryStage.setOnCloseRequest(e -> shutdown());

        udpClient = new UDPClient(config);
        try {
            udpClient.start();
        } catch (Exception e) {
            log.error("Failed to start UDP client: {}", e.getMessage(), e);
            Platform.exit();
            return;
        }
        new VersionCheckScreen(primaryStage, udpClient).show();
    }

    private void launchSyncApp() {
        Path syncJar = Path.of(System.getProperty("user.home"))
                .resolve(".adventure-friends").resolve("sync").resolve("syncapp.jar");

        if (!syncJar.toFile().exists()) {
            log.warn("SyncApp JAR not found at {} — launching game directly", syncJar);
            // Fallback: re-launch self with --synced so the game starts anyway
            relaunchWithSynced();
            return;
        }

        try {
            String java = ProcessHandle.current().info().command().orElse("java");
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            for (int i = 0; i < jvmArgs.size(); i++) {
                String a = jvmArgs.get(i);
                if (a.startsWith("--module-path") || a.startsWith("--add-modules")
                        || a.startsWith("--add-opens") || a.startsWith("--add-reads")) {
                    cmd.add(a);
                } else if ((a.equals("--module-path") || a.equals("--add-modules")) && i + 1 < jvmArgs.size()) {
                    cmd.add(a);
                    cmd.add(jvmArgs.get(++i));
                }
            }
            cmd.add("--enable-preview");
            cmd.add("-jar");
            cmd.add(syncJar.toAbsolutePath().toString());

            log.info("Launching SyncApp: {}", syncJar);
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception e) {
            log.error("Failed to launch SyncApp: {}", e.getMessage(), e);
            relaunchWithSynced();
        }
    }

    private void relaunchWithSynced() {
        try {
            String java = ProcessHandle.current().info().command().orElse("java");
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            String selfJar = Path.of(System.getProperty("user.home"))
                    .resolve(".adventure-friends").resolve("client").resolve("game-client.jar")
                    .toAbsolutePath().toString();

            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            for (int i = 0; i < jvmArgs.size(); i++) {
                String a = jvmArgs.get(i);
                if (a.startsWith("--module-path") || a.startsWith("--add-modules")
                        || a.startsWith("--add-opens") || a.startsWith("--add-reads")) {
                    cmd.add(a);
                } else if ((a.equals("--module-path") || a.equals("--add-modules")) && i + 1 < jvmArgs.size()) {
                    cmd.add(a);
                    cmd.add(jvmArgs.get(++i));
                }
            }
            cmd.add("--enable-preview");
            cmd.add("-jar");
            cmd.add(selfJar);
            cmd.add("--synced");
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception ex) {
            log.error("Fallback relaunch failed: {}", ex.getMessage(), ex);
        }
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (udpClient != null) udpClient.stop();
        log.info("Client shut down.");
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
