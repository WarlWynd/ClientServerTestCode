package com.game.server;

import com.game.server.db.DatabaseManager;
import com.game.server.db.UserRepository;
import com.game.server.ui.AdminApp;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application entry point for the game server.
 *
 * Start with:
 *   ./gradlew :server:run
 * or build a fat JAR:
 *   ./gradlew :server:jar
 *   java -jar server/build/libs/game-server.jar
 */
public class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) {
        ServerConfig config = new ServerConfig();

        log.info("Connecting to MySQL at {}:{}/{} …",
                config.getDbHost(), config.getDbPort(), config.getDbName());

        DatabaseManager.initialize(
                config.getDbHost(),
                config.getDbPort(),
                config.getDbName(),
                config.getDbUser(),
                config.getDbPassword()
        );

        new UserRepository().createAdminIfNotExists(
                config.getAdminUsername(), config.getAdminPassword());

        log.info("Server version: {}", config.getVersion());
        UDPServer server = new UDPServer(config.getPort(), config.getVersion());

        AssetHttpServer assetServer = new AssetHttpServer(
                config.getHttpPort(), config.getAssetsDir(), server.getAuthHandler());
        try {
            assetServer.start();
        } catch (Exception e) {
            log.error("Failed to start asset HTTP server: {}", e.getMessage(), e);
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                .name("shutdown-hook")
                .unstarted(() -> {
                    log.info("Shutdown signal received — stopping server …");
                    server.stop();
                    assetServer.stop();
                }));

        // Run UDP server on a background thread so JavaFX can own the main thread
        Thread serverThread = Thread.ofPlatform()
                .name("udp-server")
                .start(() -> {
                    try {
                        server.start();
                    } catch (Exception e) {
                        log.error("Fatal server error: {}", e.getMessage(), e);
                        System.exit(1);
                    }
                });

        // Pass the GameHandler to the admin UI before launching
        AdminApp.init(server.getGameHandler(), config.getPort());
        Application.launch(AdminApp.class, args);

        // JavaFX exited — stop the server
        server.stop();
    }
}
