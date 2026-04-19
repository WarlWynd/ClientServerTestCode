package com.game.client.desktop;

import com.game.client.ClientMain;
import com.game.client.SessionStore;
import com.jme3.system.AppSettings;
import javafx.application.Application;

public class DesktopLauncher {

    public static void main(String[] args) {
        Application.launch(LoginWindow.class, args);

        if (!SessionStore.isLoggedIn()) return;

        ClientMain app = new ClientMain();
        AppSettings settings = new AppSettings(true);
        settings.setTitle(com.game.client.AppSettings.getProgramName());
        settings.setResolution(1280, 720);
        settings.setFullscreen(false);
        settings.setVSync(true);
        settings.setSamples(4);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
