package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.ThemeManager;
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

public class RegisterScreen {

    private final Stage     stage;
    private final UDPClient client;

    private TextField     usernameField;
    private TextField     emailField;
    private PasswordField passwordField;
    private PasswordField confirmField;
    private Label         statusLabel;
    private Button        registerButton;

    public RegisterScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text("Create Account");
        title.setFont(Font.font("System", FontWeight.BOLD, 26));
        title.getStyleClass().add("title-text");

        usernameField = new TextField();
        usernameField.setPromptText("Username (letters, digits, underscores)");
        usernameField.setMaxWidth(280);
        usernameField.getStyleClass().add("input-field");

        emailField = new TextField();
        emailField.setPromptText("Email address");
        emailField.setMaxWidth(280);
        emailField.getStyleClass().add("input-field");

        passwordField = new PasswordField();
        passwordField.setPromptText("Password (min 6 characters)");
        passwordField.setMaxWidth(280);
        passwordField.getStyleClass().add("input-field");

        confirmField = new PasswordField();
        confirmField.setPromptText("Confirm password");
        confirmField.setMaxWidth(280);
        confirmField.getStyleClass().add("input-field");
        confirmField.setOnAction(e -> doRegister());

        registerButton = new Button("Create Account");
        registerButton.setDefaultButton(true);
        registerButton.setPrefWidth(280);
        registerButton.getStyleClass().add("btn-register");
        registerButton.setOnAction(e -> doRegister());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(280);

        Hyperlink backLink = new Hyperlink("← Back to Login");
        backLink.setOnAction(e -> new LoginScreen(stage, client).show());

        VBox form = new VBox(12,
                title,
                usernameField, emailField, passwordField, confirmField,
                registerButton,
                statusLabel,
                backLink);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(40));
        form.setMaxWidth(360);

        StackPane root = new StackPane(form);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 480, 480);
        ThemeManager.apply(scene);
        stage.setTitle(AppSettings.getProgramName() + " — Register");
        stage.setScene(scene);
    }

    private void doRegister() {
        String username = usernameField.getText().trim();
        String email    = emailField.getText().trim();
        String password = passwordField.getText();
        String confirm  = confirmField.getText();

        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            status("Please fill in all fields.", false);
            return;
        }
        if (!password.equals(confirm)) {
            status("Passwords do not match.", false);
            return;
        }

        registerButton.setDisable(true);
        status("Creating account…", true);

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("email",    email);
        payload.put("password", password);
        client.send(new Packet(PacketType.REGISTER_REQUEST, null, payload));
    }

    private void onPacket(Packet packet) {
        Platform.runLater(() -> {
            if (packet.type == PacketType.REGISTER_RESPONSE) {
                boolean success = packet.payload.get("success").asBoolean();
                String  message = packet.payload.get("message").asText();
                if (success) {
                    status("✓ " + message + " Redirecting to login…", true);
                    new Thread(() -> {
                        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> new LoginScreen(stage, client).show());
                    }).start();
                } else {
                    status(message, false);
                    registerButton.setDisable(false);
                }
            }
        });
    }

    private void status(String msg, boolean ok) {
        statusLabel.setText(msg);
        statusLabel.setStyle(ok ? "-fx-text-fill: -af-success;" : "-fx-text-fill: -af-error;");
    }
}
