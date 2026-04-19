package com.game.client.state;

import com.game.client.ClientConfig;
import com.game.client.UDPClient;
import com.game.shared.Packet;
import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages the UDP connection lifecycle.
 *
 * Other AppStates register packet listeners here rather than holding a
 * direct reference to UDPClient. Listeners are called on the network
 * receive thread — use app.enqueue() to touch scene-graph objects.
 */
public class NetworkAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(NetworkAppState.class);

    private UDPClient udpClient;
    private final CopyOnWriteArrayList<Consumer<Packet>> listeners = new CopyOnWriteArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application app) {
        ClientConfig config = new ClientConfig();
        udpClient = new UDPClient(config);
        udpClient.setPacketListener(pkt -> listeners.forEach(l -> l.accept(pkt)));
        try {
            udpClient.start();
            log.info("UDP client started — {}:{}", config.getServerHost(), config.getServerPort());
        } catch (Exception e) {
            log.error("Failed to start UDP client: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void cleanup(Application app) {
        if (udpClient != null) udpClient.stop();
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    // ── API ───────────────────────────────────────────────────────────────────

    public void send(Packet packet) {
        if (udpClient != null) udpClient.send(packet);
    }

    public void addListener(Consumer<Packet> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Packet> listener) {
        listeners.remove(listener);
    }
}
