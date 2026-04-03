package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Registration screen — reachable from the LoginScreen.
 *
 * Sends REGISTER_REQUEST and navigates back to LoginScreen on success.
 */
public class RegisterScreen {

    private final Stage     stage;
    private final UDPClient client;
    private final String    version;

    private TextField     usernameField;
    private PasswordField passwordField;
    private PasswordField confirmField;
    private Label         statusLabel;
    private Button        registerButton;

    public RegisterScreen(Stage stage, UDPClient client, String version) {
        this.stage   = stage;
        this.client  = client;
        this.version = version;
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text("Create Account");
        title.setFont(Font.font("System", FontWeight.BOLD, 26));
        title.setStyle("-fx-fill: #e0e0e0;");

        usernameField = new TextField();
        usernameField.setPromptText("Username (letters, digits, underscores)");
        usernameField.setMaxWidth(280);

        passwordField = new PasswordField();
        passwordField.setPromptText("Password (min 6 characters)");
        passwordField.setMaxWidth(280);

        confirmField = new PasswordField();
        confirmField.setPromptText("Confirm password");
        confirmField.setMaxWidth(280);
        confirmField.setOnAction(e -> doRegister());

        registerButton = new Button("Create Account");
        registerButton.setDefaultButton(true);
        registerButton.setPrefWidth(280);
        registerButton.setOnAction(e -> doRegister());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(280);

        Hyperlink backLink = new Hyperlink("← Back to Login");
        backLink.setOnAction(e -> new LoginScreen(stage, client, version).show());

        VBox form = new VBox(12,
                title,
                usernameField, passwordField, confirmField,
                registerButton,
                statusLabel,
                backLink);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(40));
        form.setMaxWidth(360);

        StackPane root = new StackPane(form);
        root.setStyle("-fx-background-color: #1a1a2e;");

        styleField(usernameField);
        styleField(passwordField);
        styleField(confirmField);
        styleButton(registerButton);

        Scene scene = new Scene(root, 480, 420);
        stage.setTitle("Game — Register v" + version + " - ");
        stage.setScene(scene);
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    private void doRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String confirm  = confirmField.getText();

        if (username.isEmpty() || password.isEmpty()) {
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
                    // Short delay so the user can read the success message
                    new Thread(() -> {
                        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> new LoginScreen(stage, client, version).show());
                    }).start();
                } else {
                    status(message, false);
                    registerButton.setDisable(false);
                }
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
                -fx-background-color: #0f3460;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 10 0 10 0;
                """);
    }
}
