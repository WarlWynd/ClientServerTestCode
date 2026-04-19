package com.game.client;

import com.game.client.state.LoginAppState;
import com.game.client.state.NetworkAppState;
import com.jme3.app.SimpleApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jMonkeyEngine entry point for the 3D open-world client.
 *
 * Boot order:
 *   1. NetworkAppState  — UDP connection lifecycle
 *   2. LoginAppState    — Lemur login form (attaches WorldAppState on success)
 */
public class ClientMain extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

    public static void main(String[] args) {
        ClientMain app = new ClientMain();

        com.jme3.system.AppSettings settings = new com.jme3.system.AppSettings(true);
        settings.setTitle(com.game.client.AppSettings.getProgramName());
        settings.setResolution(1280, 720);
        settings.setFullscreen(false);
        settings.setVSync(true);
        settings.setSamples(4);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // Disable the default fly-cam — the game will supply its own camera control
        flyCam.setEnabled(false);

        NetworkAppState network = new NetworkAppState();
        LoginAppState   login   = new LoginAppState(network);

        stateManager.attachAll(network, login);
    }

    @Override
    public void simpleUpdate(float tpf) {
        // Per-frame logic handled by AppStates
    }
}
