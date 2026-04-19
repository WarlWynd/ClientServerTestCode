package com.game.admin.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.admin.AdminSession;
import com.game.admin.AdminUDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
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

public class DashboardScreen {

    private final Stage          stage;
    private final AdminUDPClient client;

    private Label  headerLabel;
    private Label  statusLabel;
    private final ObservableList<PlayerRow> rows         = FXCollections.observableArrayList();
    private final FilteredList<PlayerRow>   filteredRows = new FilteredList<>(rows, p -> true);
    private Timeline ticker;

    public DashboardScreen(Stage stage, AdminUDPClient client) {
        this.stage  = stage;
        this.client = client;
    }

    public void show() {
        client.setPacketListener(this::onPacket);

        TabPane tabs = new TabPane(
                buildPlayersTab(),
                buildItemDevTab(),
                buildSpellDevTab(),
                buildQuestDevTab()
        );
        tabs.getStyleClass().add("tab-pane-dark");
        tabs.setTabMinWidth(110);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox root = new VBox(tabs);
        root.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Scene scene = new Scene(root);
        applyDarkTheme(scene);

        stage.setScene(scene);
        stage.setTitle("Admin Console — " + AdminSession.getUsername());
        stage.show();

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> requestPlayerList()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
        requestPlayerList();
    }

    // ── Players tab ───────────────────────────────────────────────────────────

    private Tab buildPlayersTab() {
        headerLabel = new Label("Logged in as: " + AdminSession.getUsername());
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        headerLabel.setTextFill(Color.web("#e0e0ff"));

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.setTextFill(Color.web("#80c080"));

        Button logoutBtn = new Button("Logout");
        logoutBtn.setStyle("-fx-background-color: #e94560; -fx-text-fill: white;" +
                           "-fx-font-size: 11; -fx-background-radius: 4; -fx-padding: 5 12 5 12;");
        logoutBtn.setOnAction(e -> doLogout());

        // Search bar
        TextField searchField = new TextField();
        searchField.setPromptText("Search players…");
        searchField.setPrefWidth(220);
        searchField.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
                             "-fx-border-color: #3a3a6a; -fx-border-radius: 4; -fx-padding: 5;");

        ComboBox<String> searchCombo = new ComboBox<>();
        searchCombo.getItems().addAll("Username", "Email", "Character Name", "IP");
        searchCombo.setValue("Username");
        searchCombo.getStyleClass().add("combo-dark");

        searchField.textProperty().addListener((obs, o, n) -> applyFilter(searchField, searchCombo));
        searchCombo.valueProperty().addListener((obs, o, n) -> applyFilter(searchField, searchCombo));

        Button clearBtn = new Button("✕");
        clearBtn.setStyle("-fx-background-color: #3a1a1a; -fx-text-fill: white;" +
                          "-fx-font-size: 11; -fx-padding: 5 8 5 8; -fx-background-radius: 4;");
        clearBtn.setOnAction(e -> searchField.clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(12, headerLabel, spacer, logoutBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 14, 6, 14));
        topBar.setStyle("-fx-background-color: #0f0f1e;");

        HBox searchBar = new HBox(6, new Label("Search:"), searchCombo, searchField, clearBtn);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(5, 14, 5, 14));
        searchBar.setStyle("-fx-background-color: #0f0f1e;");
        for (var lbl : searchBar.getChildren()) {
            if (lbl instanceof Label l) l.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 12;");
        }

        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(2, 14, 4, 14));
        statusBar.setStyle("-fx-background-color: #0f0f1e;");

        // Table
        TableView<PlayerRow> table = new TableView<>(filteredRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getStyleClass().add("dark-table");
        table.setPlaceholder(new Label("No players connected"));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().addAll(
                strCol("Username",       "username",      150),
                strCol("Email",          "email",         200),
                strCol("Character Name", "characterName", 140),
                strCol("IP",             "ip",            120),
                strCol("Connected",      "connectedTime",  90),
                strCol("Score",          "score",           60),
                strCol("X",              "x",               55),
                strCol("Y",              "y",               55),
                adminCol(),
                kickCol(),
                banCol()
        );

        VBox content = new VBox(topBar, searchBar, statusBar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.setStyle("-fx-background-color: #1a1a2e;");

        Tab tab = new Tab("👥  Players", content);
        tab.setClosable(false);
        return tab;
    }

    // ── Dev tabs ──────────────────────────────────────────────────────────────

    private Tab buildItemDevTab() {
        Tab lootTab = new Tab("📦 Loot Tables",  new LootTablePanel().build());
        Tab itemTab = new Tab("🗡 Item Registry", new ItemRegistryPanel().build());
        lootTab.setClosable(false);
        itemTab.setClosable(false);
        TabPane inner = new TabPane(lootTab, itemTab);
        inner.getStyleClass().add("tab-pane-dark");
        inner.setTabMinWidth(120);
        VBox.setVgrow(inner, Priority.ALWAYS);
        Tab tab = new Tab("🗡  Item Dev", inner);
        tab.setClosable(false);
        return tab;
    }

    private Tab buildSpellDevTab() {
        Tab tab = new Tab("✨  Spell Dev", new SpellLibraryPanel().build());
        tab.setClosable(false);
        return tab;
    }

    private Tab buildQuestDevTab() {
        Tab tab = new Tab("📜  Quest Dev", new QuestDevPanel().build());
        tab.setClosable(false);
        return tab;
    }

    // ── Table helpers ─────────────────────────────────────────────────────────

    private void applyFilter(TextField searchField, ComboBox<String> searchCombo) {
        String query = searchField.getText().trim().toLowerCase();
        String field = searchCombo.getValue();
        if (query.isEmpty()) { filteredRows.setPredicate(p -> true); return; }
        filteredRows.setPredicate(row -> {
            String val = switch (field) {
                case "Email"          -> row.email.get();
                case "Character Name" -> row.characterName.get();
                case "IP"             -> row.ip.get();
                default               -> row.username.get();
            };
            return val != null && val.toLowerCase().contains(query);
        });
    }

    private TableColumn<PlayerRow, String> strCol(String title, String field, double width) {
        TableColumn<PlayerRow, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(data -> {
            PlayerRow r = data.getValue();
            return switch (field) {
                case "username"      -> r.username;
                case "email"         -> r.email;
                case "characterName" -> r.characterName;
                case "ip"            -> r.ip;
                case "connectedTime" -> r.connectedTime;
                case "score"         -> r.score;
                case "x"             -> r.x;
                case "y"             -> r.y;
                default              -> new SimpleStringProperty("");
            };
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> adminCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Admin");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button();
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                if (row.username.get().equals(AdminSession.getUsername())) { setGraphic(null); return; }
                btn.setText(row.isAdmin ? "Revoke" : "Grant");
                btn.setStyle("-fx-background-color: " + (row.isAdmin ? "#7b241c" : "#1e5f3a") +
                             "; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 3 8 3 8; -fx-background-radius: 3;");
                btn.setOnAction(e -> doSetAdmin(row.username.get(), !row.isAdmin));
                setGraphic(btn);
            }
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> kickCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Action");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("Kick");
            { btn.setStyle("-fx-background-color: #e94560; -fx-text-fill: white;" +
                           "-fx-font-size: 11; -fx-padding: 3 8 3 8; -fx-background-radius: 3;");
              btn.setOnAction(e -> doKick(getTableView().getItems().get(getIndex()).username.get())); }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                setGraphic(row.username.get().equals(AdminSession.getUsername()) ? null : btn);
            }
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> banCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Ban");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("Ban");
            { btn.setStyle("-fx-background-color: #a03030; -fx-text-fill: white;" +
                           "-fx-font-size: 11; -fx-padding: 3 8 3 8; -fx-background-radius: 3;");
              btn.setOnAction(e -> doBan(getTableView().getItems().get(getIndex()).username.get())); }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                setGraphic(row.username.get().equals(AdminSession.getUsername()) ? null : btn);
            }
        });
        return col;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void requestPlayerList() {
        client.send(new Packet(PacketType.ADMIN_USER_LIST_REQUEST,
                AdminSession.getToken(), PacketSerializer.emptyPayload()));
    }

    private void doKick(String username) {
        ObjectNode p = PacketSerializer.mapper().createObjectNode();
        p.put("username", username);
        client.send(new Packet(PacketType.ADMIN_KICK_REQUEST, AdminSession.getToken(), p));
    }

    private void doBan(String username) {
        ObjectNode p = PacketSerializer.mapper().createObjectNode();
        p.put("username", username);
        p.put("ban", true);
        client.send(new Packet(PacketType.ADMIN_BAN_REQUEST, AdminSession.getToken(), p));
    }

    private void doSetAdmin(String username, boolean isAdmin) {
        ObjectNode p = PacketSerializer.mapper().createObjectNode();
        p.put("username", username);
        p.put("isAdmin",  isAdmin);
        client.send(new Packet(PacketType.ADMIN_SET_ADMIN_REQUEST, AdminSession.getToken(), p));
    }

    private void doLogout() {
        ticker.stop();
        ObjectNode p = PacketSerializer.emptyPayload();
        client.send(new Packet(PacketType.LOGOUT_REQUEST, AdminSession.getToken(), p));
        AdminSession.clear();
        new LoginScreen(stage, client).show();
    }

    private void onPacket(Packet packet) {
        switch (packet.type) {
            case ADMIN_USER_LIST_RESPONSE -> Platform.runLater(() -> {
                if (!packet.payload.get("success").asBoolean()) return;
                JsonNode players = packet.payload.get("players");
                rows.clear();
                if (players != null && players.isArray()) {
                    for (JsonNode p : players) {
                        rows.add(new PlayerRow(
                                p.has("username")      ? p.get("username").asText()      : "—",
                                p.has("email")         ? p.get("email").asText()         : "—",
                                p.has("characterName") ? p.get("characterName").asText() : "—",
                                p.has("ip")            ? p.get("ip").asText()            : "—",
                                p.get("joinedAt").asLong(),
                                p.get("x").asDouble(),
                                p.get("y").asDouble(),
                                p.get("score").asInt(),
                                p.has("isAdmin") && p.get("isAdmin").asBoolean()));
                    }
                }
                headerLabel.setText(String.format("Logged in as: %s  |  %d player%s online",
                        AdminSession.getUsername(), rows.size(), rows.size() == 1 ? "" : "s"));
            });
            case ADMIN_KICK_RESPONSE -> Platform.runLater(() -> {
                boolean ok = packet.payload.get("success").asBoolean();
                String  msg = ok ? "Kicked: " + packet.payload.get("username").asText()
                                 : "Kick failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_BAN_RESPONSE -> Platform.runLater(() -> {
                boolean ok = packet.payload.get("success").asBoolean();
                String  msg = ok ? "Banned: " + packet.payload.get("username").asText()
                                 : "Ban failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_SET_ADMIN_RESPONSE -> Platform.runLater(() -> {
                boolean ok = packet.payload.get("success").asBoolean();
                String msg = ok
                        ? (packet.payload.get("isAdmin").asBoolean() ? "Granted admin: " : "Revoked admin: ")
                                + packet.payload.get("username").asText()
                        : "Admin change failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ERROR -> Platform.runLater(() ->
                    showStatus("Error: " + packet.payload.get("message").asText(), false));
        }
    }

    private void showStatus(String msg, boolean ok) {
        statusLabel.setTextFill(Color.web(ok ? "#80c080" : "#e94560"));
        statusLabel.setText(msg);
        PauseTransition clear = new PauseTransition(Duration.seconds(4));
        clear.setOnFinished(e -> statusLabel.setText(""));
        clear.play();
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private static void applyDarkTheme(Scene scene) {
        try {
            var base = DashboardScreen.class.getResource("/styles/base.css");
            var dark = DashboardScreen.class.getResource("/styles/dark.css");
            if (base != null) scene.getStylesheets().add(base.toExternalForm());
            if (dark != null) scene.getStylesheets().add(dark.toExternalForm());
        } catch (Exception ignored) {}
    }

    // ── Inner model ───────────────────────────────────────────────────────────

    static class PlayerRow {
        final StringProperty username      = new SimpleStringProperty();
        final StringProperty email         = new SimpleStringProperty();
        final StringProperty characterName = new SimpleStringProperty();
        final StringProperty ip            = new SimpleStringProperty();
        final StringProperty connectedTime = new SimpleStringProperty();
        final StringProperty score         = new SimpleStringProperty();
        final StringProperty x             = new SimpleStringProperty();
        final StringProperty y             = new SimpleStringProperty();
        volatile boolean isAdmin;
        final long joinedAt;

        PlayerRow(String username, String email, String characterName, String ip,
                  long joinedAt, double x, double y, int score, boolean isAdmin) {
            this.joinedAt = joinedAt;
            this.isAdmin  = isAdmin;
            this.username     .set(username);
            this.email        .set(email);
            this.characterName.set(characterName);
            this.ip           .set(ip);
            this.score        .set(String.valueOf(score));
            this.x            .set(String.format("%.0f", x));
            this.y            .set(String.format("%.0f", y));
            updateConnectedTime();
        }

        void updateConnectedTime() {
            long secs = (System.currentTimeMillis() - joinedAt) / 1000;
            if (secs < 60)        connectedTime.set(secs + "s");
            else if (secs < 3600) connectedTime.set((secs / 60) + "m " + (secs % 60) + "s");
            else                  connectedTime.set((secs / 3600) + "h " + ((secs % 3600) / 60) + "m");
        }
    }
}
