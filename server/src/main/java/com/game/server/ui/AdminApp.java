package com.game.server.ui;

import com.game.server.GameHandler;
import javafx.application.Application;
import javafx.stage.Stage;

public class AdminApp extends Application {

    private static GameHandler gameHandler;
    private static int         serverPort;

    /** Called by ServerMain before Application.launch(). */
    public static void init(GameHandler gh, int port) {
        gameHandler = gh;
        serverPort  = port;
    }

    @Override
    public void start(Stage stage) {
        new AdminWindow(stage, gameHandler, serverPort).show();
    }
}
