package com.game.client;

import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.function.Consumer;

/**
 * Thin UDP client wrapper.
 *
 * A single background virtual thread continuously reads inbound datagrams
 * and dispatches them to the registered {@link Consumer<Packet>} callback
 * (always on the background thread — callers must marshal to the JavaFX
 * Application Thread themselves via {@code Platform.runLater(…)}).
 *
 * Usage:
 * <pre>
 *   UDPClient client = new UDPClient(config);
 *   client.setPacketListener(packet -> Platform.runLater(() -> handle(packet)));
 *   client.start();
 *   client.send(myPacket);
 *   // …later…
 *   client.stop();
 * </pre>
 */
public class UDPClient {

    private static final Logger log = LoggerFactory.getLogger(UDPClient.class);
    private static final int    RECV_BUFFER_SIZE = 4096;

    private final String serverHost;
    private final int    serverPort;
    private final int    localPort;

    private DatagramSocket    socket;
    private InetAddress       serverAddr;
    private volatile boolean  running;
    private Consumer<Packet>  listener;
    private Thread            receiverThread;

    public UDPClient(ClientConfig config) {
        this.serverHost = config.getServerHost();
        this.serverPort = config.getServerPort();
        this.localPort  = config.getClientPort();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() throws Exception {
        serverAddr = InetAddress.getByName(serverHost);
        socket     = (localPort == 0)
                ? new DatagramSocket()
                : new DatagramSocket(localPort);
        running    = true;

        receiverThread = Thread.ofVirtual()
                .name("udp-receiver")
                .start(this::receiveLoop);

        log.info("UDP client started → {}:{}", serverHost, serverPort);
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
        log.info("UDP client stopped.");
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    public void send(Packet packet) {
        if (socket == null || socket.isClosed()) {
            log.warn("Cannot send — socket is not open.");
            return;
        }
        try {
            byte[] data = PacketSerializer.serialize(packet);
            socket.send(new DatagramPacket(data, data.length, serverAddr, serverPort));
        } catch (Exception e) {
            log.error("Send error: {}", e.getMessage(), e);
        }
    }

    // ── Receive loop ─────────────────────────────────────────────────────────

    private void receiveLoop() {
        byte[] buf = new byte[RECV_BUFFER_SIZE];
        while (running) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.receive(dp);                   // blocks

                byte[] trimmed = new byte[dp.getLength()];
                System.arraycopy(dp.getData(), 0, trimmed, 0, dp.getLength());

                Packet packet = PacketSerializer.deserialize(trimmed);
                if (listener != null) listener.accept(packet);
            } catch (Exception e) {
                if (running) log.error("Receive error: {}", e.getMessage(), e);
            }
        }
    }

    // ── Configuration ────────────────────────────────────────────────────────

    /** Register a callback invoked for every inbound packet (on receiver thread). */
    public void setPacketListener(Consumer<Packet> listener) {
        this.listener = listener;
    }
}
