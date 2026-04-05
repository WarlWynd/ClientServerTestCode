package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.SessionStore;
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
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

public class LoginScreen {

    private final Stage     stage;
    private final UDPClient client;

    private TextField     emailField;
    private PasswordField passwordField;
    private Label         statusLabel;
    private Button        loginButton;

    public LoginScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        Text title = new Text(AppSettings.getProgramName());
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.getStyleClass().add("title-text");

        Text subtitle = new Text("Sign in to play");
        subtitle.setFont(Font.font("System", 14));
        subtitle.getStyleClass().add("subtitle-text");

        boolean remember = AppSettings.isRememberUsername();
        emailField = new TextField(remember ? AppSettings.getLastUsername() : "");
        emailField.setPromptText("Email address");
        emailField.setMaxWidth(280);
        emailField.getStyleClass().addAll("input-field");

        passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setMaxWidth(280);
        passwordField.getStyleClass().add("input-field");

        CheckBox rememberBox = new CheckBox("Remember email");
        rememberBox.setSelected(remember);
        rememberBox.getStyleClass().add("check-secondary");

        passwordField.setOnAction(e -> doLogin(rememberBox.isSelected()));

        loginButton = new Button("Log In");
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(280);
        loginButton.getStyleClass().add("btn-primary");
        loginButton.setOnAction(e -> doLogin(rememberBox.isSelected()));

        statusLabel = new Label();
        statusLabel.getStyleClass().addAll("text-error", "font-12");
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
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 480, 400);
        ThemeManager.apply(scene);
        stage.setTitle(AppSettings.getProgramName() + " — Login");
        stage.setScene(scene);
        stage.show();

        if (remember && !emailField.getText().isEmpty()) {
            Platform.runLater(passwordField::requestFocus);
        }
    }

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
                        String  token    = packet.payload.get("sessionToken").asText();
                        String  username = packet.payload.get("username").asText();
                        boolean isAdmin  = packet.payload.has("isAdmin") && packet.payload.get("isAdmin").asBoolean();
                        boolean isAudioDev = packet.payload.has("isAudioDev") && packet.payload.get("isAudioDev").asBoolean();
                        SessionStore.set(token, username, isAdmin, isAudioDev);
                        AppSettings.setRememberUsername(pendingRemember);
                        AppSettings.setLastUsername(pendingRemember ? emailField.getText().trim() : "");
                        AppSettings.save();
                        if (packet.payload.has("characterName"))
                            SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                        new CharacterScreen(stage, client).show();
                    } else {
                        statusLabel.setText(packet.payload.get("message").asText("Login failed."));
                        loginButton.setDisable(false);
                    }
                }
                case ERROR -> {
                    statusLabel.setText(packet.payload.get("message").asText("Server error."));
                    loginButton.setDisable(false);
                }
                default -> {}
            }
        });
    }
}
