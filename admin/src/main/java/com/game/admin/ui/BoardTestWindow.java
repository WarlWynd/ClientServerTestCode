package com.game.admin.ui;

import javafx.scene.input.KeyCode;

import java.util.function.Consumer;

/**
 * Opens only the 3D first-person view for a Board.
 * Player movement is reported back via onMove so the Board Editor canvas
 * can draw the position indicator without a separate top-down window.
 */
public class BoardTestWindow {

    private static final int[] DX = { 0,  1,  0, -1};
    private static final int[] DZ = {-1,  0,  1,  0};

    private final BoardDevPanel.Board board;
    private final Consumer<int[]>     onMove;   // {px, pz, facing}
    private final Runnable            onClose;

    private int px, pz, facing;

    private Board3DViewWindow view3D;

    public BoardTestWindow(BoardDevPanel.Board board, Consumer<int[]> onMove, Runnable onClose) {
        this.board   = board;
        this.onMove  = onMove;
        this.onClose = onClose;
        this.px      = board.spawnX;
        this.pz      = board.spawnZ;
        this.facing  = 2;  // start facing SOUTH
    }

    public void show() {
        view3D = new Board3DViewWindow(board);
        view3D.show();
        view3D.updatePlayer(px, pz, facing);
        view3D.getStage().getScene().setOnKeyPressed(e -> handleKey(e.getCode()));
        view3D.getStage().setOnCloseRequest(e -> onClose.run());
        onMove.accept(new int[]{px, pz, facing});
    }

    public void close() {
        if (view3D != null && view3D.getStage() != null) view3D.getStage().close();
    }

    private void handleKey(KeyCode code) {
        switch (code) {
            case W, UP       -> tryMove( DX[facing],  DZ[facing]);
            case S, DOWN     -> tryMove(-DX[facing], -DZ[facing]);
            case PAGE_UP     -> { view3D.adjustPitch(-5); return; }
            case PAGE_DOWN   -> { view3D.adjustPitch(+5); return; }
            case A, LEFT     -> facing = (facing + 3) % 4;
            case D, RIGHT    -> facing = (facing + 1) % 4;
            default -> { return; }
        }
        view3D.updatePlayer(px, pz, facing);
        onMove.accept(new int[]{px, pz, facing});
    }

    private void tryMove(int dx, int dz) {
        int    nx   = px + dx;
        int    nz   = pz + dz;
        String tile = board.tileAt(nx, nz);
        if (!tile.equals("WALL") && !tile.equals("VOID")) { px = nx; pz = nz; }
    }
}
