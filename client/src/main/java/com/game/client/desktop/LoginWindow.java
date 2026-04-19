package com.game.client.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.ClientConfig;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class LoginWindow extends Application {

    private static final Path PREFS_FILE = Paths.get(
            System.getProperty("user.home"), ".game", "client-prefs.properties");

    private UDPClient udpClient;
    private Label     statusLabel;
    private Button    loginButton;
    private String    pendingEmail;
    private String    pendingPassword;
    private boolean   rememberEmail;
    private boolean   rememberPassword;

    @Override
    public void start(Stage stage) throws Exception {
        ClientConfig config = new ClientConfig();
        udpClient = new UDPClient(config);
        udpClient.setPacketListener(this::onPacket);
        udpClient.start();

        Properties prefs        = loadPrefs();
        String     savedEmail   = prefs.getProperty("email",    "");
        String     savedPassword= prefs.getProperty("password", "");
        boolean    saveEmail    = !savedEmail.isEmpty();
        boolean    savePassword = !savedPassword.isEmpty();

        Label title = new Label("Adventure Friends");
        title.setFont(Font.font("System", FontWeight.BOLD, 18));
        title.setTextFill(Color.web("#e0e0ff"));

        Label subtitle = new Label("Player Login");
        subtitle.setFont(Font.font("System", 10));
        subtitle.setTextFill(Color.web("#8080a0"));

        VBox brandBox = new VBox(4, title, subtitle);
        brandBox.setAlignment(Pos.CENTER);

        Separator sep = new Separator(javafx.geometry.Orientation.HORIZONTAL);
        sep.setStyle("-fx-background-color: #3a3a6a;");
        sep.setPadding(new Insets(4, 0, 4, 0));

        String fieldStyle = """
                -fx-background-color: #16213e;
                -fx-text-fill: #e0e0e0;
                -fx-prompt-text-fill: #505070;
                -fx-border-color: #3a3a6a;
                -fx-border-radius: 3;
                -fx-background-radius: 3;
                -fx-padding: 6;
                """;

        TextField     emailField    = new TextField(savedEmail);
        PasswordField passwordField = new PasswordField();
        passwordField.setText(savedPassword);
        emailField.setPromptText("Email");
        passwordField.setPromptText("Password");
        emailField.setStyle(fieldStyle);
        passwordField.setStyle(fieldStyle);
        emailField.setMaxWidth(Double.MAX_VALUE);
        passwordField.setMaxWidth(Double.MAX_VALUE);

        CheckBox rememberEmailBox    = styledCheckBox("Remember email",    saveEmail);
        CheckBox rememberPasswordBox = styledCheckBox("Remember password", savePassword);

        HBox checkRow = new HBox(12, rememberEmailBox, rememberPasswordBox);
        checkRow.setAlignment(Pos.CENTER_LEFT);

        loginButton = new Button("Login");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-font-size: 11;
                -fx-background-radius: 3;
                -fx-padding: 7 0 7 0;
                """);

        statusLabel = new Label("");
        statusLabel.setTextFill(Color.web("#e94560"));
        statusLabel.setFont(Font.font("System", 9));
        statusLabel.setWrapText(true);

        VBox root = new VBox(8, brandBox, sep, emailField, passwordField, checkRow, loginButton, statusLabel);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(20, 20, 20, 20));
        root.setStyle("-fx-background-color: #1a1a2e;");

        Runnable submit = () -> {
            rememberEmail    = rememberEmailBox.isSelected();
            rememberPassword = rememberPasswordBox.isSelected();
            pendingEmail     = emailField.getText();
            pendingPassword  = passwordField.getText();
            doLogin(pendingEmail, pendingPassword);
        };

        loginButton.setOnAction(e -> submit.run());
        passwordField.setOnAction(e -> submit.run());

        stage.setTitle("Adventure Friends");
        stage.setWidth(300);
        stage.setResizable(false);
        stage.setScene(new Scene(root, 300, 260));
        stage.setOnCloseRequest(e -> { udpClient.stop(); Platform.exit(); });
        stage.show();
        Platform.runLater(emailField::requestFocus);
    }

    private void doLogin(String email, String password) {
        if (email.isBlank() || password.isBlank()) {
            statusLabel.setText("Please enter email and password.");
            return;
        }
        loginButton.setDisable(true);
        statusLabel.setText("Connecting...");

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("email",    email);
        payload.put("password", password);
        udpClient.send(new Packet(PacketType.LOGIN_REQUEST, null, payload));
    }

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.LOGIN_RESPONSE) return;
        Platform.runLater(() -> {
            boolean success = packet.payload.path("success").asBoolean(false);
            if (!success) {
                statusLabel.setText(packet.payload.has("message")
                        ? packet.payload.get("message").asText()
                        : "Login failed.");
                loginButton.setDisable(false);
                return;
            }
            // Accept either key name the server might use
            String token = packet.payload.path("sessionToken").asText(
                    packet.payload.path("token").asText(""));
            String  username = packet.payload.path("username").asText("");
            boolean isAdmin  = packet.payload.path("isAdmin").asBoolean(
                    packet.payload.path("admin").asBoolean(false));

            savePrefs(
                    rememberEmail    ? pendingEmail    : "",
                    rememberPassword ? pendingPassword : "");

            SessionStore.set(token, username, isAdmin, false, false, false);
            udpClient.stop();
            Platform.exit();
        });
    }

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private static Properties loadPrefs() {
        Properties p = new Properties();
        if (Files.exists(PREFS_FILE)) {
            try (InputStream in = Files.newInputStream(PREFS_FILE)) { p.load(in); }
            catch (Exception ignored) {}
        }
        return p;
    }

    private static void savePrefs(String email, String password) {
        Properties p = new Properties();
        p.setProperty("email",    email);
        p.setProperty("password", password);
        try {
            Files.createDirectories(PREFS_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(PREFS_FILE)) {
                p.store(out, "Client — saved credentials");
            }
        } catch (Exception ignored) {}
    }

    private static CheckBox styledCheckBox(String text, boolean selected) {
        CheckBox cb = new CheckBox(text);
        cb.setSelected(selected);
        cb.setStyle("-fx-text-fill: #8080a0; -fx-font-size: 9;");
        return cb;
    }
}
