package com.game.client;

import com.game.client.ui.LoginScreen;
import com.game.client.ui.UpdateScreen;
import com.game.client.MobilePlatform;
import com.game.client.ui.VersionCheckScreen;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX Application entry point.
 *
 * Start with:
 *   ./gradlew :client:run
 */
public class ClientMain extends Application {

    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

    private UDPClient udpClient;

    @Override
    public void start(Stage primaryStage) throws Exception {
        ClientConfig config = new ClientConfig();

        primaryStage.setOnCloseRequest(e -> shutdown());

        if (MobilePlatform.isMobile()) {
            launchGame(primaryStage, config);
        } else {
            new UpdateScreen(primaryStage, config.getVersion(), () -> launchGame(primaryStage, config)).show();
        }
    }

    private void launchGame(Stage primaryStage, ClientConfig config) {
        udpClient = new UDPClient(config);
        try {
            udpClient.start();
        } catch (Exception e) {
            log.error("Failed to start UDP client: {}", e.getMessage(), e);
            Platform.exit();
            return;
        }
        new VersionCheckScreen(primaryStage, udpClient, config.getVersion(),
                () -> new LoginScreen(primaryStage, udpClient, config.getVersion()).show()).show();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (udpClient != null) udpClient.stop();
        log.info("Client shut down.");
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
