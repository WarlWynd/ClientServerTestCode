package com.game.client.ui;

import com.game.client.AppSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Settings panel — shown as a tab in GameScreen for all users.
 *
 * Sections can be expanded freely as new settings are added.
 * Call {@link #buildView()} once to get the root Node.
 */
public class SettingsPanel {

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node buildView() {

        // ── Connection ────────────────────────────────────────────────────────
        TextField hostField = styledField(AppSettings.getServerHost());
        TextField portField = styledField(String.valueOf(AppSettings.getServerPort()));

        Label restartNote = new Label("Connection changes take effect after restart.");
        restartNote.setStyle("-fx-text-fill: #8080a0; -fx-font-size: 10;");

        VBox connectionSection = section("Connection",
                row("Server Host", hostField),
                row("Server Port", portField),
                restartNote);

        // ── Audio ─────────────────────────────────────────────────────────────
        CheckBox soundCheck = new CheckBox("Enable sound effects");
        soundCheck.setSelected(AppSettings.isSoundEnabled());
        soundCheck.setStyle("-fx-text-fill: #c0c0d8;");
        soundCheck.selectedProperty().addListener((obs, old, val) ->
                AppSettings.setSoundEnabled(val));

        VBox audioSection = section("Audio",
                row(soundCheck));

        // ── Display ───────────────────────────────────────────────────────────
        VBox displaySection = section("Display",
                comingSoon());

        // ── Gameplay ──────────────────────────────────────────────────────────
        VBox gameplaySection = section("Gameplay",
                comingSoon());

        // ── Account ───────────────────────────────────────────────────────────
        VBox accountSection = section("Account",
                comingSoon());

        // ── Save / Reset ──────────────────────────────────────────────────────
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11;");

        Button saveBtn = new Button("Save Settings");
        saveBtn.setStyle("""
                -fx-background-color: #e94560;
                -fx-text-fill: white;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 7 18 7 18;
                """);

        Button resetBtn = new Button("Reset to Defaults");
        resetBtn.setStyle("""
                -fx-background-color: #2a2a4a;
                -fx-text-fill: #a0a0c0;
                -fx-background-radius: 4;
                -fx-padding: 7 14 7 14;
                """);

        saveBtn.setOnAction(e -> {
            // Validate port
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) {
                setStatus(statusLabel, "Invalid port number.", false);
                return;
            }
            AppSettings.setServerHost(hostField.getText().trim());
            AppSettings.setServerPort(port);
            boolean ok = AppSettings.save();
            setStatus(statusLabel, ok ? "Settings saved." : "Could not write settings file.", ok);
        });

        resetBtn.setOnAction(e -> {
            hostField.setText(AppSettings.getServerHost());
            portField.setText(String.valueOf(AppSettings.getServerPort()));
            soundCheck.setSelected(AppSettings.isSoundEnabled());
            setStatus(statusLabel, "Reset to current saved values.", true);
        });

        HBox buttons = new HBox(10, resetBtn, saveBtn, statusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(16, 20, 20, 20));

        // ── Scroll container ──────────────────────────────────────────────────
        VBox content = new VBox(
                connectionSection,
                audioSection,
                displaySection,
                gameplaySection,
                accountSection,
                buttons);
        content.setStyle("-fx-background-color: #1a1a2e;");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("""
                -fx-background: #1a1a2e;
                -fx-background-color: #1a1a2e;
                -fx-border-color: transparent;
                """);

        return scroll;
    }

    // ── Section builder ───────────────────────────────────────────────────────

    private static VBox section(String title, Node... children) {
        Label header = new Label(title);
        header.setFont(Font.font("System", FontWeight.BOLD, 13));
        header.setTextFill(Color.web("#e94560"));

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #2a2a4a;");

        VBox body = new VBox(6);
        body.setPadding(new Insets(4, 0, 0, 0));
        body.getChildren().addAll(children);

        VBox box = new VBox(4, header, sep, body);
        box.setPadding(new Insets(14, 20, 8, 20));
        box.setStyle("-fx-background-color: #1a1a2e;");
        return box;
    }

    /** A label + control pair on one row. */
    private static HBox row(String label, Control control) {
        Label lbl = new Label(label);
        lbl.setMinWidth(130);
        lbl.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 12;");
        HBox row = new HBox(12, lbl, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A row containing a single full-width node (e.g. CheckBox). */
    private static HBox row(Node node) {
        HBox row = new HBox(node);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 0, 2, 0));
        return row;
    }

    private static Label comingSoon() {
        Label lbl = new Label("More options coming soon.");
        lbl.setStyle("-fx-text-fill: #505065; -fx-font-style: italic; -fx-font-size: 11;");
        return lbl;
    }

    // ── Styling ───────────────────────────────────────────────────────────────

    private static TextField styledField(String value) {
        TextField f = new TextField(value);
        f.setPrefWidth(220);
        f.setStyle("""
                -fx-background-color: #16213e;
                -fx-text-fill: #e0e0e0;
                -fx-prompt-text-fill: #6060a0;
                -fx-border-color: #3a3a6a;
                -fx-border-radius: 4;
                -fx-background-radius: 4;
                -fx-padding: 6;
                """);
        return f;
    }

    private static void setStatus(Label label, String msg, boolean success) {
        label.setText(msg);
        label.setStyle("-fx-font-size: 11; -fx-text-fill: "
                + (success ? "#50c050" : "#e94560") + ";");
    }
}
