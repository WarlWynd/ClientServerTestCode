package com.game.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Thin wrapper around Jackson for converting Packets to/from raw byte arrays
 * suitable for UDP datagrams.
 *
 * Max safe UDP payload is ~65 507 bytes; in practice keep game packets < 1 400 bytes
 * to stay below typical MTU and avoid IP fragmentation.
 */
public final class PacketSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PacketSerializer() {}

    public static byte[] serialize(Packet packet) throws Exception {
        return MAPPER.writeValueAsBytes(packet);
    }

    public static Packet deserialize(byte[] data) throws Exception {
        return MAPPER.readValue(data, Packet.class);
    }

    /** Shared mapper instance — use for building payload ObjectNodes. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Convenience: create an empty payload node. */
    public static ObjectNode emptyPayload() {
        return MAPPER.createObjectNode();
    }
}
