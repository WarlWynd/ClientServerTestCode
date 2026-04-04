package com.game.client.ui;

import com.game.client.AppSettings;
import com.game.client.GameResolution;
import com.game.client.SessionStore;
import com.game.client.SoundMode;
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

    private final Runnable onRestartClient;

    public SettingsPanel(Runnable onRestartClient) {
        this.onRestartClient = onRestartClient;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Node buildView() {

        // ── Audio ─────────────────────────────────────────────────────────────
        ToggleGroup soundGroup = new ToggleGroup();
        HBox radioRow = new HBox(16);
        radioRow.setAlignment(Pos.CENTER_LEFT);

        for (SoundMode mode : SoundMode.values()) {
            RadioButton rb = new RadioButton(mode.label);
            rb.setToggleGroup(soundGroup);
            rb.setUserData(mode);
            rb.setSelected(AppSettings.getSoundMode() == mode);
            rb.setStyle("-fx-text-fill: #c0c0d8;");
            radioRow.getChildren().add(rb);
        }

        soundGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) AppSettings.setSoundMode((SoundMode) val.getUserData());
        });

        VBox audioSection = section("Audio", row(radioRow));

        // ── Display ───────────────────────────────────────────────────────────
        CheckBox keepAwakeCheck = new CheckBox("Keep screen awake");
        keepAwakeCheck.setSelected(AppSettings.isKeepScreenAwake());
        keepAwakeCheck.setStyle("-fx-text-fill: #c0c0d8;");
        keepAwakeCheck.selectedProperty().addListener((obs, old, val) ->
                AppSettings.setKeepScreenAwake(val));

        Label hudLabel = new Label("HUD Opacity:");
        hudLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 12;");

        ToggleGroup hudGroup = new ToggleGroup();
        HBox hudRow = new HBox(12);
        hudRow.setAlignment(Pos.CENTER_LEFT);

        double[] opacityLevels  = { 0.25, 0.50, 0.75, 1.0 };
        String[] opacityLabels  = { "25%", "50%", "75%", "100%" };
        for (int i = 0; i < opacityLevels.length; i++) {
            RadioButton rb = new RadioButton(opacityLabels[i]);
            rb.setToggleGroup(hudGroup);
            rb.setUserData(opacityLevels[i]);
            rb.setSelected(Math.abs(AppSettings.getHudOpacity() - opacityLevels[i]) < 0.01);
            rb.setStyle("-fx-text-fill: #c0c0d8;");
            hudRow.getChildren().add(rb);
        }
        hudGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) AppSettings.setHudOpacity((double) val.getUserData());
        });

        // ── Resolution ────────────────────────────────────────────────────────
        Label resLabel = new Label("Resolution:");
        resLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 12;");

        ToggleGroup resGroup = new ToggleGroup();
        VBox resCol = new VBox(6);
        for (GameResolution res : GameResolution.values()) {
            RadioButton rb = new RadioButton(res.displayLabel());
            rb.setToggleGroup(resGroup);
            rb.setUserData(res);
            rb.setSelected(AppSettings.getResolution() == res);
            rb.setStyle("-fx-text-fill: #c0c0d8;");
            resCol.getChildren().add(rb);
        }
        resGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) AppSettings.setResolution((GameResolution) val.getUserData());
        });

        Label resNote = new Label("Resolution applies next time you enter the game.");
        resNote.setStyle("-fx-text-fill: #505065; -fx-font-style: italic; -fx-font-size: 11;");

        VBox displaySection = section("Display",
                row(keepAwakeCheck),
                row(hudLabel),
                row(hudRow),
                row(resLabel),
                row(resCol),
                row(resNote));

        // ── Gameplay ──────────────────────────────────────────────────────────
        VBox gameplaySection = section("Gameplay",
                comingSoon());

        // ── Account ───────────────────────────────────────────────────────────
        VBox accountSection = section("Account", comingSoon());

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

        // ── Admin: Connection ─────────────────────────────────────────────────
        VBox connectionSection = null;
        TextField hostField = null;
        TextField portField = null;

        if (SessionStore.isAdmin()) {
            hostField = styledField(AppSettings.getServerHost());
            portField = styledField(String.valueOf(AppSettings.getServerPort()));

            Label restartNote = new Label("Connection changes take effect on next launch.");
            restartNote.setStyle("-fx-text-fill: #606080; -fx-font-size: 11; -fx-font-style: italic;");

            connectionSection = section("Connection (Admin)",
                    row("Server Host:", hostField),
                    row("Server Port:", portField),
                    row(restartNote));
        }

        final TextField finalHostField = hostField;
        final TextField finalPortField = portField;

        saveBtn.setOnAction(e -> {
            if (SessionStore.isAdmin() && finalHostField != null) {
                String host = finalHostField.getText().trim();
                if (!host.isEmpty()) AppSettings.setServerHost(host);
                try {
                    int p = Integer.parseInt(finalPortField.getText().trim());
                    if (p > 0 && p < 65536) AppSettings.setServerPort(p);
                } catch (NumberFormatException ignored) {}
            }
            boolean ok = AppSettings.save();
            setStatus(statusLabel, ok ? "Settings saved." : "Could not write settings file.", ok);
        });

        resetBtn.setOnAction(e -> {
            soundGroup.getToggles().forEach(t ->
                    t.setSelected(t.getUserData() == AppSettings.getSoundMode()));
            keepAwakeCheck.setSelected(AppSettings.isKeepScreenAwake());
            hudGroup.getToggles().forEach(t ->
                    t.setSelected(Math.abs((double) t.getUserData() - AppSettings.getHudOpacity()) < 0.01));
            if (finalHostField != null) finalHostField.setText(AppSettings.getServerHost());
            if (finalPortField != null) finalPortField.setText(String.valueOf(AppSettings.getServerPort()));
            setStatus(statusLabel, "Reset to current saved values.", true);
        });

        Button restartClientBtn = new Button("Restart Client");
        restartClientBtn.setStyle("""
                -fx-background-color: #1a3a5a;
                -fx-text-fill: #80c0ff;
                -fx-font-weight: bold;
                -fx-background-radius: 4;
                -fx-padding: 7 14 7 14;
                """);
        restartClientBtn.setOnAction(e -> {
            AppSettings.save();
            if (onRestartClient != null) onRestartClient.run();
        });

        HBox buttons = new HBox(10, resetBtn, saveBtn, statusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(16, 20, 4, 20));

        HBox restartRow = new HBox(restartClientBtn);
        restartRow.setAlignment(Pos.CENTER_LEFT);
        restartRow.setPadding(new Insets(0, 20, 20, 20));

        // ── Scroll container ──────────────────────────────────────────────────
        VBox content = new VBox(audioSection, displaySection, gameplaySection, accountSection);
        if (connectionSection != null) content.getChildren().add(connectionSection);
        content.getChildren().addAll(buttons, restartRow);
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
