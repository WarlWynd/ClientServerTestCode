package com.game.admin.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.admin.AdminSession;
import com.game.admin.AdminUDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class LoginScreen {

    private final Stage          stage;
    private final AdminUDPClient client;

    private Label         statusLabel;
    private Button        loginButton;

    public LoginScreen(Stage stage, AdminUDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        Label title = new Label("Server Admin");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setTextFill(Color.web("#e0e0ff"));

        Label subtitle = new Label("Administrator Login");
        subtitle.setFont(Font.font("System", 13));
        subtitle.setTextFill(Color.web("#8080a0"));

        TextField     usernameField = new TextField();
        PasswordField passwordField = new PasswordField();
        usernameField.setPromptText("Username");
        passwordField.setPromptText("Password");
        for (var f : new Control[]{usernameField, passwordField}) {
            f.setStyle("""
                    -fx-background-color: #16213e;
                    -fx-text-fill: #e0e0e0;
                    -fx-prompt-text-fill: #505070;
                    -fx-border-color: #3a3a6a;
                    -fx-border-radius: 4;
                    -fx-background-radius: 4;
                    -fx-padding: 8;
                    """);
            ((Region) f).setPrefWidth(280);
        }

        loginButton = new Button("Login");
        loginButton.setPrefWidth(280);
        loginButton.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-font-size: 13;
                -fx-background-radius: 4;
                -fx-padding: 9 0 9 0;
                """);

        statusLabel = new Label("");
        statusLabel.setTextFill(Color.web("#e94560"));
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(280);

        loginButton.setOnAction(e -> doLogin(usernameField.getText(), passwordField.getText()));
        passwordField.setOnAction(e -> doLogin(usernameField.getText(), passwordField.getText()));

        VBox root = new VBox(14, title, subtitle, usernameField, passwordField, loginButton, statusLabel);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(50));
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setScene(new Scene(root, 380, 360));
        stage.show();
    }

    private void doLogin(String username, String password) {
        if (username.isBlank() || password.isBlank()) {
            statusLabel.setText("Please enter username and password.");
            return;
        }
        loginButton.setDisable(true);
        statusLabel.setText("Connecting...");

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("password", password);
        client.send(new Packet(PacketType.LOGIN_REQUEST, null, payload));
    }

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.LOGIN_RESPONSE) return;
        Platform.runLater(() -> {
            boolean success = packet.payload.get("success").asBoolean();
            if (!success) {
                statusLabel.setText(packet.payload.has("message")
                        ? packet.payload.get("message").asText()
                        : "Login failed.");
                loginButton.setDisable(false);
                return;
            }
            boolean isAdmin = packet.payload.has("isAdmin")
                    && packet.payload.get("isAdmin").asBoolean();
            if (!isAdmin) {
                statusLabel.setText("Access denied — this account is not an admin.");
                loginButton.setDisable(false);
                return;
            }
            AdminSession.set(
                    packet.payload.get("sessionToken").asText(),
                    packet.payload.get("username").asText());
            new DashboardScreen(stage, client).show();
        });
    }
}
