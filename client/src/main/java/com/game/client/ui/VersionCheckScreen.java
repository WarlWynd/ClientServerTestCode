package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.ThemeManager;
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

public class VersionCheckScreen {

    private static final int TIMEOUT_MS = 5000;

    private final Stage     stage;
    private final UDPClient client;

    private Label             statusLabel;
    private ProgressIndicator spinner;
    private Button            retryButton;
    private Thread            timeoutThread;

    public VersionCheckScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text(AppSettings.getProgramName());
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.getStyleClass().add("title-text");

        Label versionLabel = new Label("Client v" + GameVersion.VERSION);
        versionLabel.getStyleClass().addAll("text-muted", "font-12");

        spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);

        statusLabel = new Label("Connecting to server…");
        statusLabel.getStyleClass().add("text-secondary");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(300);

        retryButton = new Button("Retry");
        retryButton.getStyleClass().add("btn-primary");
        retryButton.setVisible(false);
        retryButton.setOnAction(e -> {
            retryButton.setVisible(false);
            spinner.setVisible(true);
            statusLabel.setText("Connecting to server…");
            statusLabel.getStyleClass().setAll("text-secondary");
            sendVersionCheck();
        });

        VBox box = new VBox(16, title, versionLabel, spinner, statusLabel, retryButton);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 480, 320);
        ThemeManager.apply(scene);
        stage.setTitle(AppSettings.getProgramName() + " — Connecting…");
        stage.setScene(scene);
        stage.show();

        sendVersionCheck();
    }

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
        statusLabel.setStyle("-fx-text-fill: -af-error;");
        retryButton.setVisible(true);
    }

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.VERSION_RESPONSE) return;

        if (timeoutThread != null) timeoutThread.interrupt();

        boolean compatible    = packet.payload.get("compatible").asBoolean();
        String  serverVersion = packet.payload.get("serverVersion").asText();

        Platform.runLater(() -> {
            if (compatible) {
                new LoginScreen(stage, client).show();
            } else {
                spinner.setVisible(false);
                statusLabel.setText(
                        "Version mismatch!\n\n" +
                        "Your client: v" + GameVersion.VERSION + "\n" +
                        "Server requires: v" + serverVersion + "\n\n" +
                        "Please download the latest client.");
                statusLabel.setStyle("-fx-text-fill: -af-error;");
                stage.setTitle(AppSettings.getProgramName() + " — Version Mismatch");
            }
        });
    }
}
