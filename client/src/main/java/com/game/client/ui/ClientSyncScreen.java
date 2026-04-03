package com.game.client.ui;

import com.game.client.ClientSyncClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Shown between VersionCheckScreen and LoginScreen.
 *
 * Fetches /client-manifest and downloads any outdated or missing files
 * into ~/.game/{type}/. Shows the current software version and sync result
 * before the user can proceed to login.
 */
public class ClientSyncScreen {

    private static final Logger log          = LoggerFactory.getLogger(ClientSyncScreen.class);
    private static final Path   VERSION_FILE = ClientSyncClient.BASE_DIR.resolve("client-version.txt");

    private final Stage    stage;
    private final String   assetUrl;
    private final String   version;      // protocol/app version
    private final Runnable onComplete;

    private Label       versionLabel;
    private Label       statusLabel;
    private ProgressBar progressBar;
    private Button      continueBtn;

    public ClientSyncScreen(Stage stage, String assetUrl, String version, Runnable onComplete) {
        this.stage      = stage;
        this.assetUrl   = assetUrl;
        this.version    = version;
        this.onComplete = onComplete;
    }

    public void show() {
        String localSoftwareVersion = readLocalSoftwareVersion();

        Text title = new Text("Multiplayer Game");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-fill: #e0e0e0;");

        // Software version display — updates once server version is known
        versionLabel = new Label(softwareVersionText(localSoftwareVersion, null));
        versionLabel.setStyle("-fx-text-fill: #7090c0; -fx-font-size: 12;");

        Text subtitle = new Text("Checking for updates…");
        subtitle.setStyle("-fx-fill: #8888aa;");

        progressBar = new ProgressBar(-1);
        progressBar.setPrefWidth(340);
        progressBar.setStyle("-fx-accent: #4a90d9;");

        statusLabel = new Label("Contacting asset server…");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(340);
        statusLabel.setAlignment(Pos.CENTER);

        continueBtn = new Button("Continue");
        continueBtn.setStyle("""
                -fx-background-color: #2a6a4a;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 28 8 28;
                """);
        continueBtn.setVisible(false);
        continueBtn.setOnAction(e -> onComplete.run());

        VBox box = new VBox(10, title, versionLabel, subtitle, progressBar, statusLabel, continueBtn);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle("Game — Syncing v" + version + " - ");
        stage.setScene(new Scene(root, 480, 340));
        stage.show();

        startSync(localSoftwareVersion);
    }

    /** Text shown in the on-screen version label. */
    private String softwareVersionText(String local, String server) {
        if (server != null && local != null && !local.equals(server)) {
            return "Software Version: v" + local + "  →  v" + server;
        }
        String ver = server != null ? server : local;
        return ver != null ? "Software Version: v" + ver : "Software Version: unknown";
    }

    private String readLocalSoftwareVersion() {
        try {
            if (Files.exists(VERSION_FILE)) return Files.readString(VERSION_FILE).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private void saveLocalSoftwareVersion(String ver) {
        try {
            Files.createDirectories(VERSION_FILE.getParent());
            Files.writeString(VERSION_FILE, ver,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.warn("Could not save client-version.txt: {}", e.getMessage());
        }
    }

    private void startSync(String localVersion) {
        Thread.ofVirtual().name("client-sync").start(() -> {
            ClientSyncClient.SyncResult result = ClientSyncClient.sync(
                assetUrl,
                (done, total) -> Platform.runLater(() -> {
                    progressBar.setProgress(total > 0 ? (double) done / total : 0);
                    statusLabel.setText("Checking… " + done + " / " + total);
                }),
                serverVer -> Platform.runLater(() -> {
                    // Update version label and title as soon as server version is known
                    versionLabel.setText(softwareVersionText(localVersion, serverVer));
                    if (localVersion != null && !localVersion.equals(serverVer)) {
                        stage.setTitle("Game — Syncing v" + localVersion + " → v" + serverVer + " - ");
                    } else {
                        stage.setTitle("Game — Syncing v" + serverVer + " - ");
                    }
                })
            );
            Platform.runLater(() -> onSyncDone(result, localVersion));
        });
    }

    private void onSyncDone(ClientSyncClient.SyncResult result, String localVersion) {
        progressBar.setProgress(1.0);

        if (!result.hasError() && result.serverVersion() != null) {
            saveLocalSoftwareVersion(result.serverVersion());
            versionLabel.setText("Software Version: v" + result.serverVersion());
        }

        String statusColor;
        String statusMsg;

        if (result.hasError()) {
            if (result.error().startsWith("Cannot reach")) {
                log.warn("Asset server not reachable at {} — continuing with cached files", assetUrl);
                statusColor = "#c0a040";
                statusMsg   = "Asset server not reachable — using cached files.";
            } else {
                log.warn("Client sync error: {}", result.error());
                statusColor = "#c04040";
                statusMsg   = "Sync error: " + result.error();
            }
        } else if (result.allCurrent()) {
            statusColor = "#60c060";
            statusMsg   = result.checked() == 0
                    ? "No new client files on server — nothing to sync."
                    : "No new client files on server — all " + result.checked() + " file(s) are up to date.";
        } else {
            statusColor = "#60c060";
            statusMsg   = result.downloaded() + " of " + result.checked() + " file(s) updated";
            if (result.failed() > 0) statusMsg += ", " + result.failed() + " failed";
            statusMsg += ".";
            if (result.serverVersion() != null) {
                String prev = (localVersion != null) ? "v" + localVersion : "previous version";
                statusMsg  += " (" + prev + " → v" + result.serverVersion() + ")";
            }
        }

        log.info("Client sync: {}", statusMsg);
        statusLabel.setText(statusMsg);
        statusLabel.setStyle("-fx-text-fill: " + statusColor + ";");
        continueBtn.setVisible(true);
    }
}
