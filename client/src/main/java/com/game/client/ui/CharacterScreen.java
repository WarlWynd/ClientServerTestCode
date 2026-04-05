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
import javafx.stage.Stage;

/**
 * Standalone character screen shown after login.
 *
 * Two modes:
 *   • Creation form — when the user has no character yet.
 *   • Summary view  — when the user already has a character.
 */
public class CharacterScreen {

    private final Stage     stage;
    private final UDPClient client;

    private TextField nameField;
    private Label     statusLabel;
    private Button    actionBtn;

    public CharacterScreen(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);
        String existing = SessionStore.getCharacterName();
        if (existing != null && !existing.isBlank()) {
            showSummary(existing);
        } else {
            showCreationForm();
        }
    }

    // ── Summary (existing character) ─────────────────────────────────────────

    private void showSummary(String charName) {
        Label pageTitle = new Label("Your Character");
        pageTitle.setFont(Font.font("System", FontWeight.BOLD, 22));
        pageTitle.getStyleClass().add("text-primary");

        VBox details = new VBox(10,
                summaryField("Character Name", charName),
                summaryField("Race",           "—"),
                summaryField("Class",          "—")
        );
        details.setPadding(new Insets(16));
        details.getStyleClass().add("app-card");

        Button enterBtn = new Button("Enter Game  ▶");
        enterBtn.setDefaultButton(true);
        enterBtn.setMaxWidth(Double.MAX_VALUE);
        enterBtn.getStyleClass().add("btn-enter-game");
        enterBtn.setOnAction(e -> new GameScreen(stage, client).show());

        VBox content = new VBox(20, pageTitle, details, enterBtn);
        content.setPadding(new Insets(40));
        content.setMaxWidth(460);
        content.setAlignment(Pos.TOP_CENTER);

        StackPane root = new StackPane(content);
        root.getStyleClass().add("app-root");
        StackPane.setAlignment(content, Pos.CENTER);

        Scene scene = new Scene(root, 520, 400);
        ThemeManager.apply(scene);
        stage.setTitle(AppSettings.getProgramName() + " — Character");
        stage.setScene(scene);
    }

    private VBox summaryField(String label, String value) {
        Label lbl = new Label(label.toUpperCase());
        lbl.getStyleClass().addAll("text-muted", "font-10");

        Label val = new Label(value);
        val.getStyleClass().addAll("text-primary", "bold", "font-14");

        VBox box = new VBox(2, lbl, val);
        box.setPadding(new Insets(0, 0, 4, 0));
        return box;
    }

    // ── Creation form ────────────────────────────────────────────────────────

    private void showCreationForm() {
        Label pageTitle = new Label("Create Your Character");
        pageTitle.setFont(Font.font("System", FontWeight.BOLD, 22));
        pageTitle.getStyleClass().add("text-primary");

        Label subtitle = new Label("Fill in the details below to create your character.");
        subtitle.getStyleClass().addAll("text-secondary", "font-12");

        // ── Section: Identity ─────────────────────────────────────────────
        VBox identitySection = section("Identity",
                formRow("Character Name *",
                        nameField = styledField("Enter a unique name (2–50 characters)", false),
                        null));

        // ── Section: Origin ───────────────────────────────────────────────
        VBox originSection = section("Origin",
                formRow("Race",       lockedField("Coming soon"), comingSoonBadge()),
                formRow("Class",      lockedField("Coming soon"), comingSoonBadge()),
                formRow("Background", lockedField("Coming soon"), comingSoonBadge())
        );

        // ── Section: Appearance ───────────────────────────────────────────
        VBox appearanceSection = section("Appearance",
                formRow("Gender",     lockedField("Coming soon"), comingSoonBadge()),
                formRow("Hair Style", lockedField("Coming soon"), comingSoonBadge()),
                formRow("Hair Color", lockedField("Coming soon"), comingSoonBadge())
        );

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        actionBtn = new Button("Create Character");
        actionBtn.setDefaultButton(true);
        actionBtn.setMaxWidth(Double.MAX_VALUE);
        actionBtn.getStyleClass().add("btn-enter-game");
        actionBtn.setOnAction(e -> doCreate());
        nameField.setOnAction(e -> doCreate());

        VBox form = new VBox(16,
                pageTitle, subtitle,
                identitySection,
                originSection,
                appearanceSection,
                statusLabel,
                actionBtn);
        form.setPadding(new Insets(30, 40, 30, 40));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-dark");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Scene scene = new Scene(scroll, 560, 620);
        ThemeManager.apply(scene);
        stage.setTitle(AppSettings.getProgramName() + " — Create Character");
        stage.setScene(scene);
    }

    // ── Form helpers ─────────────────────────────────────────────────────────

    /** Titled card containing one or more form rows. */
    private VBox section(String title, HBox... rows) {
        Label hdr = new Label(title.toUpperCase());
        hdr.getStyleClass().addAll("text-secondary", "bold", "font-10");

        Separator sep = new Separator();
        sep.getStyleClass().add("sep-dark");

        VBox body = new VBox(10);
        body.getChildren().add(hdr);
        body.getChildren().add(sep);
        for (HBox row : rows) body.getChildren().add(row);
        body.setPadding(new Insets(14));
        body.getStyleClass().add("app-card");
        return body;
    }

    /** A label + control row, with optional trailing badge. */
    private HBox formRow(String labelText, Control field, Label badge) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().addAll("text-secondary", "font-12");
        lbl.setMinWidth(130);

        HBox row = new HBox(10, lbl, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        if (badge != null) row.getChildren().add(badge);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private TextField styledField(String prompt, boolean locked) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setDisable(locked);
        f.getStyleClass().add("input-field");
        return f;
    }

    private TextField lockedField(String prompt) {
        TextField f = styledField(prompt, true);
        f.setStyle("-fx-opacity: 0.45;");
        return f;
    }

    private Label comingSoonBadge() {
        Label badge = new Label("Soon™");
        badge.getStyleClass().add("badge-muted");
        return badge;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void doCreate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            status("Please enter a character name.", false);
            return;
        }
        actionBtn.setDisable(true);
        status("Creating character…", true);
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("characterName", name);
        client.send(new Packet(PacketType.CHARACTER_CREATE_REQUEST, SessionStore.getToken(), payload));
    }

    private void onPacket(Packet packet) {
        Platform.runLater(() -> {
            if (packet.type != PacketType.CHARACTER_CREATE_RESPONSE) return;

            boolean success = packet.payload.get("success").asBoolean();
            String  message = packet.payload.get("message").asText();

            if (success) {
                SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                showSummary(SessionStore.getCharacterName());
            } else if (packet.payload.has("characterName")) {
                SessionStore.setCharacterName(packet.payload.get("characterName").asText());
                showSummary(SessionStore.getCharacterName());
            } else {
                status(message, false);
                if (actionBtn != null) actionBtn.setDisable(false);
            }
        });
    }

    private void status(String msg, boolean ok) {
        if (statusLabel != null) {
            statusLabel.setText(msg);
            statusLabel.setStyle(ok ? "-fx-text-fill: -af-success;" : "-fx-text-fill: -af-error;");
        }
    }
}
