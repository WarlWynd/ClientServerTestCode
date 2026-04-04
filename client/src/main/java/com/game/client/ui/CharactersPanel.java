package com.game.client.ui;

import com.game.client.SessionStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Characters tab — shown to all users.
 * Displays the current character's details, with room for future fields
 * (class, race, stats, etc.).
 */
public class CharactersPanel {

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
            Label none = new Label("No character found.");
            none.setStyle("-fx-text-fill: #808080;");
            root.getChildren().add(none);
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
