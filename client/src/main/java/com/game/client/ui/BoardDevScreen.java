package com.game.client.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;

/**
 * Board Dev screen — tile-based level designer for building game boards.
 *
 * The canvas shows a scrollable, zoomable grid. Left-click paints the
 * selected tile type; right-click erases (sets to AIR).
 */
public class BoardDevScreen {

    // ── Board state ───────────────────────────────────────────────────────────
    private static final int DEFAULT_COLS = 58;  // 3200/58 ≈ 55 world units ≈ sprite height
    private static final int DEFAULT_ROWS = 44;  // 2400/44 ≈ 55 world units
    private static final int TILE_PX      = 24;   // default tile size in canvas pixels

    private int           cols  = DEFAULT_COLS;
    private int           rows  = DEFAULT_ROWS;
    private BoardTile[][] board = new BoardTile[rows][cols];

    private BoardTile selectedTile = BoardTile.PLATFORM;
    private int        cursorRow   = -1;   // board row under the mouse (-1 = off-canvas)

    // Scroll/zoom
    private double offsetX = 0, offsetY = 0;
    private double zoom    = 1.0;

    private final Stage    stage;
    private final Runnable onLoadIntoGame;

    public BoardDevScreen(Stage stage) {
        this(stage, null);
    }

    public BoardDevScreen(Stage stage, Runnable onLoadIntoGame) {
        this.stage          = stage;
        this.onLoadIntoGame = onLoadIntoGame;
        clearBoard();
        dailyBackup();
    }

    public Node build() {
        // ── Canvas ────────────────────────────────────────────────────────────
        Canvas canvas = new Canvas(900, 600);
        drawBoard(canvas);

        // ── Scrollbars ────────────────────────────────────────────────────────
        ScrollBar hBar = new ScrollBar();
        hBar.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        hBar.setMin(0);
        hBar.setMaxHeight(14);
        hBar.setPrefHeight(14);
        hBar.setStyle("-fx-background-color: #12122a;");

        ScrollBar vBar = new ScrollBar();
        vBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vBar.setMin(0);
        vBar.setMaxWidth(14);
        vBar.setPrefWidth(14);
        vBar.setStyle("-fx-background-color: #12122a;");

        // Update scrollbar ranges (call after zoom or board-size changes).
        // JavaFX thumb draggable range = [min, max - visibleAmount], so set
        // max = totalBoardSize and visibleAmount = viewportSize so the thumb
        // stops exactly at boardSize - viewportSize (= the correct max offset).
        Runnable syncBars = () -> {
            double ts   = TILE_PX * zoom;
            double bw   = cols * ts;
            double bh   = rows * ts;
            double cw   = Math.max(1, canvas.getWidth());
            double ch   = Math.max(1, canvas.getHeight());
            double maxX = Math.max(0, bw - cw);
            double maxY = Math.max(0, bh - ch);
            offsetX = Math.max(0, Math.min(offsetX, maxX));
            offsetY = Math.max(0, Math.min(offsetY, maxY));
            hBar.setMax(bw);
            hBar.setVisibleAmount(Math.min(cw, bw));
            vBar.setMax(bh);
            vBar.setVisibleAmount(Math.min(ch, bh));
            hBar.setValue(offsetX);
            vBar.setValue(offsetY);
        };

        // Scrollbar → offset (user drags thumb)
        hBar.valueProperty().addListener((obs, o, n) -> { offsetX = n.doubleValue(); drawBoard(canvas); });
        vBar.valueProperty().addListener((obs, o, n) -> { offsetY = n.doubleValue(); drawBoard(canvas); });

        // Mouse paint
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED,  e -> handleMouse(e, canvas));
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED,  e -> handleMouse(e, canvas));

        // Scroll to pan (vertical) or zoom (Ctrl)
        canvas.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown()) {
                zoom = Math.max(0.25, Math.min(4.0, zoom + e.getDeltaY() * 0.002));
            } else {
                double ts   = TILE_PX * zoom;
                double maxX = Math.max(0, cols * ts - canvas.getWidth());
                double maxY = Math.max(0, rows * ts - canvas.getHeight());
                offsetX = Math.max(0, Math.min(maxX, offsetX - e.getDeltaX()));
                offsetY = Math.max(0, Math.min(maxY, offsetY - e.getDeltaY()));
            }
            syncBars.run();
            drawBoard(canvas);
        });

        // Resize canvas with window — re-sync scrollbars
        canvas.widthProperty().addListener(o  -> { syncBars.run(); drawBoard(canvas); });
        canvas.heightProperty().addListener(o -> { syncBars.run(); drawBoard(canvas); });

        // ── Tile palette ──────────────────────────────────────────────────────
        VBox palette = new VBox(6);
        palette.setPadding(new Insets(12));
        palette.setStyle("-fx-background-color: #12122a;");
        palette.setMinWidth(130);

        Label palLbl = new Label("TILES");
        palLbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        palLbl.setStyle("-fx-text-fill: #6060a0;");
        palette.getChildren().add(palLbl);

        ToggleGroup tg = new ToggleGroup();
        for (BoardTile t : BoardTile.values()) {
            ToggleButton btn = new ToggleButton(t.label);
            btn.setToggleGroup(tg);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setStyle(tileButtonStyle(t, false));
            btn.setOnAction(e -> {
                selectedTile = t;
                btn.setStyle(tileButtonStyle(t, true));
            });
            btn.selectedProperty().addListener((o, a, sel) ->
                btn.setStyle(tileButtonStyle(t, sel)));
            if (t == BoardTile.PLATFORM) btn.setSelected(true);
            palette.getChildren().add(btn);
        }

        // ── Board settings ────────────────────────────────────────────────────
        Label settingsLbl = new Label("BOARD");
        settingsLbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        settingsLbl.setStyle("-fx-text-fill: #6060a0;");
        settingsLbl.setPadding(new Insets(10, 0, 2, 0));

        Label colsLbl = new Label("Columns:");
        colsLbl.setStyle("-fx-text-fill: #9090b0;");
        Spinner<Integer> colsSpin = new Spinner<>(8, 200, cols, 4);
        colsSpin.setEditable(true);
        colsSpin.setPrefWidth(110);
        styleSpinner(colsSpin);

        Label rowsLbl = new Label("Rows:");
        rowsLbl.setStyle("-fx-text-fill: #9090b0;");
        Spinner<Integer> rowsSpin = new Spinner<>(4, 100, rows, 2);
        rowsSpin.setEditable(true);
        rowsSpin.setPrefWidth(110);
        styleSpinner(rowsSpin);

        Button applyBtn = new Button("Apply Size");
        applyBtn.setMaxWidth(Double.MAX_VALUE);
        applyBtn.setStyle("-fx-background-color: #224488; -fx-text-fill: #c8c8e8;");
        Button clearBtn = new Button("Clear Board");
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setStyle("-fx-background-color: #442222; -fx-text-fill: #c8c8e8;");

        Button fillFloorBtn = new Button("Fill Level");
        fillFloorBtn.setMaxWidth(Double.MAX_VALUE);
        fillFloorBtn.setStyle("-fx-background-color: #224433; -fx-text-fill: #c8c8e8;");
        fillFloorBtn.setOnAction(e -> { fillLevel(); drawBoard(canvas); });

        Label fillRowLbl = new Label("row: —");
        fillRowLbl.setStyle("-fx-text-fill: #ffff66; -fx-font-size: 10;");

        // ── File I/O ──────────────────────────────────────────────────────────
        Label fileLbl = new Label("FILE");
        fileLbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        fileLbl.setStyle("-fx-text-fill: #6060a0;");
        fileLbl.setPadding(new Insets(10, 0, 2, 0));

        Label nameLbl = new Label("Name:");
        nameLbl.setStyle("-fx-text-fill: #9090b0; -fx-font-size: 11;");

        TextField nameField = new TextField("untitled");
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: #c8c8e8; -fx-font-size: 11;");

        Label fileNameLbl = new Label("");
        fileNameLbl.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 10;");
        fileNameLbl.setWrapText(true);

        Button saveBtn = new Button("Save Board");
        saveBtn.setMaxWidth(Double.MAX_VALUE);
        saveBtn.setStyle("-fx-background-color: #225522; -fx-text-fill: #c8c8e8;");
        saveBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save Board");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            fc.setInitialDirectory(boardsDir());
            String name = nameField.getText().trim();
            if (name.isEmpty()) name = "untitled";
            fc.setInitialFileName(name.endsWith(".csv") ? name : name + ".csv");
            File f = fc.showSaveDialog(stage);
            if (f != null && saveBoard(f)) {
                String base = f.getName().replaceAll("\\.csv$", "");
                nameField.setText(base);
                fileNameLbl.setText("Saved: " + f.getName());
            }
        });

        Button loadBtn = new Button("Load Board");
        loadBtn.setMaxWidth(Double.MAX_VALUE);
        loadBtn.setStyle("-fx-background-color: #222255; -fx-text-fill: #c8c8e8;");
        loadBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Load Board");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            fc.setInitialDirectory(boardsDir());
            File f = fc.showOpenDialog(stage);
            if (f != null && loadBoard(f)) {
                String base = f.getName().replaceAll("\\.csv$", "");
                nameField.setText(base);
                fileNameLbl.setText("Loaded: " + f.getName());
                colsSpin.getValueFactory().setValue(cols);
                rowsSpin.getValueFactory().setValue(rows);
                drawBoard(canvas);
            }
        });

        Button restoreBtn = new Button("Restore Backup");
        restoreBtn.setMaxWidth(Double.MAX_VALUE);
        restoreBtn.setStyle("-fx-background-color: #553300; -fx-text-fill: #c8c8e8;");
        restoreBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Restore Backup");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            File backupsRoot = new File(boardsDir(), "backups");
            backupsRoot.mkdirs();
            fc.setInitialDirectory(backupsRoot);
            File f = fc.showOpenDialog(stage);
            if (f != null && loadBoard(f)) {
                String base = f.getName().replaceAll("\\.csv$", "");
                nameField.setText(base);
                fileNameLbl.setText("Restored: " + f.getName());
                colsSpin.getValueFactory().setValue(cols);
                rowsSpin.getValueFactory().setValue(rows);
                drawBoard(canvas);
            }
        });

        // ── Load into Game ────────────────────────────────────────────────────
        Button loadIntoGameBtn = new Button("▶ Load into Game");
        loadIntoGameBtn.setMaxWidth(Double.MAX_VALUE);
        loadIntoGameBtn.setStyle(
                "-fx-background-color: #1a4a2a; -fx-text-fill: #66ffaa; " +
                "-fx-border-color: #33aa66; -fx-border-width: 1; -fx-border-radius: 3; " +
                "-fx-background-radius: 3; -fx-font-weight: bold;");
        loadIntoGameBtn.setOnAction(e -> {
            BoardStore.set(copyBoard(), rows, cols, nameField.getText().trim());
            if (onLoadIntoGame != null) onLoadIntoGame.run();
        });

        // Zoom label
        Label zoomLbl = new Label();
        zoomLbl.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 10;");
        canvas.addEventHandler(ScrollEvent.SCROLL, e -> zoomLbl.setText(
                String.format("Zoom %.0f%%  Ctrl+Scroll to zoom", zoom * 100)));
        zoomLbl.setText("Scroll=pan  Ctrl+Scroll=zoom");

        palette.getChildren().addAll(
                settingsLbl,
                colsLbl, colsSpin,
                rowsLbl, rowsSpin,
                applyBtn, clearBtn, fillFloorBtn, fillRowLbl,
                new Separator(),
                fileLbl,
                nameLbl, nameField,
                saveBtn, loadBtn, restoreBtn,
                new Separator(),
                loadIntoGameBtn,
                fileNameLbl,
                new Separator(),
                zoomLbl
        );

        // ── Status bar ────────────────────────────────────────────────────────
        Label statusBar = new Label(" ");
        statusBar.setStyle("-fx-text-fill: #6060a0; -fx-font-size: 11; " +
                           "-fx-background-color: #0a0a1a; -fx-padding: 3 8;");
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            int[] tc = canvasToTile(e.getX(), e.getY());
            if (tc != null) {
                cursorRow = tc[0];
                fillRowLbl.setText("row: " + cursorRow);
                BoardTile t = board[tc[0]][tc[1]];
                statusBar.setText(String.format("  row %d  col %d  →  %s     LMB=paint  RMB=erase",
                        tc[0], tc[1], t.label));
                drawBoard(canvas);
            } else {
                statusBar.setText("  —");
            }
        });

        // ── Root layout ───────────────────────────────────────────────────────
        StackPane canvasPane = new StackPane(canvas);
        canvasPane.setStyle("-fx-background-color: #0a0a1a;");
        canvas.widthProperty().bind(canvasPane.widthProperty());
        canvas.heightProperty().bind(canvasPane.heightProperty());

        // Row 1: canvas + vBar side by side
        HBox canvasRow = new HBox(canvasPane, vBar);
        HBox.setHgrow(canvasPane, Priority.ALWAYS);
        VBox.setVgrow(canvasRow, Priority.ALWAYS);

        // Col: canvasRow on top, hBar below — VBox.fillWidth=true stretches hBar to full width
        hBar.setMaxWidth(Double.MAX_VALUE);
        VBox canvasArea = new VBox(canvasRow, hBar);
        HBox.setHgrow(canvasArea, Priority.ALWAYS);

        // Sync bars once the layout is applied
        canvasPane.widthProperty().addListener(o  -> syncBars.run());
        canvasPane.heightProperty().addListener(o -> syncBars.run());

        // Sync when apply/clear changes board dimensions
        applyBtn.setOnAction(e -> { resizeBoard(rowsSpin.getValue(), colsSpin.getValue()); syncBars.run(); drawBoard(canvas); });
        clearBtn.setOnAction(e -> { clearBoard(); syncBars.run(); drawBoard(canvas); });

        HBox main = new HBox(palette, canvasArea);
        main.setStyle("-fx-background-color: #0f0f1e;");
        VBox.setVgrow(main, Priority.ALWAYS);

        VBox rootVBox = new VBox(main, statusBar);
        VBox.setVgrow(main, Priority.ALWAYS);
        rootVBox.setStyle("-fx-background-color: #0f0f1e;");

        return rootVBox;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private void drawBoard(Canvas canvas) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(Color.web("#0a0a1a"));
        gc.fillRect(0, 0, w, h);

        double ts = TILE_PX * zoom;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double x = c * ts - offsetX;
                double y = r * ts - offsetY;

                // Skip tiles fully off-screen
                if (x + ts < 0 || x > w || y + ts < 0 || y > h) continue;

                BoardTile t = board[r][c];

                // Fill
                if (t != BoardTile.AIR) {
                    gc.setFill(t.fill);
                    gc.fillRect(x + 1, y + 1, ts - 2, ts - 2);
                }

                // Special tile rendering
                drawTileDetail(gc, t, x, y, ts);

                // Grid line
                gc.setStroke(Color.web("#1e1e3a"));
                gc.setLineWidth(0.5);
                gc.strokeRect(x, y, ts, ts);
            }
        }

        // Board border
        gc.setStroke(Color.web("#334466"));
        gc.setLineWidth(2);
        gc.strokeRect(-offsetX, -offsetY, cols * ts, rows * ts);

        // Highlight the current fill-level row
        if (cursorRow >= 0 && cursorRow < rows) {
            double ry = cursorRow * ts - offsetY;
            gc.setFill(Color.color(1, 1, 0, 0.15));
            gc.fillRect(-offsetX, ry, cols * ts, ts);
            gc.setStroke(Color.color(1, 1, 0, 0.6));
            gc.setLineWidth(1.5);
            gc.strokeRect(-offsetX, ry, cols * ts, ts);
        }
    }

    private void drawTileDetail(GraphicsContext gc, BoardTile t, double x, double y, double ts) {
        switch (t) {
            case LADDER -> {
                // Two vertical rails + rungs
                gc.setStroke(t.border);
                gc.setLineWidth(Math.max(1, ts * 0.08));
                double lx = x + ts * 0.25, rx = x + ts * 0.75;
                gc.strokeLine(lx, y + 2, lx, y + ts - 2);
                gc.strokeLine(rx, y + 2, rx, y + ts - 2);
                gc.setLineWidth(Math.max(1, ts * 0.05));
                int rungs = Math.max(2, (int)(ts / 8));
                for (int i = 1; i < rungs; i++) {
                    double ry = y + (ts / rungs) * i;
                    gc.strokeLine(lx, ry, rx, ry);
                }
            }
            case SPIKE -> {
                // Triangle spikes along bottom edge
                gc.setFill(t.border);
                int spikes = Math.max(2, (int)(ts / 8));
                double sw = ts / spikes;
                for (int i = 0; i < spikes; i++) {
                    double sx = x + i * sw;
                    double[] px = { sx, sx + sw / 2, sx + sw };
                    double[] py = { y + ts - 2, y + 4, y + ts - 2 };
                    gc.fillPolygon(px, py, 3);
                }
            }
            case SPAWN -> {
                // Arrow pointing up (spawn direction)
                gc.setFill(t.border);
                double mx = x + ts / 2, my = y + ts / 2;
                double aw = ts * 0.25;
                gc.fillPolygon(
                    new double[]{ mx, mx - aw, mx + aw },
                    new double[]{ y + ts * 0.2, y + ts * 0.7, y + ts * 0.7 },
                    3
                );
            }
            case WATER -> {
                // Wave line across middle
                gc.setStroke(Color.web("#4488ff", 0.5));
                gc.setLineWidth(Math.max(1, ts * 0.06));
                double wy = y + ts * 0.5;
                int    segs = Math.max(2, (int)(ts / 6));
                double segW = ts / segs;
                gc.beginPath();
                gc.moveTo(x, wy);
                for (int i = 0; i < segs; i++) {
                    double cpx = x + i * segW + segW / 2;
                    double endX = x + (i + 1) * segW;
                    double amp = ts * 0.08 * (i % 2 == 0 ? 1 : -1);
                    gc.quadraticCurveTo(cpx, wy + amp, endX, wy);
                }
                gc.stroke();
            }
            case PLATFORM -> {
                // Top highlight line
                gc.setStroke(Color.web("#88aaff", 0.5));
                gc.setLineWidth(Math.max(1, ts * 0.06));
                gc.strokeLine(x + 2, y + 2, x + ts - 2, y + 2);
            }
            default -> {}
        }
    }

    // ── Mouse interaction ─────────────────────────────────────────────────────

    private void handleMouse(MouseEvent e, Canvas canvas) {
        int[] tc = canvasToTile(e.getX(), e.getY());
        if (tc == null) return;
        if (e.getButton() == MouseButton.PRIMARY) {
            board[tc[0]][tc[1]] = selectedTile;
        } else if (e.getButton() == MouseButton.SECONDARY) {
            board[tc[0]][tc[1]] = BoardTile.AIR;
        }
        drawBoard(canvas);
    }

    private int[] canvasToTile(double mouseX, double mouseY) {
        double ts = TILE_PX * zoom;
        int c = (int)((mouseX + offsetX) / ts);
        int r = (int)((mouseY + offsetY) / ts);
        if (r < 0 || r >= rows || c < 0 || c >= cols) return null;
        return new int[]{ r, c };
    }

    // ── Board operations ──────────────────────────────────────────────────────

    private void clearBoard() {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                board[r][c] = BoardTile.AIR;
    }

    private void fillLevel() {
        int row = (cursorRow >= 0 && cursorRow < rows) ? cursorRow : rows - 1;
        for (int c = 0; c < cols; c++)
            board[row][c] = selectedTile;
    }

    private void resizeBoard(int newRows, int newCols) {
        BoardTile[][] newBoard = new BoardTile[newRows][newCols];
        for (int r = 0; r < newRows; r++)
            for (int c = 0; c < newCols; c++)
                newBoard[r][c] = (r < rows && c < cols) ? board[r][c] : BoardTile.AIR;
        rows  = newRows;
        cols  = newCols;
        board = newBoard;
    }

    /** Returns a deep copy of the current board. */
    private BoardTile[][] copyBoard() {
        BoardTile[][] copy = new BoardTile[rows][cols];
        for (int r = 0; r < rows; r++)
            copy[r] = board[r].clone();
        return copy;
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private String tileButtonStyle(BoardTile t, boolean selected) {
        String bg = selected
                ? t.fill.equals(Color.TRANSPARENT) ? "#2a2a4a" : toHex(t.fill)
                : "#1a1a2e";
        String fg = selected ? "#ffffff" : "#9090b0";
        return "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; " +
               "-fx-border-color: " + toHex(t.border) + "; -fx-border-width: 1; " +
               "-fx-border-radius: 3; -fx-background-radius: 3;";
    }

    private String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int)(c.getRed()   * 255),
                (int)(c.getGreen() * 255),
                (int)(c.getBlue()  * 255));
    }

    private void styleSpinner(Spinner<?> s) {
        s.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: #c8c8e8;");
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    /**
     * Copies all CSVs from ~/.game/boards/ into ~/.game/boards/backups/YYYY-MM-DD/
     * once per calendar day. Silently skips if the backup already exists or there
     * are no boards to back up yet.
     */
    private static void dailyBackup() {
        File boardsDir = boardsDir();
        File[] csvFiles = boardsDir.listFiles((d, n) -> n.endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) return;

        File backupDir = new File(boardsDir, "backups/" + LocalDate.now());
        if (backupDir.exists()) return;  // already backed up today
        backupDir.mkdirs();

        for (File src : csvFiles) {
            try {
                Files.copy(src.toPath(), new File(backupDir, src.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    /** Returns (creating if needed) ~/.game/boards/ as the default save directory. */
    private static File boardsDir() {
        File dir = new File(System.getProperty("user.home"), ".game/boards");
        dir.mkdirs();
        return dir;
    }

    /**
     * Saves the board as CSV.
     * Line 0: "rows,cols"  — dimensions header.
     * Lines 1+: one row per line, tile names comma-separated.
     */
    private boolean saveBoard(File f) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            w.write(rows + "," + cols);
            w.newLine();
            for (int r = 0; r < rows; r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    if (c > 0) sb.append(',');
                    sb.append(board[r][c].name());
                }
                w.write(sb.toString());
                w.newLine();
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Loads a CSV board saved by {@link #saveBoard}. Returns true on success. */
    private boolean loadBoard(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String header = r.readLine();
            if (header == null) return false;
            String[] dims = header.split(",");
            int newRows = Integer.parseInt(dims[0].trim());
            int newCols = Integer.parseInt(dims[1].trim());

            BoardTile[][] newBoard = new BoardTile[newRows][newCols];
            for (int row = 0; row < newRows; row++) {
                String line = r.readLine();
                if (line == null) return false;
                String[] cells = line.split(",", -1);
                for (int col = 0; col < newCols; col++) {
                    try {
                        newBoard[row][col] = BoardTile.valueOf(cells[col].trim());
                    } catch (Exception e) {
                        newBoard[row][col] = BoardTile.AIR;
                    }
                }
            }
            rows  = newRows;
            cols  = newCols;
            board = newBoard;
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
