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
        title.getStyleClass().add("text-primary");

        Separator sep = new Separator();
        sep.getStyleClass().add("sep-dark");

        VBox root = new VBox(12, title, sep);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("app-root");
        root.setAlignment(Pos.TOP_LEFT);

        String charName = SessionStore.getCharacterName();
        if (charName != null && !charName.isBlank()) {
            root.getChildren().add(field("Character Name", charName));
            // Future fields (class, race, etc.) will be added here
        } else {
            Label none = new Label("You don't have a character yet.");
            none.getStyleClass().add("text-muted");

            Button createBtn = new Button("Create Character");
            createBtn.getStyleClass().add("btn-enter-game");
            createBtn.setOnAction(e -> new CharacterCreationScreen(stage, client).show());

            root.getChildren().addAll(none, createBtn);
        }

        return root;
    }

    private VBox field(String label, String value) {
        Label lbl = new Label(label.toUpperCase());
        lbl.getStyleClass().addAll("text-muted", "font-10");

        Label val = new Label(value);
        val.getStyleClass().addAll("text-primary", "bold", "font-14");

        VBox box = new VBox(2, lbl, val);
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }
}
