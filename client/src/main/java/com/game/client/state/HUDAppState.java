package com.game.client.state;

import com.game.client.SessionStore;
import com.game.shared.Packet;
import com.game.shared.PacketType;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.scene.Node;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lemur HUD overlay — HP/Mana bars, player name, minimap placeholder.
 *
 * Listens for PLAYER_STATE packets to keep stats current.
 */
public class HUDAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(HUDAppState.class);

    private final NetworkAppState network;
    private Node  guiNode;
    private Container hudRoot;

    // Stat labels updated by incoming packets
    private Label hpLabel;
    private Label manaLabel;
    private Label nameLabel;

    public HUDAppState(NetworkAppState network) {
        this.network = network;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application app) {
        guiNode = ((SimpleApplication) app).getGuiNode();
        buildHud(app);
        network.addListener(this::onPacket);
    }

    @Override
    protected void cleanup(Application app) {
        network.removeListener(this::onPacket);
        if (hudRoot != null) hudRoot.removeFromParent();
    }

    @Override protected void onEnable()  { if (hudRoot != null) guiNode.attachChild(hudRoot); }
    @Override protected void onDisable() { if (hudRoot != null) hudRoot.removeFromParent(); }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void buildHud(Application app) {
        int screenW = app.getCamera().getWidth();
        int screenH = app.getCamera().getHeight();

        hudRoot = new Container();

        nameLabel = hudRoot.addChild(new Label(SessionStore.getUsername()));
        hpLabel   = hudRoot.addChild(new Label("HP: —"));
        manaLabel = hudRoot.addChild(new Label("MP: —"));

        // Anchor bottom-left
        hudRoot.setLocalTranslation(10, 90, 0);

        guiNode.attachChild(hudRoot);
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.GAME_STATE) return;
        int hp   = packet.payload.path("hp").asInt(-1);
        int mana = packet.payload.path("mana").asInt(-1);
        if (hp < 0 && mana < 0) return;
        getApplication().enqueue(() -> {
            if (hp   >= 0) hpLabel.setText("HP: " + hp);
            if (mana >= 0) manaLabel.setText("MP: " + mana);
        });
    }
}
