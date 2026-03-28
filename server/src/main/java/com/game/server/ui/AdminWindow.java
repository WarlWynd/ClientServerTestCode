package com.game.server.ui;

import com.game.server.GameHandler;
import com.game.server.model.PlayerState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class AdminWindow {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Stage       stage;
    private final GameHandler gameHandler;
    private final int         serverPort;

    private final ObservableList<PlayerRow> rows = FXCollections.observableArrayList();
    private Label headerLabel;

    public AdminWindow(Stage stage, GameHandler gameHandler, int serverPort) {
        this.stage       = stage;
        this.gameHandler = gameHandler;
        this.serverPort  = serverPort;
    }

    public void show() {
        // ── Header ────────────────────────────────────────────────────────────
        Text title = new Text("Server Monitor");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-fill: #e0e0e0;");

        headerLabel = new Label("Port " + serverPort + " | 0 players online");
        headerLabel.setStyle("-fx-text-fill: #a0a0c0;");

        VBox header = new VBox(4, title, headerLabel);
        header.setPadding(new Insets(16, 16, 8, 16));

        // ── Table ─────────────────────────────────────────────────────────────
        TableView<PlayerRow> table = new TableView<>(rows);
        table.setPlaceholder(new Label("No players connected"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-background-color: #16213e; -fx-text-fill: #e0e0e0;");

        table.getColumns().addAll(
                col("Username",    160, r -> r.username),
                col("Score",        70, r -> r.score),
                col("X",            60, r -> r.x),
                col("Y",            60, r -> r.y),
                col("Last Active", 120, r -> r.lastActive)
        );

        // ── Layout ────────────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1a1a2e;");
        root.setTop(header);
        root.setCenter(table);
        BorderPane.setMargin(table, new Insets(0, 12, 12, 12));

        stage.setTitle("Server Monitor — Port " + serverPort);
        stage.setScene(new Scene(root, 620, 420));
        stage.show();

        // ── Refresh every second ──────────────────────────────────────────────
        Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();

        refresh();
    }

    private void refresh() {
        Map<String, PlayerState> players = gameHandler.getPlayers();
        rows.clear();
        for (PlayerState p : players.values()) {
            rows.add(new PlayerRow(p));
        }
        headerLabel.setText(String.format("Port %d  |  %d player%s online",
                serverPort, players.size(), players.size() == 1 ? "" : "s"));
    }

    // ── Table row model ───────────────────────────────────────────────────────

    static class PlayerRow {
        final StringProperty username   = new SimpleStringProperty();
        final StringProperty score      = new SimpleStringProperty();
        final StringProperty x          = new SimpleStringProperty();
        final StringProperty y          = new SimpleStringProperty();
        final StringProperty lastActive = new SimpleStringProperty();

        PlayerRow(PlayerState p) {
            long secondsAgo = (System.currentTimeMillis() - p.lastSeen) / 1000;
            username.set(p.username);
            score.set(String.valueOf(p.score));
            x.set(String.format("%.0f", p.x));
            y.set(String.format("%.0f", p.y));
            lastActive.set(secondsAgo < 5 ? "now" : secondsAgo + "s ago");
        }
    }

    // ── Helper: build a typed column ─────────────────────────────────────────

    @FunctionalInterface
    interface RowProp { StringProperty get(PlayerRow r); }

    private TableColumn<PlayerRow, String> col(String title, double width, RowProp prop) {
        TableColumn<PlayerRow, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> prop.get(cd.getValue()));
        col.setStyle("-fx-alignment: CENTER-LEFT;");
        return col;
    }
}
