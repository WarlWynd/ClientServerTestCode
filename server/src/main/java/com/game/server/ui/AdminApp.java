package com.game.server.ui;

import com.game.server.GameHandler;
import com.game.server.db.SoftwareVersionRepository;
import javafx.application.Application;
import javafx.stage.Stage;

public class AdminApp extends Application {

    private static GameHandler               gameHandler;
    private static int                       serverPort;
    private static SoftwareVersionRepository versionRepo;

    /** Called by ServerMain before Application.launch(). */
    public static void init(GameHandler gh, int port, SoftwareVersionRepository repo) {
        gameHandler = gh;
        serverPort  = port;
        versionRepo = repo;
    }

    @Override
    public void start(Stage stage) {
        new AdminWindow(stage, gameHandler, serverPort, versionRepo).show();
    }
}
