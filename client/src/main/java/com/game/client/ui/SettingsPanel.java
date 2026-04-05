package com.game.client.ui;

import com.game.client.AppSettings;
import com.game.client.GameResolution;
import com.game.client.SessionStore;
import com.game.client.SoundMode;
import com.game.client.ThemeManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.util.function.Consumer;

/**
 * Settings panel — shown as a tab in GameScreen for all users.
 *
 * Sections can be expanded freely as new settings are added.
 * Call {@link #buildView()} once to get the root Node.
 */
public class SettingsPanel {

    private final Runnable        onRestartClient;
    private final Consumer<Side>  onTabSideChange;

    public SettingsPanel(Runnable onRestartClient, Consumer<Side> onTabSideChange) {
        this.onRestartClient = onRestartClient;
        this.onTabSideChange = onTabSideChange;
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
            rb.getStyleClass().add("radio-secondary");
            radioRow.getChildren().add(rb);
        }

        soundGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) AppSettings.setSoundMode((SoundMode) val.getUserData());
        });

        VBox audioSection = section("Audio", row(radioRow));

        // ── Display ───────────────────────────────────────────────────────────
        CheckBox keepAwakeCheck = new CheckBox("Keep screen awake");
        keepAwakeCheck.setSelected(AppSettings.isKeepScreenAwake());
        keepAwakeCheck.getStyleClass().add("check-secondary");
        keepAwakeCheck.selectedProperty().addListener((obs, old, val) ->
                AppSettings.setKeepScreenAwake(val));

        Label hudLabel = new Label("HUD Opacity:");
        hudLabel.getStyleClass().addAll("text-secondary", "font-12");

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
            rb.getStyleClass().add("radio-secondary");
            hudRow.getChildren().add(rb);
        }
        hudGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) AppSettings.setHudOpacity((double) val.getUserData());
        });

        // ── Resolution ────────────────────────────────────────────────────────
        Label resLabel = new Label("Resolution:");
        resLabel.getStyleClass().addAll("text-secondary", "font-12");

        ToggleGroup resGroup = new ToggleGroup();
        VBox resCol = new VBox(6);
        for (GameResolution res : GameResolution.values()) {
            RadioButton rb = new RadioButton(res.displayLabel());
            rb.setToggleGroup(resGroup);
            rb.setUserData(res);
            rb.setSelected(AppSettings.getResolution() == res);
            rb.getStyleClass().add("radio-secondary");
            resCol.getChildren().add(rb);
        }
        resGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) AppSettings.setResolution((GameResolution) val.getUserData());
        });

        Label resNote = new Label("Resolution applies next time you enter the game.");
        resNote.getStyleClass().addAll("text-muted", "italic", "font-11");

        // ── Tabs ──────────────────────────────────────────────────────────────
        ToggleGroup tabSideGroup = new ToggleGroup();
        RadioButton tabTop  = new RadioButton("Top");
        RadioButton tabLeft = new RadioButton("Left");
        tabTop.setToggleGroup(tabSideGroup);
        tabLeft.setToggleGroup(tabSideGroup);
        tabTop.setUserData(Side.TOP);
        tabLeft.setUserData(Side.LEFT);
        tabTop.getStyleClass().add("radio-secondary");
        tabLeft.getStyleClass().add("radio-secondary");
        boolean isLeft = "LEFT".equalsIgnoreCase(AppSettings.getTabSide());
        tabTop.setSelected(!isLeft);
        tabLeft.setSelected(isLeft);

        tabSideGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            Side side = (Side) val.getUserData();
            AppSettings.setTabSide(side.name());
            if (onTabSideChange != null) onTabSideChange.accept(side);
        });

        HBox tabSideRow = new HBox(16, tabTop, tabLeft);
        tabSideRow.setAlignment(Pos.CENTER_LEFT);

        // ── Theme ─────────────────────────────────────────────────────────────
        ToggleGroup themeGroup = new ToggleGroup();
        RadioButton darkRb  = new RadioButton("Dark");
        RadioButton lightRb = new RadioButton("Light");
        darkRb.setToggleGroup(themeGroup);
        lightRb.setToggleGroup(themeGroup);
        darkRb.setUserData(ThemeManager.Theme.DARK);
        lightRb.setUserData(ThemeManager.Theme.LIGHT);
        darkRb.getStyleClass().add("radio-secondary");
        lightRb.getStyleClass().add("radio-secondary");
        darkRb.setSelected(ThemeManager.getTheme() == ThemeManager.Theme.DARK);
        lightRb.setSelected(ThemeManager.getTheme() == ThemeManager.Theme.LIGHT);

        themeGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) ThemeManager.setTheme((ThemeManager.Theme) val.getUserData());
        });

        HBox themeRow = new HBox(16, darkRb, lightRb);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        VBox displaySection = section("Display",
                row(keepAwakeCheck),
                row(hudLabel),
                row(hudRow),
                row(resLabel),
                row(resCol),
                row(resNote),
                row("Tabs:",  tabSideRow),
                row("Theme:", themeRow));

        // ── Gameplay ──────────────────────────────────────────────────────────
        VBox gameplaySection = section("Gameplay", comingSoon());

        // ── Account ───────────────────────────────────────────────────────────
        VBox accountSection = section("Account", comingSoon());

        // ── Save / Reset ──────────────────────────────────────────────────────
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("font-11");

        Button saveBtn = new Button("Save Settings");
        saveBtn.getStyleClass().add("btn-primary");

        Button resetBtn = new Button("Reset to Defaults");
        resetBtn.getStyleClass().add("btn-secondary");

        // ── Admin: Connection ─────────────────────────────────────────────────
        VBox connectionSection = null;
        TextField hostField = null;
        TextField portField = null;

        if (SessionStore.isAdmin()) {
            hostField = styledField(AppSettings.getServerHost());
            portField = styledField(String.valueOf(AppSettings.getServerPort()));

            Label restartNote = new Label("Connection changes take effect on next launch.");
            restartNote.getStyleClass().addAll("text-muted", "italic", "font-11");

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
            boolean leftNow = "LEFT".equalsIgnoreCase(AppSettings.getTabSide());
            tabTop.setSelected(!leftNow);
            tabLeft.setSelected(leftNow);
            if (finalHostField != null) finalHostField.setText(AppSettings.getServerHost());
            if (finalPortField != null) finalPortField.setText(String.valueOf(AppSettings.getServerPort()));
            setStatus(statusLabel, "Reset to current saved values.", true);
        });

        Button restartClientBtn = new Button("Restart Client");
        restartClientBtn.getStyleClass().add("btn-muted");
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
        content.getStyleClass().add("app-root");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-dark");

        return scroll;
    }

    // ── Section builder ───────────────────────────────────────────────────────

    private static VBox section(String title, Node... children) {
        Label header = new Label(title);
        header.setFont(Font.font("System", FontWeight.BOLD, 13));
        header.getStyleClass().add("section-title");

        Separator sep = new Separator();
        sep.getStyleClass().add("sep-dark");

        VBox body = new VBox(6);
        body.setPadding(new Insets(4, 0, 0, 0));
        body.getChildren().addAll(children);

        VBox box = new VBox(4, header, sep, body);
        box.setPadding(new Insets(14, 20, 8, 20));
        box.getStyleClass().add("app-root");
        return box;
    }

    /** A label + control pair on one row. */
    private static HBox row(String label, Control control) {
        return row(label, (Node) control);
    }

    /** A label + node pair on one row. */
    private static HBox row(String label, Node node) {
        Label lbl = new Label(label);
        lbl.setMinWidth(130);
        lbl.getStyleClass().addAll("text-secondary", "font-12");
        HBox row = new HBox(12, lbl, node);
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
        lbl.getStyleClass().addAll("text-muted", "italic", "font-11");
        return lbl;
    }

    // ── Styling ───────────────────────────────────────────────────────────────

    private static TextField styledField(String value) {
        TextField f = new TextField(value);
        f.setPrefWidth(220);
        f.getStyleClass().add("input-field-md");
        return f;
    }

    private static void setStatus(Label label, String msg, boolean success) {
        label.setText(msg);
        label.setStyle("-fx-font-size: 11; -fx-text-fill: " + (success ? "-af-success;" : "-af-error;"));
    }
}
