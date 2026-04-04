package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * Character creation screen — shown after login when the user has no character.
 * Sends CHARACTER_CREATE_REQUEST and navigates to GameScreen on success.
 */
public class CharacterCreationScreen {

    private final Stage     stage;
    private final UDPClient client;

    private TextField nameField;
    private Label     statusLabel;
    private Button    createBtn;

    public CharacterCreationScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text("Create Your Character");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setStyle("-fx-fill: #e0e0e0;");

        Text subtitle = new Text("Choose a name for your character.");
        subtitle.setStyle("-fx-fill: #a0a0c0;");

        nameField = new TextField();
        nameField.setPromptText("Character name (2–50 characters)");
        nameField.setMaxWidth(300);
        nameField.setOnAction(e -> doCreate());

        createBtn = new Button("Create Character");
        createBtn.setDefaultButton(true);
        createBtn.setPrefWidth(300);
        createBtn.setOnAction(e -> doCreate());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(300);

        VBox form = new VBox(14, title, subtitle, nameField, createBtn, statusLabel);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(50));
        form.setMaxWidth(400);

        StackPane root = new StackPane(form);
        root.setStyle("-fx-background-color: #1a1a2e;");

        styleField(nameField);
        styleButton(createBtn);

        stage.setTitle(AppSettings.getProgramName() + " — Create Character");
        stage.setScene(new Scene(root, 480, 360));
    }

    private void doCreate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            status("Please enter a character name.", false);
            return;
        }

        createBtn.setDisable(true);
        status("Creating character…", true);

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("characterName", name);
        client.send(new Packet(PacketType.CHARACTER_CREATE_REQUEST, SessionStore.getToken(), payload));
    }

    private void onPacket(Packet packet) {
        Platform.runLater(() -> {
            if (packet.type == PacketType.CHARACTER_CREATE_RESPONSE) {
                boolean success = packet.payload.get("success").asBoolean();
                String  message = packet.payload.get("message").asText();
                if (success) {
                    SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                    status("Character created! Entering game…", true);
                    new Thread(() -> {
                        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> new GameScreen(stage, client).show());
                    }).start();
                } else if (packet.payload.has("characterName")) {
                    // User already has a character — store it and proceed to game
                    SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                    status("Loading your character…", true);
                    new Thread(() -> {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> new GameScreen(stage, client).show());
                    }).start();
                } else {
                    status(message, false);
                    createBtn.setDisable(false);
                }
            }
        });
    }

    private void status(String msg, boolean ok) {
        statusLabel.setText(msg);
        statusLabel.setStyle(ok ? "-fx-text-fill: #44cc44;" : "-fx-text-fill: #cc3333;");
    }

    private void styleField(TextInputControl f) {
        f.setStyle("""
                -fx-background-color: #16213e;
                -fx-text-fill: #e0e0e0;
                -fx-prompt-text-fill: #6060a0;
                -fx-border-color: #3a3a6a;
                -fx-border-radius: 4;
                -fx-background-radius: 4;
                -fx-padding: 8;
                """);
    }

    private void styleButton(Button b) {
        b.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 10 0 10 0;
                """);
    }
}
