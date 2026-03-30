package com.game.bots.ui;

import com.game.bots.BotClient;
import com.game.bots.BotClient.State;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;

/**
 * Control panel for the bot test unit.
 *
 * Shows all bots in a table with live status, online time, and per-bot
 * start/stop buttons.  "Start All" / "Stop All" controls the whole group.
 */
public class BotControlPanel {

    private final Stage           stage;
    private final List<BotClient> bots;
    private final String          serverHost;
    private final int             serverPort;

    public BotControlPanel(Stage stage, List<BotClient> bots,
                           String serverHost, int serverPort) {
        this.stage      = stage;
        this.bots       = bots;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public void show() {

        // ── Header ───────────────────────────────────────────────────────────
        Label title = new Label("Bot Test Unit");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setTextFill(Color.web("#e0e0ff"));

        Label info = new Label(bots.size() + " bots  •  " + serverHost + ":" + serverPort);
        info.setStyle("-fx-text-fill: #707090; -fx-font-size: 11;");

        Button startAll = new Button("Start All");
        startAll.setStyle(btnStyle("#2a7a2a"));
        startAll.setOnAction(e -> bots.forEach(BotClient::start));

        Button stopAll = new Button("Stop All");
        stopAll.setStyle(btnStyle("#e94560"));
        stopAll.setOnAction(e -> bots.forEach(BotClient::stop));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, title, info, spacer, startAll, stopAll);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.setStyle("-fx-background-color: #0f0f1e;");

        // ── Status summary bar ────────────────────────────────────────────────
        Label summaryLabel = new Label();
        summaryLabel.setStyle("-fx-text-fill: #a0a0c0; -fx-font-size: 11;");
        HBox summary = new HBox(summaryLabel);
        summary.setPadding(new Insets(3, 14, 3, 14));
        summary.setStyle("-fx-background-color: #0f0f1e;");

        // ── Table ─────────────────────────────────────────────────────────────
        TableView<BotClient> table = new TableView<>();
        table.getItems().addAll(bots);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("""
                -fx-background-color: #1a1a2e;
                -fx-text-fill: #e0e0e0;
                -fx-table-cell-border-color: #2a2a4a;
                """);
        table.setPlaceholder(new Label("No bots configured"));

        // Bot name
        TableColumn<BotClient, String> nameCol = new TableColumn<>("Bot");
        nameCol.setPrefWidth(100);
        nameCol.setCellValueFactory(d ->
                new SimpleStringProperty(d.getValue().getUsername()));
        nameCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(empty ? "" : "-fx-text-fill: #e0e0e0; -fx-font-weight: bold;");
            }
        });

        // Status (colored)
        TableColumn<BotClient, Void> statusCol = new TableColumn<>("Status");
        statusCol.setPrefWidth(130);
        statusCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setText(null); setStyle("");
                } else {
                    State s = getTableView().getItems().get(getIndex()).getState();
                    setText(labelFor(s));
                    setStyle("-fx-text-fill: " + colorFor(s) + "; -fx-font-weight: bold;");
                }
            }
        });

        // Online duration
        TableColumn<BotClient, Void> timeCol = new TableColumn<>("Online For");
        timeCol.setPrefWidth(100);
        timeCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setText(null);
                } else {
                    BotClient bot   = getTableView().getItems().get(getIndex());
                    long      since = bot.getOnlineSince();
                    if (bot.getState() == State.ONLINE && since > 0) {
                        long secs = (System.currentTimeMillis() - since) / 1000;
                        setText(formatDuration(secs));
                        setStyle("-fx-text-fill: #80c080;");
                    } else {
                        setText("—");
                        setStyle("-fx-text-fill: #505060;");
                    }
                }
            }
        });

        // Per-bot start/stop button
        TableColumn<BotClient, Void> actionCol = new TableColumn<>("Control");
        actionCol.setPrefWidth(90);
        actionCol.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button();
            {
                btn.setOnAction(e -> {
                    if (getIndex() < 0 || getIndex() >= getTableView().getItems().size()) return;
                    BotClient bot = getTableView().getItems().get(getIndex());
                    if (bot.isActive()) bot.stop(); else bot.start();
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    BotClient bot = getTableView().getItems().get(getIndex());
                    if (bot.isActive()) {
                        btn.setText("Stop");
                        btn.setStyle(btnStyle("#e94560"));
                    } else {
                        btn.setText("Start");
                        btn.setStyle(btnStyle("#2a7a2a"));
                    }
                    setGraphic(btn);
                }
            }
        });

        table.getColumns().addAll(nameCol, statusCol, timeCol, actionCol);

        // ── Root ─────────────────────────────────────────────────────────────
        VBox root = new VBox(header, summary, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1a1a2e;");

        stage.setTitle("Bot Test Unit");
        stage.setScene(new Scene(root, 520, 420));
        stage.setResizable(true);
        stage.show();

        // ── Refresh table + summary every 500 ms ─────────────────────────────
        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            table.refresh();
            long online = bots.stream().filter(b -> b.getState() == State.ONLINE).count();
            long active = bots.stream().filter(BotClient::isActive).count();
            summaryLabel.setText(online + " online  •  " + active + " active  •  "
                    + (bots.size() - active) + " stopped");
        }));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();

        stage.setOnCloseRequest(e -> bots.forEach(BotClient::stop));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String labelFor(State s) {
        return switch (s) {
            case STOPPED     -> "Offline";
            case IDLE        -> "Idle";
            case REGISTERING -> "Registering...";
            case LOGGING_IN  -> "Connecting...";
            case ONLINE      -> "Online";
        };
    }

    private static String colorFor(State s) {
        return switch (s) {
            case STOPPED               -> "#505060";
            case IDLE                  -> "#c0a030";
            case REGISTERING,
                 LOGGING_IN            -> "#5090e0";
            case ONLINE                -> "#50c050";
        };
    }

    private static String formatDuration(long secs) {
        if (secs < 60)        return secs + "s";
        else if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        else                  return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }

    private static String btnStyle(String bg) {
        return "-fx-background-color: " + bg + "; -fx-text-fill: white; "
                + "-fx-font-size: 11; -fx-background-radius: 4; -fx-padding: 4 10 4 10;";
    }
}
