package com.game.client.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Admin-only Game Settings panel — shown as a tab in GameScreen for admin users.
 * Controls server-side and gameplay parameters such as gravity.
 */
public class GameSettingsPanel {

    private final UDPClient client;

    public GameSettingsPanel(UDPClient client) {
        this.client = client;
    }

    public Node buildView() {

        // ── Gravity ───────────────────────────────────────────────────────────
        Label gravityDesc = new Label(
                "Gravity pulls characters toward the floor each frame.\n" +
                "Higher values = faster fall. 0 = no gravity.");
        gravityDesc.getStyleClass().addAll("text-muted", "font-11");
        gravityDesc.setWrapText(true);

        Label gravityLbl = new Label("Gravity strength:");
        gravityLbl.setMinWidth(140);
        gravityLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField gravityField = new TextField(String.valueOf(AppSettings.getGravity()));
        gravityField.setPrefWidth(80);
        gravityField.getStyleClass().add("input-field-md");

        HBox gravityRow = new HBox(12, gravityLbl, gravityField);
        gravityRow.setAlignment(Pos.CENTER_LEFT);

        VBox gravitySection = section("Gravity", gravityDesc, gravityRow);

        // ── Jump ──────────────────────────────────────────────────────────────
        Label jumpDesc = new Label(
                "Jump strength controls the upward velocity applied when a character jumps.\n" +
                "Higher values = higher jump.");
        jumpDesc.getStyleClass().addAll("text-muted", "font-11");
        jumpDesc.setWrapText(true);

        Label jumpLbl = new Label("Jump strength:");
        jumpLbl.setMinWidth(140);
        jumpLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField jumpField = new TextField(String.valueOf(AppSettings.getJumpStrength()));
        jumpField.setPrefWidth(80);
        jumpField.getStyleClass().add("input-field-md");

        HBox jumpRow = new HBox(12, jumpLbl, jumpField);
        jumpRow.setAlignment(Pos.CENTER_LEFT);

        VBox jumpSection = section("Jump", jumpDesc, jumpRow);

        // ── Run Speed ─────────────────────────────────────────────────────────
        Label speedDesc = new Label(
                "Run speed controls how fast characters move left and right.\n" +
                "Higher values = faster movement.");
        speedDesc.getStyleClass().addAll("text-muted", "font-11");
        speedDesc.setWrapText(true);

        Label speedLbl = new Label("Run speed:");
        speedLbl.setMinWidth(140);
        speedLbl.getStyleClass().addAll("text-secondary", "font-12");

        TextField speedField = new TextField(String.valueOf(AppSettings.getRunSpeed()));
        speedField.setPrefWidth(80);
        speedField.getStyleClass().add("input-field-md");

        HBox speedRow = new HBox(12, speedLbl, speedField);
        speedRow.setAlignment(Pos.CENTER_LEFT);

        VBox speedSection = section("Run Speed", speedDesc, speedRow);

        // ── Save / status ─────────────────────────────────────────────────────
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("font-11");

        Button saveBtn = new Button("Test Locally");
        saveBtn.setPrefWidth(140);
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> {
            float[] vals = parseFields(gravityField, jumpField, speedField, statusLabel);
            if (vals != null) {
                AppSettings.setGravity(vals[0]);
                AppSettings.setJumpStrength(vals[1]);
                AppSettings.setRunSpeed(vals[2]);
                boolean ok = AppSettings.save();
                setStatus(statusLabel, ok ? "Saved locally (not committed to server)." : "Could not write settings file.", ok);
            }
        });

        Button commitBtn = new Button("Commit to Server");
        commitBtn.setPrefWidth(160);
        commitBtn.getStyleClass().add("btn-warning");
        commitBtn.setOnAction(e -> {
            float[] vals = parseFields(gravityField, jumpField, speedField, statusLabel);
            if (vals != null) {
                AppSettings.setGravity(vals[0]);
                AppSettings.setJumpStrength(vals[1]);
                AppSettings.setRunSpeed(vals[2]);
                AppSettings.save();
                ObjectNode payload = PacketSerializer.mapper().createObjectNode();
                payload.put("gravity",      vals[0]);
                payload.put("jumpStrength", vals[1]);
                payload.put("runSpeed",     vals[2]);
                client.send(new Packet(PacketType.ADMIN_SAVE_SETTINGS_REQUEST,
                        SessionStore.getToken(), payload));
                setStatus(statusLabel, "Sending to server…", true);
            }
        });

        HBox buttons = new HBox(10, saveBtn, commitBtn, statusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(16, 20, 20, 20));

        VBox content = new VBox(gravitySection, jumpSection, speedSection, buttons);
        content.getStyleClass().add("app-root");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-dark");
        return scroll;
    }

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

    /** Parses and validates all three fields. Returns [gravity, jump, speed] or null on error. */
    private static float[] parseFields(TextField gravityField, TextField jumpField,
                                       TextField speedField, Label statusLabel) {
        float g, j, s;
        try {
            g = Float.parseFloat(gravityField.getText().trim());
            if (g < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus(statusLabel, "Invalid gravity — enter a positive number.", false);
            return null;
        }
        try {
            j = Float.parseFloat(jumpField.getText().trim());
            if (j < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus(statusLabel, "Invalid jump value — enter a positive number.", false);
            return null;
        }
        try {
            s = Float.parseFloat(speedField.getText().trim());
            if (s < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus(statusLabel, "Invalid run speed — enter a positive number.", false);
            return null;
        }
        return new float[]{g, j, s};
    }

    private static void setStatus(Label label, String msg, boolean success) {
        label.setText(msg);
        label.setStyle("-fx-font-size: 11; -fx-text-fill: " + (success ? "-af-success;" : "-af-error;"));
    }
}
