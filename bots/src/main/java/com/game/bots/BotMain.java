package com.game.bots;

import com.game.bots.ui.BotControlPanel;
import javafx.application.Application;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** Entry point for the Bot Test Unit. */
public class BotMain extends Application {

    @Override
    public void start(Stage stage) {
        BotConfig config = new BotConfig();

        List<BotClient> bots = new ArrayList<>();
        for (int i = 1; i <= config.getBotCount(); i++) {
            String name = String.format("%s%02d", config.getBotPrefix(), i);
            bots.add(new BotClient(name, config.getBotPassword(),
                    config.getServerHost(), config.getServerPort()));
        }

        new BotControlPanel(stage, bots,
                config.getServerHost(), config.getServerPort()).show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
