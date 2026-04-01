package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import com.game.client.AppSettings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Verifies that this client version is accepted by the server.
 *
 * Sends VERSION_CHECK_REQUEST immediately on show(), then waits for
 * VERSION_CHECK_RESPONSE.  On success, calls {@code onCompatible};
 * on failure, displays an error and blocks further navigation.
 */
public class VersionCheckScreen {

    private static final Logger log = LoggerFactory.getLogger(VersionCheckScreen.class);

    private final Stage           stage;
    private final UDPClient       client;
    private final String          clientVersion;
    private final Consumer<String> onCompatible; // receives assetUrl

    private Label statusLabel;

    public VersionCheckScreen(Stage stage, UDPClient client, String clientVersion, Consumer<String> onCompatible) {
        this.stage         = stage;
        this.client        = client;
        this.clientVersion = clientVersion;
        this.onCompatible  = onCompatible;
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text("Multiplayer Game");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-fill: #e0e0e0;");

        statusLabel = new Label("Checking server compatibility…");
        statusLabel.setStyle("-fx-text-fill: #a0a0c0;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(300);

        VBox box = new VBox(16, title, statusLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");

        Scene scene = new Scene(root, 480, 300);
        stage.setTitle("Game — Connecting");
        stage.setScene(scene);
        stage.show();

        sendVersionCheck();
    }

    // ── Network ──────────────────────────────────────────────────────────────

    private void sendVersionCheck() {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("version", clientVersion);
        client.send(new Packet(PacketType.VERSION_CHECK_REQUEST, null, payload));
        log.info("Sent VERSION_CHECK_REQUEST version={}", clientVersion);
    }

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.VERSION_CHECK_RESPONSE) return;

        Platform.runLater(() -> {
            boolean compatible = packet.payload.get("compatible").asBoolean();
            if (compatible) {
                int assetPort = packet.payload.has("assetPort")
                        ? packet.payload.get("assetPort").asInt(9877)
                        : 9877;
                String assetUrl = "http://" + AppSettings.getServerHost() + ":" + assetPort;
                log.info("Version check passed ({}) — assetUrl={}", clientVersion, assetUrl);
                onCompatible.accept(assetUrl);
            } else {
                String msg = packet.payload.has("message")
                        ? packet.payload.get("message").asText()
                        : "Your client version is not supported by this server.";
                log.warn("Version check failed: {}", msg);
                showError(msg);
            }
        });
    }

    // ── Error state ──────────────────────────────────────────────────────────

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: #cc3333;");

        Button exitButton = new Button("Exit");
        exitButton.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 8 24 8 24;
                """);
        exitButton.setOnAction(e -> javafx.application.Platform.exit());

        VBox box = new VBox(16,
                new Text("Multiplayer Game") {{ setFont(Font.font("System", FontWeight.BOLD, 28)); setStyle("-fx-fill: #e0e0e0;"); }},
                statusLabel,
                exitButton);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        StackPane root = new StackPane(box);
        root.setStyle("-fx-background-color: #1a1a2e;");
        stage.setScene(new Scene(root, 480, 300));
    }
}
