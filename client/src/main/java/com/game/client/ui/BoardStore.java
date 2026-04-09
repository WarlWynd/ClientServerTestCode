package com.game.client.ui;

/** Shared board state — set by Board Dev tab, read by Game tab. */
public final class BoardStore {

    private static BoardTile[][] board;
    private static int rows;
    private static int cols;
    private static String name = "";

    private BoardStore() {}

    public static void set(BoardTile[][] b, int r, int c) {
        board = b;
        rows  = r;
        cols  = c;
        name  = "";
    }

    public static void set(BoardTile[][] b, int r, int c, String boardName) {
        board = b;
        rows  = r;
        cols  = c;
        name  = boardName != null ? boardName : "";
    }

    public static void clear() { board = null; name = ""; }

    public static boolean       isLoaded()  { return board != null; }
    public static BoardTile[][] getBoard()  { return board; }
    public static int           getRows()   { return rows; }
    public static int           getCols()   { return cols; }
    public static String        getName()   { return name; }
}
