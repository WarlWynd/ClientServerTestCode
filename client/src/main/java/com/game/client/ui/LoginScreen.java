package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.PasswordCrypto;
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

        boolean remember         = AppSettings.isRememberUsername();
        boolean allowRememberPwd = AppSettings.isAllowRememberPassword();
        boolean rememberPwd      = allowRememberPwd && AppSettings.isRememberPassword();

        emailField = new TextField(remember ? AppSettings.getLastUsername() : "");
        emailField.setPromptText("Email address");
        emailField.setMaxWidth(280);
        emailField.getStyleClass().addAll("input-field");

        passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setMaxWidth(280);
        passwordField.getStyleClass().add("input-field");
        if (rememberPwd) passwordField.setText(
                PasswordCrypto.decrypt(AppSettings.getLastPassword(), AppSettings.getLastUsername()));

        CheckBox rememberBox = new CheckBox();
        rememberBox.setSelected(remember);
        rememberBox.getStyleClass().add("check-secondary");

        CheckBox rememberPwdBox = new CheckBox();
        rememberPwdBox.setSelected(rememberPwd);
        rememberPwdBox.getStyleClass().add("check-secondary");
        rememberPwdBox.setVisible(allowRememberPwd);
        rememberPwdBox.setManaged(allowRememberPwd);

        Label rememberLbl  = new Label("Remember:");
        rememberLbl.getStyleClass().add("check-secondary");
        Label emailLbl     = new Label("Email");
        emailLbl.getStyleClass().add("check-secondary");
        Label passwordLbl  = new Label("Password");
        passwordLbl.getStyleClass().add("check-secondary");
        passwordLbl.setVisible(allowRememberPwd);
        passwordLbl.setManaged(allowRememberPwd);

        HBox rememberRow = new HBox(6, rememberLbl, rememberBox, emailLbl, rememberPwdBox, passwordLbl);
        rememberRow.setAlignment(Pos.CENTER_LEFT);

        passwordField.setOnAction(e -> doLogin(rememberBox.isSelected(), rememberPwdBox.isSelected()));

        loginButton = new Button("Log In");
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(280);
        loginButton.getStyleClass().add("btn-primary");
        loginButton.setOnAction(e -> doLogin(rememberBox.isSelected(), rememberPwdBox.isSelected()));

        statusLabel = new Label();
        statusLabel.getStyleClass().addAll("text-error", "font-12");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(280);

        Hyperlink registerLink = new Hyperlink("Don't have an account? Register");
        registerLink.setOnAction(e -> new RegisterScreen(stage, client).show());

        VBox form = new VBox(12,
                title, subtitle,
                emailField, passwordField,
                rememberRow,
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
            if (rememberPwd && !passwordField.getText().isEmpty())
                Platform.runLater(loginButton::requestFocus);
            else
                Platform.runLater(passwordField::requestFocus);
        }
    }

    private boolean pendingRemember;
    private boolean pendingRememberPassword;

    private void doLogin(boolean rememberEmail, boolean rememberPassword) {
        String email    = emailField.getText().trim();
        String password = passwordField.getText();

        if (email.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Please enter both email and password.");
            return;
        }

        pendingRemember         = rememberEmail;
        pendingRememberPassword = rememberPassword;
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
                        boolean isAdmin       = packet.payload.has("isAdmin")       && packet.payload.get("isAdmin").asBoolean();
                        boolean isGraphicsDev = packet.payload.has("isGraphicsDev") && packet.payload.get("isGraphicsDev").asBoolean();
                        boolean isBoardDev    = packet.payload.has("isBoardDev")    && packet.payload.get("isBoardDev").asBoolean();
                        SessionStore.set(token, username, isAdmin, isGraphicsDev, isBoardDev);
                        AppSettings.setRememberUsername(pendingRemember);
                        AppSettings.setLastUsername(pendingRemember ? emailField.getText().trim() : "");
                        AppSettings.setRememberPassword(pendingRememberPassword);
                        AppSettings.setLastPassword(pendingRememberPassword
                                ? PasswordCrypto.encrypt(passwordField.getText(), emailField.getText().trim())
                                : "");
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
