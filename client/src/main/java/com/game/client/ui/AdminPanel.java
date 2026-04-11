package com.game.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.AppSettings;
import com.game.client.SessionStore;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Embeddable admin dashboard panel — shown as a tab in GameScreen for admin users.
 *
 * Call {@link #buildView()} once to get the root Node, then {@link #start()} to
 * begin polling, and {@link #stop()} when the user logs out.
 *
 * Packet handling is delegated from GameScreen via {@link #onPacket(Packet)}.
 */
public class AdminPanel {

    private static final Logger log = LoggerFactory.getLogger(AdminPanel.class);

    private final UDPClient client;

    private Label     headerLabel;
    private Label     statusLabel;
    private final ObservableList<PlayerRow> rows         = FXCollections.observableArrayList();
    private final FilteredList<PlayerRow>   filteredRows = new FilteredList<>(rows, p -> true);
    private TextField searchField;
    private ComboBox<String> searchFieldCombo;
    private Timeline ticker;
    private GameSettingsPanel gameSettingsPanel;

    public void setRestartCallback(Consumer<Integer> callback) {
        if (gameSettingsPanel != null) gameSettingsPanel.setRestartCallback(callback);
    }

    public AdminPanel(UDPClient client) {
        this.client = client;
    }

    // ── Build ────────────────────────────────────────────────────────────────

    public Node buildView() {
        headerLabel = new Label("Admin Panel — " + SessionStore.getUsername());
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        headerLabel.getStyleClass().add("text-primary");

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.getStyleClass().add("text-success");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(8, headerLabel, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 6, 14));
        header.setSpacing(8);
        header.getStyleClass().add("app-surface");

        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(2, 14, 4, 14));
        statusBar.getStyleClass().add("app-surface");

        // ── Search bar ────────────────────────────────────────────────────────
        searchField = new TextField();
        searchField.setPromptText("Search…");
        searchField.setPrefWidth(220);
        searchField.getStyleClass().add("input-field");

        searchFieldCombo = new ComboBox<>();
        searchFieldCombo.getItems().addAll("Email", "Username", "Character Name", "IP");
        searchFieldCombo.setValue("Username");
        searchFieldCombo.getStyleClass().add("combo-dark");

        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());
        searchFieldCombo.valueProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        Button clearSearchBtn = new Button("✕");
        clearSearchBtn.getStyleClass().add("btn-primary-sm");
        clearSearchBtn.setOnAction(e -> searchField.clear());

        HBox searchBar = new HBox(6, new Label("Search:"), searchFieldCombo, searchField, clearSearchBtn);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(6, 14, 4, 14));
        searchBar.getStyleClass().add("app-surface");

        // ── Table ─────────────────────────────────────────────────────────────
        TableView<PlayerRow> table = new TableView<>(filteredRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getStyleClass().add("dark-table");
        table.setPlaceholder(new Label("No players connected"));

        table.getColumns().addAll(
                strCol("Email",          "email",         160),
                strCol("Username",       "username",      110),
                strCol("Character Name", "characterName", 110),
                strCol("IP",             "ip",            110),
                strCol("Connected", "connectedTime",  85),
                strCol("Score",     "score",           50),
                strCol("X",         "x",               40),
                strCol("Y",         "y",               40),
                adminCol(),
                devCol("Graphics", "graphicsDev"),
                devCol("World",    "boardDev"),
                devCol("Audio",    "audioDev"),
                kickCol(),
                banCol()
        );

        VBox playersPage = new VBox(header, statusBar, searchBar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        playersPage.getStyleClass().add("app-root");

        // ── Game Settings inner tab ───────────────────────────────────────────
        gameSettingsPanel = new GameSettingsPanel(client);
        javafx.scene.Node gameSettingsPage = gameSettingsPanel.buildView();

        Tab playersTab     = new Tab("👥 Players",      playersPage);
        Tab gameSettingsTab = new Tab("🎛 Game Settings", gameSettingsPage);
        playersTab.setClosable(false);
        gameSettingsTab.setClosable(false);

        TabPane innerTabs = new TabPane(playersTab, gameSettingsTab);
        innerTabs.getStyleClass().add("tab-pane-dark");

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> requestPlayerList()));
        ticker.setCycleCount(Timeline.INDEFINITE);

        return innerTabs;
    }

    private VBox buildSection(String title, Node... children) {
        Label sectionTitle = new Label(title);
        sectionTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        sectionTitle.getStyleClass().add("section-title");

        Separator sep = new Separator();
        sep.getStyleClass().add("sep-dark");

        VBox section = new VBox(6);
        section.getChildren().addAll(sectionTitle, sep);
        section.getChildren().addAll(children);
        section.setPadding(new Insets(10, 14, 10, 14));
        section.getStyleClass().add("app-surface");
        return section;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() {
        ticker.play();
        requestPlayerList();
    }

    public void stop() {
        if (ticker != null) ticker.stop();
    }

    // ── Packet handling (called by GameScreen) ────────────────────────────────

    public void onPacket(Packet packet) {
        switch (packet.type) {
            case ADMIN_USER_LIST_RESPONSE -> {
                boolean success = packet.payload.get("success").asBoolean();
                if (!success) {
                    String msg = packet.payload.has("message")
                            ? packet.payload.get("message").asText() : "Access denied";
                    log.warn("ADMIN_USER_LIST_RESPONSE denied: {}", msg);
                    Platform.runLater(() -> showStatus("Access denied: " + msg, false));
                    return;
                }
                JsonNode players = packet.payload.get("players");
                int count = players != null && players.isArray() ? players.size() : 0;
                log.info("ADMIN_USER_LIST_RESPONSE ok — {} player(s)", count);
                Platform.runLater(() -> {
                    rows.clear();
                    if (players != null && players.isArray()) {
                        for (JsonNode p : players) {
                            rows.add(new PlayerRow(
                                    p.get("username").asText(),
                                    p.has("email")         ? p.get("email").asText()         : "",
                                    p.has("characterName") ? p.get("characterName").asText() : "—",
                                    p.has("ip")            ? p.get("ip").asText()            : "—",
                                    p.get("joinedAt").asLong(),
                                    p.get("x").asDouble(),
                                    p.get("y").asDouble(),
                                    p.get("score").asInt(),
                                    p.has("isAdmin")       && p.get("isAdmin").asBoolean(),
                                    p.has("isGraphicsDev") && p.get("isGraphicsDev").asBoolean(),
                                    p.has("isBoardDev")    && p.get("isBoardDev").asBoolean(),
                                    p.has("isAudioDev")    && p.get("isAudioDev").asBoolean()));
                        }
                    }
                    headerLabel.setText(String.format("Admin Panel — %s  |  %d player%s online",
                            SessionStore.getUsername(), rows.size(), rows.size() == 1 ? "" : "s"));
                });
            }
            case ADMIN_KICK_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Kicked: "      + packet.payload.get("username").asText()
                        : "Kick failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_BAN_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? "Banned: "      + packet.payload.get("username").asText()
                        : "Ban failed: "  + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_SET_ADMIN_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg = ok
                        ? (packet.payload.get("isAdmin").asBoolean() ? "Granted admin: " : "Revoked admin: ")
                                + packet.payload.get("username").asText()
                        : "Admin change failed: " + packet.payload.get("message").asText();
                showStatus(msg, ok);
            });
            case ADMIN_SET_DEV_RESPONSE -> Platform.runLater(() -> {
                boolean ok  = packet.payload.get("success").asBoolean();
                String  msg;
                if (ok) {
                    String role  = packet.payload.has("role")  ? packet.payload.get("role").asText()  : "dev";
                    String user  = packet.payload.has("username") ? packet.payload.get("username").asText() : "?";
                    boolean grant = packet.payload.has("grant") && packet.payload.get("grant").asBoolean();
                    msg = (grant ? "Granted " : "Revoked ") + role + ": " + user;
                } else {
                    msg = "Dev change failed: " + packet.payload.get("message").asText();
                }
                showStatus(msg, ok);
            });
            case ADMIN_SAVE_SETTINGS_RESPONSE,
                 ADMIN_RESTART_RESPONSE,
                 ADMIN_DEPLOY_RESPONSE -> {
                if (gameSettingsPanel != null) gameSettingsPanel.onPacket(packet);
            }
        }
    }

    // ── Table helpers ─────────────────────────────────────────────────────────

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase();
        String field = searchFieldCombo.getValue();
        if (query.isEmpty()) {
            filteredRows.setPredicate(p -> true);
            return;
        }
        filteredRows.setPredicate(row -> {
            String value = switch (field) {
                case "Email"          -> row.email.get();
                case "Username"       -> row.username.get();
                case "Character Name" -> row.characterName.get();
                case "IP"             -> row.ip.get();
                default               -> row.username.get();
            };
            return value != null && value.toLowerCase().contains(query);
        });
    }

    private TableColumn<PlayerRow, String> strCol(String title, String field, double width) {
        TableColumn<PlayerRow, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(data -> {
            PlayerRow row = data.getValue();
            return switch (field) {
                case "email"         -> row.email;
                case "username"      -> row.username;
                case "characterName" -> row.characterName;
                case "ip"            -> row.ip;
                case "connectedTime" -> row.connectedTime;
                case "score"         -> row.score;
                case "x"             -> row.x;
                case "y"             -> row.y;
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
                if (row.username.get().equals(SessionStore.getUsername())) {
                    setGraphic(null); return;
                }
                btn.setText(row.isAdmin ? "Revoke" : "Grant");
                btn.getStyleClass().removeAll("btn-revoke", "btn-grant");
                btn.getStyleClass().add(row.isAdmin ? "btn-revoke" : "btn-grant");
                btn.setOnAction(e -> doSetAdmin(row.username.get(), !row.isAdmin));
                setGraphic(btn);
            }
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> devCol(String label, String role) {
        TableColumn<PlayerRow, Void> col = new TableColumn<>(label);
        col.setPrefWidth(70);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button();
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                boolean has = switch (role) {
                    case "graphicsDev" -> row.isGraphicsDev;
                    case "boardDev"    -> row.isBoardDev;
                    case "audioDev"    -> row.isAudioDev;
                    default            -> false;
                };
                btn.setText(has ? "Revoke" : "Grant");
                btn.getStyleClass().removeAll("btn-revoke", "btn-grant");
                btn.getStyleClass().add(has ? "btn-revoke" : "btn-grant");
                btn.setOnAction(e -> doSetDev(row.username.get(), role, !has));
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
            {
                btn.getStyleClass().add("btn-primary-sm");
                btn.setOnAction(e -> {
                    PlayerRow row = getTableView().getItems().get(getIndex());
                    doKick(row.username.get());
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                setGraphic(row.username.get().equals(SessionStore.getUsername()) ? null : btn);
            }
        });
        return col;
    }

    private TableColumn<PlayerRow, Void> banCol() {
        TableColumn<PlayerRow, Void> col = new TableColumn<>("Ban");
        col.setPrefWidth(80);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("Ban");
            {
                btn.getStyleClass().add("btn-danger-sm");
                btn.setOnAction(e -> {
                    PlayerRow row = getTableView().getItems().get(getIndex());
                    doBan(row.username.get());
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                PlayerRow row = getTableView().getItems().get(getIndex());
                setGraphic(row.username.get().equals(SessionStore.getUsername()) ? null : btn);
            }
        });
        return col;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void requestPlayerList() {
        log.info("Sending ADMIN_USER_LIST_REQUEST (token={})",
                SessionStore.getToken() != null ? SessionStore.getToken().substring(0, 8) + "…" : "null");
        client.sendToAdmin(new Packet(PacketType.ADMIN_USER_LIST_REQUEST,
                SessionStore.getToken(), PacketSerializer.emptyPayload()));
    }

    private void doKick(String username) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        client.sendToAdmin(new Packet(PacketType.ADMIN_KICK_REQUEST, SessionStore.getToken(), payload));
    }

    private void doSetAdmin(String username, boolean isAdmin) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("isAdmin",  isAdmin);
        client.sendToAdmin(new Packet(PacketType.ADMIN_SET_ADMIN_REQUEST, SessionStore.getToken(), payload));
    }

    private void doSetDev(String username, String role, boolean grant) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("role",     role);
        payload.put("grant",    grant);
        client.sendToAdmin(new Packet(PacketType.ADMIN_SET_DEV_REQUEST, SessionStore.getToken(), payload));
    }

    private void doBan(String username) {
        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("username", username);
        payload.put("ban", true);
        client.sendToAdmin(new Packet(PacketType.ADMIN_BAN_REQUEST, SessionStore.getToken(), payload));
    }

    private void showStatus(String msg, boolean ok) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: " + (ok ? "-af-success;" : "-af-error;"));
        PauseTransition clear = new PauseTransition(Duration.seconds(4));
        clear.setOnFinished(e -> {
            statusLabel.setText("");
            statusLabel.setStyle("");
        });
        clear.play();
    }

    // ── Inner model ───────────────────────────────────────────────────────────

    static class PlayerRow {
        final StringProperty  email         = new SimpleStringProperty();
        final StringProperty  username      = new SimpleStringProperty();
        final StringProperty  characterName = new SimpleStringProperty();
        final StringProperty  ip            = new SimpleStringProperty();
        final StringProperty  connectedTime = new SimpleStringProperty();
        final StringProperty  score         = new SimpleStringProperty();
        final StringProperty  x             = new SimpleStringProperty();
        final StringProperty  y             = new SimpleStringProperty();
        volatile boolean      isAdmin;
        volatile boolean      isGraphicsDev;
        volatile boolean      isBoardDev;
        volatile boolean      isAudioDev;
        final long            joinedAt;

        PlayerRow(String username, String email, String characterName, String ip, long joinedAt, double x, double y, int score,
                  boolean isAdmin, boolean isGraphicsDev, boolean isBoardDev, boolean isAudioDev) {
            this.joinedAt      = joinedAt;
            this.isAdmin       = isAdmin;
            this.isGraphicsDev = isGraphicsDev;
            this.isBoardDev    = isBoardDev;
            this.isAudioDev    = isAudioDev;
            this.email.set(email);
            this.username.set(username);
            this.characterName.set(characterName);
            this.ip.set(ip);
            this.score.set(String.valueOf(score));
            this.x.set(String.format("%.0f", x));
            this.y.set(String.format("%.0f", y));
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
