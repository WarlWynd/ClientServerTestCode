package com.game.server.ui;

import com.game.server.GameHandler;
import com.game.server.db.SoftwareVersionRepository;
import com.game.server.model.PlayerState;
import com.game.server.model.SoftwareVersion;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AdminWindow {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Stage                    stage;
    private final GameHandler              gameHandler;
    private final int                      serverPort;
    private final SoftwareVersionRepository versionRepo;

    // Players tab
    private final ObservableList<PlayerRow>  playerRows   = FXCollections.observableArrayList();
    private Label headerLabel;

    // Software Versions tab
    private final ObservableList<VersionRow> versionRows  = FXCollections.observableArrayList();
    private TableView<VersionRow>            versionTable;

    public AdminWindow(Stage stage, GameHandler gameHandler, int serverPort,
                       SoftwareVersionRepository versionRepo) {
        this.stage       = stage;
        this.gameHandler = gameHandler;
        this.serverPort  = serverPort;
        this.versionRepo = versionRepo;
    }

    public void show() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildPlayersTab(), buildVersionsTab());

        stage.setTitle("Server Monitor — Port " + serverPort);
        stage.setScene(new Scene(tabs, 720, 500));
        stage.show();

        Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshPlayers()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();

        refreshPlayers();
        refreshVersions();
    }

    // ── Players tab ───────────────────────────────────────────────────────────

    private Tab buildPlayersTab() {
        Text title = new Text("Server Monitor");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-fill: #e0e0e0;");

        headerLabel = new Label("Port " + serverPort + " | 0 players online");
        headerLabel.setStyle("-fx-text-fill: #a0a0c0;");

        VBox header = new VBox(4, title, headerLabel);
        header.setPadding(new Insets(16, 16, 8, 16));

        TableView<PlayerRow> table = new TableView<>(playerRows);
        table.setPlaceholder(new Label("No players connected"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-background-color: #16213e; -fx-text-fill: #e0e0e0;");
        table.getColumns().addAll(
                strCol("Username",    160, r -> r.username),
                strCol("Score",        70, r -> r.score),
                strCol("X",            60, r -> r.x),
                strCol("Y",            60, r -> r.y),
                strCol("Last Active", 120, r -> r.lastActive)
        );

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1a1a2e;");
        root.setTop(header);
        root.setCenter(table);
        BorderPane.setMargin(table, new Insets(0, 12, 12, 12));

        Tab tab = new Tab("Players", root);
        return tab;
    }

    private void refreshPlayers() {
        Map<String, PlayerState> players = gameHandler.getPlayers();
        playerRows.clear();
        for (PlayerState p : players.values()) playerRows.add(new PlayerRow(p));
        headerLabel.setText("Port %d  |  %d player%s online"
                .formatted(serverPort, players.size(), players.size() == 1 ? "" : "s"));
    }

    // ── Software Versions tab ─────────────────────────────────────────────────

    private Tab buildVersionsTab() {
        versionTable = new TableView<>(versionRows);
        versionTable.setPlaceholder(new Label("No version entries yet."));
        versionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        versionTable.setStyle("-fx-background-color: #16213e;");

        versionTable.getColumns().addAll(
                strCol("Released",      90,  r -> r.releasedAt),
                strCol("Server Ver.",   90,  r -> r.serverVersion),
                strCol("Client Ver.",   90,  r -> r.clientVersion),
                strCol("Changes",       300, r -> r.changes)
        );

        Button addBtn    = toolBtn("Add");
        Button editBtn   = toolBtn("Edit");
        Button deleteBtn = toolBtn("Delete");
        Button refreshBtn = toolBtn("Refresh");

        editBtn.setDisable(true);
        deleteBtn.setDisable(true);

        versionTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            editBtn.setDisable(sel == null);
            deleteBtn.setDisable(sel == null);
        });

        addBtn.setOnAction(e -> showVersionDialog(null));
        editBtn.setOnAction(e -> {
            VersionRow sel = versionTable.getSelectionModel().getSelectedItem();
            if (sel != null) showVersionDialog(sel.source);
        });
        deleteBtn.setOnAction(e -> {
            VersionRow sel = versionTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete version entry " + sel.source.getServerVersion()
                    + " / " + sel.source.getClientVersion() + "?",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("Confirm Delete");
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    versionRepo.delete(sel.source.getId());
                    refreshVersions();
                }
            });
        });
        refreshBtn.setOnAction(e -> refreshVersions());

        HBox toolbar = new HBox(8, addBtn, editBtn, deleteBtn, refreshBtn);
        toolbar.setPadding(new Insets(10));
        toolbar.setStyle("-fx-background-color: #0f0f1e;");

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1a1a2e;");
        root.setTop(toolbar);
        root.setCenter(versionTable);
        BorderPane.setMargin(versionTable, new Insets(0, 12, 12, 12));

        return new Tab("Software Versions", root);
    }

    private void refreshVersions() {
        List<SoftwareVersion> list = versionRepo.findAll();
        versionRows.clear();
        for (SoftwareVersion v : list) versionRows.add(new VersionRow(v));
    }

    private void showVersionDialog(SoftwareVersion existing) {
        boolean isEdit = existing != null;

        Dialog<SoftwareVersion> dialog = new Dialog<>();
        dialog.setTitle(isEdit ? "Edit Version Entry" : "Add Version Entry");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField serverVerField = field(isEdit ? existing.getServerVersion() : "");
        TextField clientVerField = field(isEdit ? existing.getClientVersion() : "");
        DatePicker datePicker    = new DatePicker(isEdit ? existing.getReleasedAt() : LocalDate.now());
        datePicker.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: #e0e0e0;");
        TextArea changesArea = new TextArea(isEdit ? existing.getChanges() : "");
        changesArea.setPrefRowCount(5);
        changesArea.setWrapText(true);
        changesArea.setStyle("-fx-control-inner-background: #0f0f2a; -fx-text-fill: #e0e0e0;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, lbl("Server Version:"), serverVerField);
        grid.addRow(1, lbl("Client Version:"), clientVerField);
        grid.addRow(2, lbl("Release Date:"),   datePicker);
        grid.addRow(3, lbl("Changes:"),        changesArea);
        GridPane.setHgrow(serverVerField, Priority.ALWAYS);
        GridPane.setHgrow(clientVerField, Priority.ALWAYS);
        GridPane.setHgrow(changesArea,    Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setStyle("-fx-background-color: #1a1a2e;");

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            SoftwareVersion v = isEdit ? existing : new SoftwareVersion();
            v.setServerVersion(serverVerField.getText().trim());
            v.setClientVersion(clientVerField.getText().trim());
            v.setReleasedAt(datePicker.getValue() != null ? datePicker.getValue() : LocalDate.now());
            v.setChanges(changesArea.getText().trim());
            return v;
        });

        Optional<SoftwareVersion> result = dialog.showAndWait();
        result.ifPresent(v -> {
            if (isEdit) versionRepo.update(v);
            else        versionRepo.insert(v);
            refreshVersions();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Button toolBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #2a2a4e; -fx-text-fill: #e0e0e0; -fx-background-radius: 4; -fx-cursor: hand;");
        return b;
    }

    private TextField field(String value) {
        TextField tf = new TextField(value);
        tf.setStyle("-fx-control-inner-background: #0f0f2a; -fx-text-fill: #e0e0e0;");
        return tf;
    }

    private Label lbl(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #a0a0c0;");
        return l;
    }

    @FunctionalInterface interface RowProp<T> { StringProperty get(T r); }

    private <T> TableColumn<T, String> strCol(String title, double width, RowProp<T> prop) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> prop.get(cd.getValue()));
        col.setStyle("-fx-alignment: CENTER-LEFT;");
        return col;
    }

    // ── Row models ────────────────────────────────────────────────────────────

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

    static class VersionRow {
        final SoftwareVersion source;
        final StringProperty  releasedAt    = new SimpleStringProperty();
        final StringProperty  serverVersion = new SimpleStringProperty();
        final StringProperty  clientVersion = new SimpleStringProperty();
        final StringProperty  changes       = new SimpleStringProperty();

        VersionRow(SoftwareVersion v) {
            source = v;
            releasedAt.set(v.getReleasedAt() != null ? v.getReleasedAt().format(DATE_FMT) : "");
            serverVersion.set(v.getServerVersion());
            clientVersion.set(v.getClientVersion());
            // Show first line only in the table; full text visible in edit dialog
            String ch = v.getChanges();
            changes.set(ch.contains("\n") ? ch.substring(0, ch.indexOf('\n')) + " …" : ch);
        }
    }
}
