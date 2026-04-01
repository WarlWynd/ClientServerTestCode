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

/**
 * Shown between VersionCheckScreen and LoginScreen.
 *
 * Fetches /client-manifest and downloads any outdated or missing files
 * into ~/.game/{type}/. Auto-advances to LoginScreen if everything is
 * already current; otherwise shows a "Continue" button when done.
 */
public class ClientSyncScreen {

    private static final Logger log = LoggerFactory.getLogger(ClientSyncScreen.class);

    private final Stage    stage;
    private final String   assetUrl;
    private final Runnable onComplete;

    private Label       statusLabel;
    private ProgressBar progressBar;
    private Button      continueBtn;

    public ClientSyncScreen(Stage stage, String assetUrl, Runnable onComplete) {
        this.stage      = stage;
        this.assetUrl   = assetUrl;
        this.onComplete = onComplete;
    }

    public void show() {
        Text title = new Text("Multiplayer Game");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-fill: #e0e0e0;");

        Text subtitle = new Text("Checking client files…");
        subtitle.setStyle("-fx-fill: #8888aa;");

        statusLabel = new Label("Contacting asset server…");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(340);
        statusLabel.setAlignment(Pos.CENTER);

        progressBar = new ProgressBar(-1); // indeterminate until we know total
        progressBar.setPrefWidth(340);
        progressBar.setStyle("-fx-accent: #4a90d9;");

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

        VBox box = new VBox(14, title, subtitle, progressBar, statusLabel, continueBtn);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle("Game — Syncing");
        stage.setScene(new Scene(root, 480, 320));
        stage.show();

        startSync();
    }

    private void startSync() {
        Thread.ofVirtual().name("client-sync").start(() -> {
            ClientSyncClient.SyncResult result = ClientSyncClient.sync(assetUrl, (done, total) ->
                Platform.runLater(() -> {
                    progressBar.setProgress(total > 0 ? (double) done / total : 0);
                    statusLabel.setText("Checking… " + done + " / " + total);
                })
            );
            Platform.runLater(() -> onSyncDone(result));
        });
    }

    private void onSyncDone(ClientSyncClient.SyncResult result) {
        progressBar.setProgress(1.0);

        if (result.hasError()) {
            if (result.error().startsWith("Cannot reach")) {
                // Non-fatal — asset server may not be running; continue with cached files
                log.warn("Asset server not reachable at {} — continuing with cached files", assetUrl);
                statusLabel.setText("Asset server not reachable — using cached files.");
                statusLabel.setStyle("-fx-text-fill: #c0a040;");
                continueBtn.setVisible(true);
            } else {
                log.warn("Client sync error: {}", result.error());
                statusLabel.setText("Sync error: " + result.error());
                statusLabel.setStyle("-fx-text-fill: #c04040;");
                continueBtn.setVisible(true);
            }
            return;
        }

        if (result.allCurrent()) {
            String msg = result.checked() == 0
                    ? "No client files on server — ready."
                    : "All " + result.checked() + " file(s) up to date.";
            statusLabel.setText(msg);
            statusLabel.setStyle("-fx-text-fill: #60c060;");
            log.info("Client sync: {}", msg);
            // Auto-advance after a short pause so the user can see the green status
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                Platform.runLater(onComplete::run);
            });
        } else {
            String msg = result.downloaded() + " file(s) updated";
            if (result.failed() > 0) msg += ", " + result.failed() + " failed";
            msg += ".";
            statusLabel.setText(msg);
            statusLabel.setStyle("-fx-text-fill: #60c060;");
            log.info("Client sync complete: {}", msg);
            continueBtn.setVisible(true);
        }
    }
}
