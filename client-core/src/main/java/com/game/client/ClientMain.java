package com.game.client;

import com.game.client.state.DungeonWorldAppState;
import com.game.client.state.NetworkAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.math.ColorRGBA;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.style.Styles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-independent jME application.
 * Each platform module supplies its own launcher that configures and starts this class.
 *
 * Boot order:
 *   1. NetworkAppState      — UDP connection lifecycle
 *   2. DungeonWorldAppState — 3D world (login already completed via JavaFX LoginWindow)
 */
public class ClientMain extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);

        // Prevent Escape from killing the app
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);

        GuiGlobals.initialize(this);
        applyStyles();

        NetworkAppState network = new NetworkAppState();
        stateManager.attachAll(network, new DungeonWorldAppState(network));
    }

    @Override
    public void simpleUpdate(float tpf) {}

    private void applyStyles() {
        Styles styles = GuiGlobals.getInstance().getStyles();
        ColorRGBA panelBg  = new ColorRGBA(0.1f, 0.1f, 0.15f, 0.85f);
        ColorRGBA fieldBg  = new ColorRGBA(0.2f, 0.2f, 0.25f, 1f);
        ColorRGBA btnBg    = new ColorRGBA(0.25f, 0.4f, 0.65f, 1f);
        ColorRGBA white    = ColorRGBA.White;

        styles.getSelector(Container.ELEMENT_ID, null)
              .set("background", new QuadBackgroundComponent(panelBg));
        styles.getSelector(Label.ELEMENT_ID, null)
              .set("color", white);
        styles.getSelector(TextField.ELEMENT_ID, null)
              .set("background", new QuadBackgroundComponent(fieldBg));
        styles.getSelector(TextField.ELEMENT_ID, null)
              .set("color", white);
        styles.getSelector(Button.ELEMENT_ID, null)
              .set("background", new QuadBackgroundComponent(btnBg));
        styles.getSelector(Button.ELEMENT_ID, null)
              .set("color", white);
    }
}
