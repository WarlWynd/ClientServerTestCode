package com.game.syncapp;

import javafx.application.Application;
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

import java.io.InputStream;
import java.util.Properties;

public class SyncApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(SyncApp.class);

    private static final String APP_NAME = loadConfig().getProperty("app.name",    "Sync App");
    private static final String VERSION  = loadConfig().getProperty("app.version", "1.0.0");
    private static final String ASSET_URL = loadConfig().getProperty("asset.url",  "http://localhost:9877");

    private Label       statusLabel;
    private ProgressBar progressBar;
    private Button      closeBtn;

    @Override
    public void start(Stage stage) {
        stage.setTitle(APP_NAME + " v" + VERSION);
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> Platform.exit());

        Text title = new Text(APP_NAME);
        title.setFont(Font.font("System", FontWeight.BOLD, 26));
        title.setStyle("-fx-fill: #e0e0e0;");

        Label versionLabel = new Label("Software Version: " + VERSION);
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

        closeBtn = new Button("Close");
        closeBtn.setStyle("""
                -fx-background-color: #2a6a4a;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 28 8 28;
                """);
        closeBtn.setVisible(false);
        closeBtn.setOnAction(e -> Platform.exit());

        VBox box = new VBox(10, title, versionLabel, subtitle, progressBar, statusLabel, closeBtn);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setScene(new Scene(root, 480, 320));
        stage.show();

        startSync();
    }

    private void startSync() {
        Thread.ofVirtual().name("file-sync").start(() -> {
            FileSyncClient.SyncResult result = FileSyncClient.sync(
                ASSET_URL,
                (done, total) -> Platform.runLater(() -> {
                    progressBar.setProgress(total > 0 ? (double) done / total : 0);
                    statusLabel.setText("Checking… " + done + " / " + total);
                })
            );
            Platform.runLater(() -> onSyncDone(result));
        });
    }

    private void onSyncDone(FileSyncClient.SyncResult result) {
        progressBar.setProgress(1.0);

        String color, msg;

        if (result.hasError()) {
            if (result.error().startsWith("Cannot reach")) {
                log.warn("Asset server not reachable — continuing with cached files");
                color = "#c0a040";
                msg   = "Asset server not reachable — using cached files.";
            } else {
                log.warn("Sync error: {}", result.error());
                color = "#c04040";
                msg   = "Sync error: " + result.error();
            }
        } else if (result.allCurrent()) {
            color = "#60c060";
            msg   = result.checked() == 0
                    ? "No new files on server — nothing to sync."
                    : "All " + result.checked() + " file(s) are up to date.";
        } else {
            color = "#60c060";
            msg   = result.downloaded() + " of " + result.checked() + " file(s) updated";
            if (result.failed() > 0) msg += ", " + result.failed() + " failed";
            msg += ".";
        }

        log.info("Sync complete: {}", msg);
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: " + color + ";");
        closeBtn.setVisible(true);
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream in = SyncApp.class.getResourceAsStream("/syncapp.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        return p;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
