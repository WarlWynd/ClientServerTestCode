package com.game.admin.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.admin.AdminSession;
import com.game.admin.AdminUDPClient;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import com.game.shared.WorldConstants;
import com.game.shared.WorldDef;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorldEditorPanel {

    // ── Brush modes ───────────────────────────────────────────────────────────

    private enum BrushMode { RAISE, LOWER, FLATTEN, SMOOTH }

    // ── Chunk protocol ────────────────────────────────────────────────────────

    private static final int FLOATS_PER_CHUNK = 12_000;

    // ── State ─────────────────────────────────────────────────────────────────

    private final AdminUDPClient client;

    // World list: summaries from server (id/name/size/scales)
    private final List<long[]>               worldIds    = new ArrayList<>();   // parallel to displayList: [0]=id
    private final ObservableList<String>     displayList = FXCollections.observableArrayList();
    private final ListView<String>           listView    = new ListView<>(displayList);

    private long      currentId        = -1;
    private long      pendingNewWorldId = -1;
    private WorldDef  current;
    private BrushMode brushMode   = BrushMode.RAISE;
    private int       brushSize   = 10;
    private float     brushStrength = 0.05f;
    private float     flattenTarget = 0f;
    private int       cellSize    = 3;   // px per heightmap cell

    // Incoming pull: map from chunk index to decoded floats
    private final Map<Integer, float[]> pullChunks = new ConcurrentHashMap<>();
    private int pullTotalChunks = -1;
    private long pullWorldId    = -1;
    private WorldDef pullMeta;

    private Canvas      canvas;
    private TextField   nameField;
    private Label       sizeLabel;
    private Label       coordLabel;
    private Label       statusLabel;
    private TextField   spawnXField, spawnZField;
    private World3DViewWindow previewWindow;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WorldEditorPanel(AdminUDPClient client) {
        this.client = client;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public VBox build() {

        // ── World list ────────────────────────────────────────────────────────
        listView.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a6a;");
        listView.getSelectionModel().selectedIndexProperty()
                .addListener((obs, old, idx) -> { if (idx.intValue() >= 0) selectWorld(idx.intValue()); });
        VBox.setVgrow(listView, Priority.ALWAYS);

        Button addBtn     = btn("+ New",    "#50c050");
        Button delBtn     = btn("Delete",   "#e94560");
        Button refreshBtn = btn("Refresh",  "#2a4a6a");
        addBtn    .setOnAction(e -> doNew());
        delBtn    .setOnAction(e -> doDelete());
        refreshBtn.setOnAction(e -> requestList());
        HBox listBtns = new HBox(4, addBtn, delBtn, refreshBtn);
        HBox.setHgrow(addBtn,     Priority.ALWAYS);
        HBox.setHgrow(delBtn,     Priority.ALWAYS);
        HBox.setHgrow(refreshBtn, Priority.ALWAYS);

        // ── World properties ──────────────────────────────────────────────────
        nameField = new TextField("");
        nameField.setPromptText("World name");
        nameField.setStyle(fieldStyle());
        nameField.textProperty().addListener((o, old, n) -> {
            if (current != null) current.name = n;
        });

        sizeLabel = lbl("Size: —", 11);

        spawnXField = new TextField("0");
        spawnZField = new TextField("0");
        spawnXField.setPrefWidth(60);
        spawnZField.setPrefWidth(60);
        spawnXField.setStyle(fieldStyle());
        spawnZField.setStyle(fieldStyle());
        spawnXField.textProperty().addListener((o, old, n) -> syncSpawn());
        spawnZField.textProperty().addListener((o, old, n) -> syncSpawn());

        HBox spawnRow = new HBox(4,
                lbl("Spawn X:", 11), spawnXField,
                lbl("Z:", 11), spawnZField);
        spawnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Brush controls ────────────────────────────────────────────────────
        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton raiseRb   = radio("Raise",   modeGroup, BrushMode.RAISE);
        RadioButton lowerRb   = radio("Lower",   modeGroup, BrushMode.LOWER);
        RadioButton flattenRb = radio("Flatten", modeGroup, BrushMode.FLATTEN);
        RadioButton smoothRb  = radio("Smooth",  modeGroup, BrushMode.SMOOTH);
        raiseRb.setSelected(true);
        VBox modeBox = new VBox(3, raiseRb, lowerRb, flattenRb, smoothRb);

        Slider sizeSlider = new Slider(1, 60, brushSize);
        Label  sizeLbl    = lbl("Size: " + brushSize, 11);
        sizeSlider.valueProperty().addListener((o, old, n) -> {
            brushSize = n.intValue();
            sizeLbl.setText("Size: " + brushSize);
        });

        Slider strengthSlider = new Slider(0.005, 0.3, brushStrength);
        Label  strengthLbl    = lbl("Strength: " + String.format("%.3f", brushStrength), 11);
        strengthSlider.valueProperty().addListener((o, old, n) -> {
            brushStrength = n.floatValue();
            strengthLbl.setText("Strength: " + String.format("%.3f", brushStrength));
        });

        // ── Action buttons ────────────────────────────────────────────────────
        Button testBtn    = btn("Load Test World","#4a2a6a");
        Button flatBtn    = btn("Level Terrain",  "#3a3a8a");
        Button saveBtn    = btn("Save to Server", "#2a6a2a");
        Button loadBtn    = btn("Load Heightmap", "#2a4a6a");
        Button previewBtn = btn("3D Preview",     "#1a3a5a");

        testBtn   .setOnAction(e -> loadTestWorld());
        flatBtn   .setOnAction(e -> fillFlat());
        saveBtn   .setOnAction(e -> doPush());
        loadBtn   .setOnAction(e -> doPull());
        previewBtn.setOnAction(e -> openPreview());

        statusLabel = new Label("Click Refresh to load worlds.");
        statusLabel.setTextFill(Color.web("#9090b0"));
        statusLabel.setStyle("-fx-font-size: 11;");
        statusLabel.setWrapText(true);

        // ── Left column ───────────────────────────────────────────────────────
        // All controls live in one VBox inside a ScrollPane so nothing is ever
        // clipped regardless of window height.
        listView.setPrefHeight(160);
        listView.setMinHeight(80);

        VBox leftContent = new VBox(6,
                lbl("Server Worlds", 12, true),
                listView,
                listBtns,
                separator(),
                lbl("Name", 11), nameField,
                sizeLabel,
                spawnRow,
                separator(),
                lbl("Brush Mode", 11, true), modeBox,
                sizeLbl, sizeSlider,
                strengthLbl, strengthSlider,
                separator(),
                testBtn, flatBtn, loadBtn, saveBtn, previewBtn,
                statusLabel
        );
        leftContent.setPadding(new Insets(10));
        leftContent.setStyle("-fx-background-color: #16213e;");
        leftContent.setFillWidth(true);

        ScrollPane leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScroll.setStyle("-fx-background-color: #16213e; -fx-background: #16213e;");
        leftScroll.setPrefWidth(240);
        leftScroll.setMinWidth(240);
        leftScroll.setMaxWidth(240);

        // ── Canvas ────────────────────────────────────────────────────────────
        canvas = new Canvas(600, 600);
        setupCanvasEvents();
        redraw();

        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setStyle("-fx-background: #0a0a18; -fx-background-color: #0a0a18;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        Button zoomIn  = smallBtn("+");
        Button zoomOut = smallBtn("−");
        zoomIn .setOnAction(e -> zoom(+1));
        zoomOut.setOnAction(e -> zoom(-1));

        coordLabel = lbl("", 11);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(6, lbl("Zoom:", 11), zoomOut, zoomIn, spacer, coordLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(5, 10, 5, 10));
        toolbar.setStyle("-fx-background-color: #0f0f1e;");

        VBox rightCol = new VBox(toolbar, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(rightCol, Priority.ALWAYS);

        HBox root = new HBox(leftScroll, rightCol);
        HBox.setHgrow(rightCol, Priority.ALWAYS);
        VBox.setVgrow(root, Priority.ALWAYS);

        VBox wrapper = new VBox(root);
        VBox.setVgrow(root, Priority.ALWAYS);
        wrapper.setStyle("-fx-background-color: #1a1a2e;");

        requestList();
        return wrapper;
    }

    // ── Packet handler (called by DashboardScreen) ────────────────────────────

    public void onPacket(Packet p) {
        switch (p.type) {
            case ADMIN_WORLD_LIST_RESPONSE   -> Platform.runLater(() -> handleListResponse(p));
            case ADMIN_WORLD_NEW_RESPONSE    -> Platform.runLater(() -> handleNewResponse(p));
            case ADMIN_WORLD_DELETE_RESPONSE -> Platform.runLater(() -> handleDeleteResponse(p));
            case ADMIN_WORLD_PULL_CHUNK      -> handlePullChunk(p);
            case ADMIN_WORLD_PUSH_RESPONSE   -> Platform.runLater(() -> handlePushResponse(p));
            default -> {}
        }
    }

    // ── Server requests ───────────────────────────────────────────────────────

    private void requestList() {
        showStatus("Loading worlds from server…", false);
        client.send(new Packet(PacketType.ADMIN_WORLD_LIST_REQUEST,
                AdminSession.getToken(), PacketSerializer.emptyPayload()));
    }

    private void doNew() {
        TextInputDialog dlg = new TextInputDialog("New World");
        dlg.setHeaderText("World name:");
        dlg.setContentText("Name:");
        dlg.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            var payload = PacketSerializer.mapper().createObjectNode();
            payload.put("name",    name.trim());
            payload.put("size",    WorldConstants.TERRAIN_SIZE);
            payload.put("xzScale", WorldConstants.TERRAIN_XZ_SCALE);
            payload.put("yScale",  WorldConstants.TERRAIN_Y_SCALE);
            client.send(new Packet(PacketType.ADMIN_WORLD_NEW_REQUEST,
                    AdminSession.getToken(), payload));
            showStatus("Creating world…", false);
        });
    }

    private void doDelete() {
        if (currentId < 0 || current == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete world \"" + current.name + "\" from the server?\nThis cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Delete World");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            var payload = PacketSerializer.mapper().createObjectNode();
            payload.put("id", currentId);
            client.send(new Packet(PacketType.ADMIN_WORLD_DELETE_REQUEST,
                    AdminSession.getToken(), payload));
            showStatus("Deleting…", false);
        });
    }

    private void doPull() {
        if (currentId < 0) { showStatus("Select a world first.", true); return; }
        pullChunks.clear();
        pullTotalChunks = -1;
        pullWorldId     = currentId;
        pullMeta        = null;
        var payload = PacketSerializer.mapper().createObjectNode();
        payload.put("id", currentId);
        client.send(new Packet(PacketType.ADMIN_WORLD_PULL_REQUEST,
                AdminSession.getToken(), payload));
        showStatus("Loading heightmap…", false);
    }

    private void doPush() {
        if (currentId < 0 || current == null) { showStatus("No world loaded.", true); return; }
        float[] hm    = current.heightmap;
        int     total = (int) Math.ceil((double) hm.length / FLOATS_PER_CHUNK);

        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < total; i++) {
                    int from   = i * FLOATS_PER_CHUNK;
                    int to     = Math.min(from + FLOATS_PER_CHUNK, hm.length);
                    float[] sl = Arrays.copyOfRange(hm, from, to);

                    ByteBuffer buf = ByteBuffer.allocate(sl.length * 4).order(ByteOrder.BIG_ENDIAN);
                    for (float f : sl) buf.putFloat(f);
                    String b64 = Base64.getEncoder().encodeToString(buf.array());

                    var chunk = PacketSerializer.mapper().createObjectNode();
                    chunk.put("id",    currentId);
                    chunk.put("index", i);
                    chunk.put("total", total);
                    chunk.put("data",  b64);
                    client.send(new Packet(PacketType.ADMIN_WORLD_PUSH_CHUNK,
                            AdminSession.getToken(), chunk));

                    int finalI = i;
                    Platform.runLater(() -> showStatus("Uploading chunk " + (finalI+1) + "/" + total + "…", false));
                    Thread.sleep(5); // small delay to avoid flooding the server
                }

                var done = PacketSerializer.mapper().createObjectNode();
                done.put("id",          currentId);
                done.put("totalChunks", total);
                done.put("name",        current.name);
                done.put("spawnX",      current.spawnX);
                done.put("spawnY",      current.spawnY);
                done.put("spawnZ",      current.spawnZ);
                client.send(new Packet(PacketType.ADMIN_WORLD_PUSH_DONE,
                        AdminSession.getToken(), done));
            } catch (Exception e) {
                Platform.runLater(() -> showStatus("Push failed: " + e.getMessage(), true));
            }
        });
    }

    // ── Response handlers ─────────────────────────────────────────────────────

    private void handleListResponse(Packet p) {
        if (!p.payload.get("success").asBoolean()) {
            showStatus("List failed: " + p.payload.path("message").asText(), true);
            return;
        }
        worldIds.clear();
        displayList.clear();
        JsonNode arr = p.payload.get("worlds");
        if (arr != null && arr.isArray()) {
            for (JsonNode w : arr) {
                long id   = w.get("id").asLong();
                String nm = w.get("name").asText();
                int    sz = w.get("heightmapSize").asInt();
                worldIds.add(new long[]{id});
                displayList.add(nm + "  [" + sz + "²]");
            }
        }
        showStatus(displayList.size() + " world(s) on server.", false);
        if (pendingNewWorldId > 0) {
            for (int i = 0; i < worldIds.size(); i++) {
                if (worldIds.get(i)[0] == pendingNewWorldId) {
                    listView.getSelectionModel().select(i);
                    doPull();
                    break;
                }
            }
            pendingNewWorldId = -1;
        } else if (!displayList.isEmpty() && currentId < 0) {
            listView.getSelectionModel().select(0);
        }
    }

    private void handleNewResponse(Packet p) {
        if (!p.payload.get("success").asBoolean()) {
            showStatus("Create failed: " + p.payload.path("message").asText(), true);
            return;
        }
        pendingNewWorldId = p.payload.has("id") ? p.payload.get("id").asLong(-1) : -1;
        showStatus("World created — loading…", false);
        requestList();
    }

    private void handleDeleteResponse(Packet p) {
        if (!p.payload.get("success").asBoolean()) {
            showStatus("Delete failed: " + p.payload.path("message").asText(), true);
            return;
        }
        currentId = -1;
        current   = null;
        nameField.setText("");
        sizeLabel.setText("Size: —");
        redraw();
        showStatus("World deleted.", false);
        requestList();
    }

    private void handlePullChunk(Packet p) {
        try {
            long id    = p.payload.get("id").asLong();
            int  index = p.payload.get("index").asInt();
            int  total = p.payload.get("total").asInt();
            String b64 = p.payload.get("data").asText();

            if (id != pullWorldId) return;

            if (index == 0 && p.payload.has("name")) {
                pullMeta = new WorldDef();
                pullMeta.id            = String.valueOf(id);
                pullMeta.name          = p.payload.get("name").asText();
                pullMeta.heightmapSize = p.payload.get("heightmapSize").asInt();
                pullMeta.xzScale       = (float) p.payload.get("xzScale").asDouble();
                pullMeta.yScale        = (float) p.payload.get("yScale").asDouble();
                pullMeta.spawnX        = (float) p.payload.get("spawnX").asDouble();
                pullMeta.spawnY        = (float) p.payload.get("spawnY").asDouble();
                pullMeta.spawnZ        = (float) p.payload.get("spawnZ").asDouble();
                pullMeta.objects       = new ArrayList<>();
            }

            byte[]  raw    = Base64.getDecoder().decode(b64);
            float[] floats = new float[raw.length / 4];
            ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(floats);
            pullChunks.put(index, floats);
            pullTotalChunks = total;

            if (pullChunks.size() == total && pullMeta != null) {
                int totalFloats = pullChunks.values().stream().mapToInt(a -> a.length).sum();
                float[] hm = new float[totalFloats];
                int pos = 0;
                for (int i = 0; i < total; i++) {
                    float[] chunk = pullChunks.get(i);
                    System.arraycopy(chunk, 0, hm, pos, chunk.length);
                    pos += chunk.length;
                }
                pullMeta.heightmap = hm;
                WorldDef loaded = pullMeta;
                Platform.runLater(() -> {
                    current = loaded;
                    nameField.setText(current.name);
                    sizeLabel.setText("Size: " + current.heightmapSize + " × " + current.heightmapSize);
                    spawnXField.setText(String.format("%.1f", current.spawnX));
                    spawnZField.setText(String.format("%.1f", current.spawnZ));
                    redraw();
                    showStatus("Heightmap loaded (" + total + " chunks).", false);
                });
                pullChunks.clear();
            }
        } catch (Exception e) {
            Platform.runLater(() -> showStatus("Pull chunk error: " + e.getMessage(), true));
        }
    }

    private void handlePushResponse(Packet p) {
        if (p.payload.get("success").asBoolean()) {
            showStatus("Saved to server.", false);
        } else {
            showStatus("Save failed: " + p.payload.path("message").asText(), true);
        }
    }

    // ── World selection ───────────────────────────────────────────────────────

    private void selectWorld(int index) {
        if (index < 0 || index >= worldIds.size()) return;
        currentId = worldIds.get(index)[0];
        current   = null;

        // Show name from list label (strip the [size²] suffix)
        String label = displayList.get(index);
        int bracket  = label.indexOf("  [");
        nameField.setText(bracket > 0 ? label.substring(0, bracket) : label);
        sizeLabel.setText("Heightmap not loaded — click Load Heightmap");
        redraw();
    }

    // ── Canvas events ─────────────────────────────────────────────────────────

    private void setupCanvasEvents() {
        canvas.setOnMousePressed(e -> {
            if (current == null || current.heightmap == null) return;
            if (brushMode == BrushMode.FLATTEN)
                flattenTarget = sampleHeight(e.getX(), e.getY());
            paint(e.getX(), e.getY());
        });
        canvas.setOnMouseDragged(e -> {
            if (current == null || current.heightmap == null) return;
            paint(e.getX(), e.getY());
            int cx = (int)(e.getX() / cellSize);
            int cz = (int)(e.getY() / cellSize);
            if (inBounds(cx, cz))
                coordLabel.setText(String.format("x=%d  z=%d  h=%.3f", cx, cz,
                        current.heightmap[cz * current.heightmapSize + cx]));
        });
        canvas.setOnMouseMoved(e -> {
            if (current == null || current.heightmap == null) return;
            int cx = (int)(e.getX() / cellSize);
            int cz = (int)(e.getY() / cellSize);
            if (inBounds(cx, cz))
                coordLabel.setText(String.format("x=%d  z=%d  h=%.3f", cx, cz,
                        current.heightmap[cz * current.heightmapSize + cx]));
        });
    }

    private boolean inBounds(int cx, int cz) {
        return current != null && current.heightmap != null
                && cx >= 0 && cz >= 0
                && cx < current.heightmapSize && cz < current.heightmapSize;
    }

    // ── Paint ─────────────────────────────────────────────────────────────────

    private void paint(double mouseX, double mouseY) {
        if (current == null || current.heightmap == null) return;
        int cx = (int)(mouseX / cellSize);
        int cz = (int)(mouseY / cellSize);
        int size = current.heightmapSize;
        float[] hm = current.heightmap;

        int r = brushSize;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int nx = cx + dx, nz = cz + dz;
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) continue;
                double t = 1.0 - dist / r;
                float  influence = (float)(t * t);
                int    idx       = nz * size + nx;
                switch (brushMode) {
                    case RAISE   -> hm[idx] = Math.min(1f, hm[idx] + brushStrength * influence);
                    case LOWER   -> hm[idx] = Math.max(0f, hm[idx] - brushStrength * influence);
                    case FLATTEN -> hm[idx] += (flattenTarget - hm[idx]) * brushStrength * influence * 5f;
                    case SMOOTH  -> hm[idx]  = smooth(hm, size, nx, nz, influence);
                }
            }
        }
        redraw();
        if (previewWindow != null && previewWindow.getStage() != null
                && previewWindow.getStage().isShowing())
            previewWindow.refresh(current);
    }

    private float sampleHeight(double mouseX, double mouseY) {
        if (current == null || current.heightmap == null) return 0f;
        int cx = Math.min((int)(mouseX / cellSize), current.heightmapSize - 1);
        int cz = Math.min((int)(mouseY / cellSize), current.heightmapSize - 1);
        return current.heightmap[cz * current.heightmapSize + cx];
    }

    private float smooth(float[] hm, int size, int x, int z, float influence) {
        float sum = 0; int count = 0;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                sum += hm[nz * size + nx]; count++;
            }
        float avg = sum / count;
        return hm[z * size + x] + (avg - hm[z * size + x]) * brushStrength * influence * 5f;
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        if (current == null || current.heightmap == null) {
            canvas.setWidth(600); canvas.setHeight(600);
            gc.setFill(Color.web("#0a0a18"));
            gc.fillRect(0, 0, 600, 600);
            gc.setFill(Color.web("#606080"));
            gc.fillText(current == null
                    ? "Select a world from the list."
                    : "Click 'Load Heightmap' to edit terrain.", 20, 40);
            return;
        }

        int size    = current.heightmapSize;
        float[] hm  = current.heightmap;
        int canvasW = size * cellSize;
        int canvasH = size * cellSize;
        canvas.setWidth(canvasW);
        canvas.setHeight(canvasH);

        WritableImage img = new WritableImage(canvasW, canvasH);
        PixelWriter pw = img.getPixelWriter();

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int gray = (int)(hm[z * size + x] * 255);
                int argb = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                for (int py = z * cellSize; py < (z + 1) * cellSize; py++)
                    for (int px = x * cellSize; px < (x + 1) * cellSize; px++)
                        pw.setArgb(px, py, argb);
            }
        }
        gc.drawImage(img, 0, 0);

        // Spawn marker
        int sx = (int)(current.spawnX / current.xzScale + size / 2f);
        int sz = (int)(current.spawnZ / current.xzScale + size / 2f);
        if (sx >= 0 && sx < size && sz >= 0 && sz < size) {
            gc.setFill(Color.RED);
            gc.fillOval(sx * cellSize - 4, sz * cellSize - 4, 8, 8);
        }
    }

    // ── Misc ops ─────────────────────────────────────────────────────────────

    private void loadTestWorld() {
        int size = WorldConstants.TERRAIN_SIZE; // 257
        WorldDef w = WorldDef.createFlat("Test World", size);

        float[] hm = w.heightmap;
        double cx  = size / 2.0;
        double cy  = size / 2.0;

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double nx = (x - cx) / cx;   // -1..1
                double nz = (z - cy) / cy;

                // Rolling hills — stacked sines at different frequencies/phases
                double hills =
                        0.20 * Math.sin(nx * Math.PI * 3.0 + 0.5)  * Math.cos(nz * Math.PI * 2.5)
                      + 0.12 * Math.sin(nx * Math.PI * 6.0 - 1.0)  * Math.sin(nz * Math.PI * 5.0)
                      + 0.07 * Math.cos(nx * Math.PI * 11.0 + 0.3) * Math.cos(nz * Math.PI * 9.0 + 1.2)
                      + 0.04 * Math.sin(nx * Math.PI * 18.0 - 0.7) * Math.sin(nz * Math.PI * 16.0);

                // Central mountain
                double dist    = Math.sqrt(nx * nx + nz * nz);
                double mountain = Math.max(0, 0.65 * (1.0 - dist / 0.55));
                mountain = mountain * mountain;

                // River valley running roughly east–west (dip near z=0)
                double river = -0.18 * Math.exp(-nz * nz / 0.004);

                // Northern ridge
                double ridge = 0.35 * Math.max(0, (-nz - 0.55)) * Math.cos(nx * Math.PI * 2.0);

                double h = 0.18 + hills + mountain + river + ridge;
                hm[z * size + x] = (float) Math.max(0, Math.min(1, h));
            }
        }

        currentId = -1;   // local test — no server ID
        current   = w;
        nameField.setText(w.name);
        sizeLabel.setText("Size: " + size + " × " + size + "  (local, not saved)");
        spawnXField.setText("0.0");
        spawnZField.setText("0.0");
        redraw();
        showStatus("Test world loaded. Paint, then open 3D Preview.", false);
    }

    private void zoom(int delta) {
        cellSize = Math.max(1, Math.min(8, cellSize + delta));
        redraw();
    }

    private void fillFlat() {
        if (current == null || current.heightmap == null) return;
        Arrays.fill(current.heightmap, 0f);
        redraw();
    }

    private void syncSpawn() {
        if (current == null) return;
        try { current.spawnX = Float.parseFloat(spawnXField.getText().trim()); } catch (NumberFormatException ignored) {}
        try { current.spawnZ = Float.parseFloat(spawnZField.getText().trim()); } catch (NumberFormatException ignored) {}
        redraw();
    }

    private void openPreview() {
        if (current == null || current.heightmap == null) {
            showStatus("Load the heightmap first.", true); return;
        }
        if (previewWindow == null) previewWindow = new World3DViewWindow();
        if (previewWindow.getStage() != null && previewWindow.getStage().isShowing()) {
            previewWindow.refresh(current);
            previewWindow.getStage().toFront();
        } else {
            previewWindow = new World3DViewWindow();
            previewWindow.show(current);
        }
    }

    private void showStatus(String msg, boolean error) {
        statusLabel.setTextFill(Color.web(error ? "#e94560" : "#80c080"));
        statusLabel.setText(msg);
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private RadioButton radio(String text, ToggleGroup group, BrushMode mode) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setStyle("-fx-text-fill: #c0c0e0; -fx-font-size: 11;");
        rb.setOnAction(e -> brushMode = mode);
        return rb;
    }

    private Button btn(String text, String color) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white;" +
                   "-fx-font-size: 11; -fx-background-radius: 4; -fx-padding: 5 10 5 10;");
        return b;
    }

    private Button smallBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #2a2a4a; -fx-text-fill: white;" +
                   "-fx-font-size: 12; -fx-padding: 3 8 3 8; -fx-background-radius: 3;");
        return b;
    }

    private static Label lbl(String text, int size) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #9090b0; -fx-font-size: " + size + ";");
        return l;
    }

    private static Label lbl(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #c0c0e0; -fx-font-size: " + size + ";" +
                   (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private static Separator separator() {
        Separator s = new Separator();
        s.setStyle("-fx-background-color: #3a3a6a;");
        return s;
    }

    private static String fieldStyle() {
        return "-fx-background-color: #0f0f1e; -fx-text-fill: #e0e0e0;" +
               "-fx-border-color: #3a3a6a; -fx-border-radius: 3; -fx-padding: 4;";
    }
}
