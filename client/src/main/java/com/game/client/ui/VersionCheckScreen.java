package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.UDPClient;
import com.game.shared.GameVersion;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * First screen shown at startup.
 *
 * Sends a VERSION_CHECK packet to the server and waits for VERSION_RESPONSE.
 *   - Compatible  → automatically navigates to LoginScreen
 *   - Incompatible → shows an error with the version details, blocks login
 *   - No response  → shows a timeout error with a Retry button
 */
public class VersionCheckScreen {

    private static final int TIMEOUT_MS = 5000;

    private final Stage     stage;
    private final UDPClient client;

    private Label            statusLabel;
    private ProgressIndicator spinner;
    private Button           retryButton;
    private Thread           timeoutThread;

    public VersionCheckScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text(AppSettings.getProgramName());
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-fill: #e0e0e0;");

        Label versionLabel = new Label("Client v" + GameVersion.VERSION);
        versionLabel.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 12;");

        spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);

        statusLabel = new Label("Connecting to server…");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(300);

        retryButton = new Button("Retry");
        retryButton.setVisible(false);
        retryButton.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 24 8 24;
                """);
        retryButton.setOnAction(e -> {
            retryButton.setVisible(false);
            spinner.setVisible(true);
            statusLabel.setText("Connecting to server…");
            statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
            sendVersionCheck();
        });

        VBox box = new VBox(16, title, versionLabel, spinner, statusLabel, retryButton);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        Scene scene = new Scene(root, 480, 320);
        stage.setTitle(AppSettings.getProgramName() + " — Connecting…");
        stage.setScene(scene);
        stage.show();

        sendVersionCheck();
    }

    // ── Version check ─────────────────────────────────────────────────────────

    private void sendVersionCheck() {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("version", GameVersion.VERSION);
        client.send(new Packet(PacketType.VERSION_CHECK, null, payload));
        startTimeout();
    }

    private void startTimeout() {
        if (timeoutThread != null) timeoutThread.interrupt();
        timeoutThread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(TIMEOUT_MS);
                Platform.runLater(this::showTimeout);
            } catch (InterruptedException ignored) {}
        });
    }

    private void showTimeout() {
        spinner.setVisible(false);
        statusLabel.setText("Could not reach server at the configured address.\nCheck that the server is running and try again.");
        statusLabel.setStyle("-fx-text-fill: #e94560;");
        retryButton.setVisible(true);
    }

    // ── Packet handler ────────────────────────────────────────────────────────

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.VERSION_RESPONSE) return;

        if (timeoutThread != null) timeoutThread.interrupt();

        boolean compatible   = packet.payload.get("compatible").asBoolean();
        String  serverVersion = packet.payload.get("serverVersion").asText();

        Platform.runLater(() -> {
            if (compatible) {
                new LoginScreen(stage, client).show();
            } else {
                // Version mismatch — block and show details
                spinner.setVisible(false);
                statusLabel.setText(
                        "Version mismatch!\n\n" +
                        "Your client: v" + GameVersion.VERSION + "\n" +
                        "Server requires: v" + serverVersion + "\n\n" +
                        "Please download the latest client.");
                statusLabel.setStyle("-fx-text-fill: #e94560;");
                stage.setTitle(AppSettings.getProgramName() + " — Version Mismatch");
            }
        });
    }
}
