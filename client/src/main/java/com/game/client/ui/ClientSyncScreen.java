package com.game.client.ui;

import com.game.client.AppSettings;
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

/**
 * Shown between VersionCheckScreen and LoginScreen.
 *
 * Fetches /client-manifest and downloads any outdated or missing files
 * into ~/.game/{type}/. Shows the current software version and sync result
 * before the user can proceed to login.
 */
public class ClientSyncScreen {

    private static final Logger log = LoggerFactory.getLogger(ClientSyncScreen.class);

    private final Stage    stage;
    private final String   assetUrl;
    private final Runnable onComplete;

    private Label       statusLabel;
    private ProgressBar progressBar;
    private Button      continueBtn;
    private Button      retryBtn;

    public ClientSyncScreen(Stage stage, String assetUrl, String version, Runnable onComplete) {
        this.stage      = stage;
        this.assetUrl   = assetUrl;
        this.onComplete = onComplete;
    }

    public void show() {
        String softwareVersion = AppSettings.getClientVersion();

        Text title = new Text(AppSettings.getProgramName());
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-fill: #e0e0e0;");

        Label versionLabel = new Label("Software Version: " + softwareVersion);
        versionLabel.setStyle("-fx-text-fill: #7090c0; -fx-font-size: 12;");

        Label corpLabel = new Label(AppSettings.getCorpName());
        corpLabel.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 11;");

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

        retryBtn = new Button("Retry");
        retryBtn.setStyle("""
                -fx-background-color: #7a3000;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 28 8 28;
                """);
        retryBtn.setVisible(false);
        retryBtn.setOnAction(e -> {
            retryBtn.setVisible(false);
            statusLabel.setText("Retrying…");
            statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
            progressBar.setProgress(-1);
            startSync();
        });

        VBox box = new VBox(10, title, corpLabel, versionLabel, subtitle, progressBar, statusLabel, continueBtn, retryBtn);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle(AppSettings.getCorpName() + " — Sync v" + softwareVersion);
        stage.setScene(new Scene(root, 480, 340));
        stage.show();

        startSync();
    }

    private void startSync() {
        Thread.ofVirtual().name("client-sync").start(() -> {
            ClientSyncClient.SyncResult result = ClientSyncClient.sync(
                assetUrl,
                (done, total) -> Platform.runLater(() -> {
                    progressBar.setProgress(total > 0 ? (double) done / total : 0);
                    statusLabel.setText("Checking… " + done + " / " + total);
                }),
                serverVer -> {}
            );
            Platform.runLater(() -> onSyncDone(result));
        });
    }

    private void onSyncDone(ClientSyncClient.SyncResult result) {
        progressBar.setProgress(1.0);

        if (result.hasError()) {
            log.warn("Client sync failed: {}", result.error());
            statusLabel.setText("Cannot connect to the update server.\nPlease check your connection and try again.");
            statusLabel.setStyle("-fx-text-fill: #e94560;");
            retryBtn.setVisible(true);
            return;
        }

        String statusMsg;
        if (result.allCurrent()) {
            statusMsg = result.checked() == 0
                    ? "Nothing to sync."
                    : "All " + result.checked() + " file(s) are up to date.";
        } else {
            statusMsg = result.downloaded() + " file(s) updated";
            if (result.failed() > 0) statusMsg += ", " + result.failed() + " failed";
            statusMsg += " (" + result.checked() + " checked).";
        }

        log.info("Client sync: {}", statusMsg);
        statusLabel.setText(statusMsg);
        statusLabel.setStyle("-fx-text-fill: #60c060;");
        continueBtn.setVisible(true);
    }
}
