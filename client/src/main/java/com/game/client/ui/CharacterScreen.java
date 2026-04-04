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
 * Standalone character screen shown after login.
 * If the user already has a character, shows their character info and an Enter Game button.
 * If not, shows the character creation form.
 */
public class CharacterScreen {

    private final Stage     stage;
    private final UDPClient client;

    private TextField nameField;
    private Label     statusLabel;
    private Button    actionBtn;

    public CharacterScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        String existingName = SessionStore.getCharacterName();
        if (existingName != null && !existingName.isBlank()) {
            showCharacterInfo(existingName);
        } else {
            showCreationForm();
        }
    }

    // ── Existing character view ───────────────────────────────────────────────

    private void showCharacterInfo(String charName) {
        Text title = new Text("Your Character");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setStyle("-fx-fill: #e0e0e0;");

        Label nameLbl = new Label("CHARACTER NAME");
        nameLbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");

        Label nameVal = new Label(charName);
        nameVal.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 20; -fx-font-weight: bold;");

        Button enterBtn = new Button("Enter Game");
        enterBtn.setDefaultButton(true);
        enterBtn.setPrefWidth(300);
        enterBtn.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 10 0 10 0;
                """);
        enterBtn.setOnAction(e -> new GameScreen(stage, client).show());

        VBox content = new VBox(14, title, nameLbl, nameVal, enterBtn);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(50));
        content.setMaxWidth(400);

        StackPane root = new StackPane(content);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle(AppSettings.getProgramName() + " — Character");
        stage.setScene(new Scene(root, 480, 360));
    }

    // ── Character creation view ───────────────────────────────────────────────

    private void showCreationForm() {
        Text title = new Text("Create Your Character");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setStyle("-fx-fill: #e0e0e0;");

        Text subtitle = new Text("Choose a name for your character.");
        subtitle.setStyle("-fx-fill: #a0a0c0;");

        nameField = new TextField();
        nameField.setPromptText("Character name (2–50 characters)");
        nameField.setMaxWidth(300);
        nameField.setStyle("""
                -fx-background-color: #16213e;
                -fx-text-fill: #e0e0e0;
                -fx-prompt-text-fill: #6060a0;
                -fx-border-color: #3a3a6a;
                -fx-border-radius: 4;
                -fx-background-radius: 4;
                -fx-padding: 8;
                """);
        nameField.setOnAction(e -> doCreate());

        actionBtn = new Button("Create Character");
        actionBtn.setDefaultButton(true);
        actionBtn.setPrefWidth(300);
        actionBtn.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 10 0 10 0;
                """);
        actionBtn.setOnAction(e -> doCreate());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(300);

        VBox form = new VBox(14, title, subtitle, nameField, actionBtn, statusLabel);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(50));
        form.setMaxWidth(400);

        StackPane root = new StackPane(form);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle(AppSettings.getProgramName() + " — Create Character");
        stage.setScene(new Scene(root, 480, 360));
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void doCreate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            status("Please enter a character name.", false);
            return;
        }
        actionBtn.setDisable(true);
        status("Creating character…", true);
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("characterName", name);
        client.send(new Packet(PacketType.CHARACTER_CREATE_REQUEST, SessionStore.getToken(), payload));
    }

    private void onPacket(Packet packet) {
        Platform.runLater(() -> {
            if (packet.type != PacketType.CHARACTER_CREATE_RESPONSE) return;

            boolean success = packet.payload.get("success").asBoolean();
            String  message = packet.payload.get("message").asText();

            if (success) {
                SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                showCharacterInfo(SessionStore.getCharacterName());
            } else if (packet.payload.has("characterName")) {
                // Already have a character — server sent back the name
                SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                showCharacterInfo(SessionStore.getCharacterName());
            } else {
                status(message, false);
                if (actionBtn != null) actionBtn.setDisable(false);
            }
        });
    }

    private void status(String msg, boolean ok) {
        if (statusLabel != null) {
            statusLabel.setText(msg);
            statusLabel.setStyle(ok ? "-fx-text-fill: #44cc44;" : "-fx-text-fill: #cc3333;");
        }
    }
}
