package com.game.client.ui;

import com.game.client.UpdateChecker;
import com.game.client.UpdateChecker.UpdateInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
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
 * Shown at startup. Checks GitHub for a newer release.
 * <ul>
 *   <li>If up to date — immediately hands off to {@code onUpToDate}.</li>
 *   <li>If an update is found — shows a download progress bar, then either
 *       exits (packaged app) or proceeds to {@code onUpToDate} (dev/Gradle run).</li>
 *   <li>If the check fails — silently proceeds to {@code onUpToDate}.</li>
 * </ul>
 */
public class UpdateScreen {

    private static final Logger log = LoggerFactory.getLogger(UpdateScreen.class);

    private final Stage    stage;
    private final String   currentVersion;
    private final Runnable onUpToDate;

    private Label       statusLabel;
    private ProgressBar progressBar;

    public UpdateScreen(Stage stage, String currentVersion, Runnable onUpToDate) {
        this.stage          = stage;
        this.currentVersion = currentVersion;
        this.onUpToDate     = onUpToDate;
    }

    public void show() {
        Text title = new Text("Multiplayer Game");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-fill: #e0e0e0;");

        statusLabel = new Label("Checking for updates…");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
        statusLabel.setFont(Font.font("System", 13));

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(280);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: #e94560;");

        VBox box = new VBox(16, title, statusLabel, progressBar);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle("Multiplayer Game v" + currentVersion + " - ");
        stage.setScene(new Scene(root, 480, 400));
        stage.show();

        Thread.ofPlatform().daemon(true).start(this::runCheck);
    }

    private void runCheck() {
        UpdateInfo info = UpdateChecker.checkForUpdate(currentVersion);

        if (info == null) {
            Platform.runLater(onUpToDate);
            return;
        }

        Platform.runLater(() -> {
            statusLabel.setText("Downloading update v" + info.latestVersion() + "…");
            progressBar.setVisible(true);
        });

        try {
            boolean updateStarted = UpdateChecker.applyUpdate(info, progress ->
                    Platform.runLater(() -> progressBar.setProgress(progress)));

            if (updateStarted) {
                Platform.runLater(() -> {
                    statusLabel.setText("Update ready — restarting…");
                    progressBar.setProgress(1.0);
                });
                Thread.sleep(1500);
                Platform.exit();
            } else {
                // Dev / Gradle run — jpackage.app-path not set, just continue
                Platform.runLater(onUpToDate);
            }
        } catch (Exception e) {
            log.error("Update failed: {}", e.getMessage(), e);
            Platform.runLater(() -> {
                statusLabel.setText("Update failed — launching anyway…");
                progressBar.setVisible(false);
            });
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            Platform.runLater(onUpToDate);
        }
    }
}
