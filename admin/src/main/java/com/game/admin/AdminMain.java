package com.game.admin;

import com.game.admin.ui.LoginScreen;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AdminMain extends Application {

    private static final Logger log = LoggerFactory.getLogger(AdminMain.class);
    private AdminUDPClient udpClient;

    @Override
    public void start(Stage stage) throws Exception {
        AdminConfig config = new AdminConfig();

        stage.setTitle("Admin Console");
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> shutdown());

        udpClient = new AdminUDPClient(config);
        udpClient.start();

        new LoginScreen(stage, udpClient).show();
    }

    @Override
    public void stop() { shutdown(); }

    private void shutdown() {
        if (udpClient != null) udpClient.stop();
        log.info("Admin tool shut down.");
        Platform.exit();
    }

    public static void main(String[] args) { launch(args); }
}
