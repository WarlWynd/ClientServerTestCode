package com.game.admin;

import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.function.Consumer;

/** UDP client for the admin tool. Uses a large receive buffer for player-list responses. */
public class AdminUDPClient {

    private static final Logger log = LoggerFactory.getLogger(AdminUDPClient.class);
    private static final int    RECV_BUFFER_SIZE = 65507; // max UDP payload

    private final String serverHost;
    private final int    serverPort;
    private final int    localPort;

    private DatagramSocket   socket;
    private InetAddress      serverAddr;
    private volatile boolean running;
    private Consumer<Packet> listener;

    public AdminUDPClient(AdminConfig config) {
        this.serverHost = config.getServerHost();
        this.serverPort = config.getServerPort();
        this.localPort  = config.getClientPort();
    }

    public void start() throws Exception {
        serverAddr = InetAddress.getByName(serverHost);
        socket     = (localPort == 0) ? new DatagramSocket() : new DatagramSocket(localPort);
        running    = true;
        Thread.ofVirtual().name("admin-udp-receiver").start(this::receiveLoop);
        log.info("Admin UDP client started -> {}:{}", serverHost, serverPort);
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
        log.info("Admin UDP client stopped.");
    }

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

    public void setPacketListener(Consumer<Packet> listener) {
        this.listener = listener;
    }

    private void receiveLoop() {
        byte[] buf = new byte[RECV_BUFFER_SIZE];
        while (running) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.receive(dp);

                byte[] trimmed = new byte[dp.getLength()];
                System.arraycopy(dp.getData(), 0, trimmed, 0, dp.getLength());

                Packet packet = PacketSerializer.deserialize(trimmed);
                if (listener != null) listener.accept(packet);
            } catch (Exception e) {
                if (running) log.error("Receive error: {}", e.getMessage(), e);
            }
        }
    }
}
