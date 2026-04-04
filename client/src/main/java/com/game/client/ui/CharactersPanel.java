package com.game.client.ui;

import com.game.client.SessionStore;
import com.game.client.UDPClient;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

/**
 * Characters tab — shown to all users.
 * Displays the current character's details, with room for future fields
 * (class, race, stats, etc.).
 * If the user has no character, offers a button to create one.
 */
public class CharactersPanel {

    private final Stage     stage;
    private final UDPClient client;

    public CharactersPanel(Stage stage, UDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public Node buildView() {
        Label title = new Label("My Character");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setTextFill(Color.web("#e0e0ff"));

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #2a2a4a;");

        VBox root = new VBox(12, title, sep);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1a1a2e;");
        root.setAlignment(Pos.TOP_LEFT);

        String charName = SessionStore.getCharacterName();
        if (charName != null && !charName.isBlank()) {
            root.getChildren().add(field("Character Name", charName));
            // Future fields (class, race, etc.) will be added here
        } else {
            Label none = new Label("You don't have a character yet.");
            none.setStyle("-fx-text-fill: #808080;");

            Button createBtn = new Button("Create Character");
            createBtn.setStyle("""
                    -fx-background-color: #e94560;
                    -fx-text-fill: white;
                    -fx-font-weight: bold;
                    -fx-background-radius: 4;
                    -fx-padding: 10 20 10 20;
                    """);
            createBtn.setOnAction(e -> new CharacterCreationScreen(stage, client).show());

            root.getChildren().addAll(none, createBtn);
        }

        return root;
    }

    private VBox field(String label, String value) {
        Label lbl = new Label(label.toUpperCase());
        lbl.setStyle("-fx-text-fill: #606080; -fx-font-size: 10;");

        Label val = new Label(value);
        val.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 14; -fx-font-weight: bold;");

        VBox box = new VBox(2, lbl, val);
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }
}
