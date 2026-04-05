package com.game.server;

import com.game.server.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application entry point for the game server.
 *
 * Start with:
 *   ./gradlew :server:run
 * or build a fat JAR:
 *   ./gradlew :server:jar
 *   java --enable-preview -jar server/build/libs/game-server.jar
 */
public class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) {
        ServerConfig config = new ServerConfig();

        log.info("*** Server starting up …");
        log.info("Connecting to MySQL at {}:{}/{} …",
                config.getDbHost(), config.getDbPort(), config.getDbName());

        DatabaseManager.initialize(
                config.getDbHost(),
                config.getDbPort(),
                config.getDbName(),
                config.getDbUser(),
                config.getDbPassword()
        );

        UDPServer server = new UDPServer(config.getPort());

        AssetHttpServer assetServer = new AssetHttpServer(
                config.getHttpPort(), config.getAssetsDir(), server.getAuthHandler(),
                config.getClientSyncTypes(), config.getUploadKey());
        try {
            assetServer.start();
        } catch (Exception e) {
            log.error("Failed to start asset HTTP server: {}", e.getMessage(), e);
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                .name("shutdown-hook")
                .unstarted(() -> {
                    log.info("*** Server process shutting down — cleaning up …");
                    server.stop();
                    assetServer.stop();
                    log.info("*** Shutdown complete.");
                }));

        try {
            server.start();
        } catch (Exception e) {
            log.error("Fatal server error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
