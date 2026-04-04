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
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * Login screen — shown at startup.
 *
 * Sends LOGIN_REQUEST and waits for LOGIN_RESPONSE.
 * On success navigates to the GameScreen.
 * Provides a link to the RegisterScreen.
 */
public class LoginScreen {

    private final Stage     stage;
    private final UDPClient client;

    // Form fields
    private TextField     emailField;
    private PasswordField passwordField;
    private Label         statusLabel;
    private Button        loginButton;

    public LoginScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    // ── Build & show ─────────────────────────────────────────────────────────

    public void show() {
        client.setPacketListener(this::onPacket);

        // Title
        Text title = new Text(AppSettings.getProgramName());
        title.setFont(Font.font("System", FontWeight.BOLD, 28));

        Text subtitle = new Text("Sign in to play");
        subtitle.setFont(Font.font("System", 14));

        // Form
        boolean remember = AppSettings.isRememberUsername();
        emailField = new TextField(remember ? AppSettings.getLastUsername() : "");
        emailField.setPromptText("Email address");
        emailField.setMaxWidth(280);

        passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setMaxWidth(280);
        CheckBox rememberBox = new CheckBox("Remember email");
        rememberBox.setSelected(remember);
        rememberBox.setStyle("-fx-text-fill: #a0a0c0;");

        passwordField.setOnAction(e -> doLogin(rememberBox.isSelected()));

        loginButton = new Button("Log In");
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(280);
        loginButton.setOnAction(e -> doLogin(rememberBox.isSelected()));

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #cc3333;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(280);

        Hyperlink registerLink = new Hyperlink("Don't have an account? Register");
        registerLink.setOnAction(e -> new RegisterScreen(stage, client).show());

        VBox form = new VBox(12,
                title, subtitle,
                emailField, passwordField,
                rememberBox,
                loginButton,
                statusLabel,
                registerLink);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(40));
        form.setMaxWidth(360);

        StackPane root = new StackPane(form);
        root.setStyle("-fx-background-color: #1a1a2e;");
        title.setStyle("-fx-fill: #e0e0e0;");
        subtitle.setStyle("-fx-fill: #a0a0c0;");
        styleField(emailField);
        styleField(passwordField);
        styleButton(loginButton);

        Scene scene = new Scene(root, 480, 400);
        stage.setTitle(AppSettings.getProgramName() + " — Login v" + AppSettings.getClientVersion());
        stage.setScene(scene);
        stage.show();

        if (remember && !emailField.getText().isEmpty()) {
            Platform.runLater(passwordField::requestFocus);
        }
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    private boolean pendingRemember;

    private void doLogin(boolean rememberEmail) {
        String email    = emailField.getText().trim();
        String password = passwordField.getText();

        if (email.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Please enter both email and password.");
            return;
        }

        pendingRemember = rememberEmail;
        loginButton.setDisable(true);
        statusLabel.setText("Connecting…");

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("email",    email);
        payload.put("password", password);

        client.send(new Packet(PacketType.LOGIN_REQUEST, null, payload));
    }

    private void onPacket(Packet packet) {
        Platform.runLater(() -> {
            switch (packet.type) {
                case LOGIN_RESPONSE -> {
                    boolean success = packet.payload.get("success").asBoolean();
                    if (success) {
                        String  token        = packet.payload.get("sessionToken").asText();
                        String  username     = packet.payload.get("username").asText();
                        boolean isAdmin      = packet.payload.has("isAdmin") &&
                                              packet.payload.get("isAdmin").asBoolean();
                        boolean isAudioDev = packet.payload.has("isAudioDev") &&
                                              packet.payload.get("isAudioDev").asBoolean();
                        SessionStore.set(token, username, isAdmin, isAudioDev);
                        AppSettings.setRememberUsername(pendingRemember);
                        AppSettings.setLastUsername(pendingRemember ? emailField.getText().trim() : "");
                        AppSettings.save();
                        new GameScreen(stage, client).show();
                    } else {
                        String msg = packet.payload.get("message").asText("Login failed.");
                        statusLabel.setText(msg);
                        loginButton.setDisable(false);
                    }
                }
                case ERROR -> {
                    statusLabel.setText(packet.payload.get("message").asText("Server error."));
                    loginButton.setDisable(false);
                }
                default -> { /* ignore other packet types on this screen */ }
            }
        });
    }

    // ── Styling helpers ──────────────────────────────────────────────────────

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
