package com.game.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Every UDP datagram is serialised as one Packet (JSON bytes).
 *
 * Layout:
 *   type          – what kind of message this is
 *   sessionToken  – null for unauthenticated packets (login / register / ping)
 *   sequence      – monotonically increasing per-client counter (for loss detection)
 *   timestamp     – sender wall-clock ms (for latency estimation)
 *   payload       – packet-type-specific JSON object
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Packet {

    public PacketType type;
    public String     sessionToken;   // null ⟹ unauthenticated
    public long       sequence;
    public long       timestamp;
    public JsonNode   payload;        // arbitrary JSON object for this packet type

    /** Required by Jackson */
    public Packet() {}

    public Packet(PacketType type, String sessionToken, JsonNode payload) {
        this.type         = type;
        this.sessionToken = sessionToken;
        this.sequence     = 0;
        this.timestamp    = System.currentTimeMillis();
        this.payload      = payload;
    }
}
