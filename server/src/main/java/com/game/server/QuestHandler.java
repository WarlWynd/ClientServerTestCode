package com.game.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.QuestRepository;
import com.game.server.model.Session;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles all quest-related UDP packets.
 *
 * Packet flow:
 *   QUEST_LIST_REQUEST    → load definitions + progress → QUEST_LIST_RESPONSE
 *   QUEST_ACCEPT_REQUEST  → insert progress row         → QUEST_ACCEPT_RESPONSE
 *   QUEST_ABANDON_REQUEST → delete progress row         → QUEST_ABANDON_RESPONSE
 *
 * Quest definitions are loaded once from quests.json on the classpath.
 */
public class QuestHandler {

    private static final Logger log = LoggerFactory.getLogger(QuestHandler.class);

    private final QuestRepository repo = new QuestRepository();

    /** questId → full definition node, loaded once at construction. */
    private final Map<String, JsonNode> definitions = new HashMap<>();

    public QuestHandler() {
        loadDefinitions();
    }

    // ── Definition loading ────────────────────────────────────────────────────

    private void loadDefinitions() {
        try (InputStream in = getClass().getResourceAsStream("/quests.json")) {
            if (in == null) { log.warn("quests.json not found on classpath"); return; }
            JsonNode arr = new ObjectMapper().readTree(in);
            if (arr.isArray()) {
                for (JsonNode q : arr) {
                    definitions.put(q.get("id").asText(), q);
                }
            }
            log.info("Loaded {} quest definition(s).", definitions.size());
        } catch (Exception e) {
            log.error("Failed to load quests.json: {}", e.getMessage());
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    public void handleList(DatagramSocket socket, Packet in, Session session,
                           InetAddress addr, int port) throws Exception {
        List<QuestRepository.QuestProgress> progress = repo.getProgress(session.userId());

        // Build a map: questId → status for quick lookup
        Map<String, String> statusMap = new HashMap<>();
        for (QuestRepository.QuestProgress p : progress) {
            statusMap.put(p.questId(), p.status());
        }

        // Build progress map: questId → killProgress
        Map<String, Integer> killProgressMap = new HashMap<>();
        for (QuestRepository.QuestProgress p : progress) {
            killProgressMap.put(p.questId(), p.killProgress());
        }

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        ArrayNode  quests  = payload.putArray("quests");

        for (JsonNode def : definitions.values()) {
            String questId     = def.get("id").asText();
            String status      = statusMap.getOrDefault(questId, "AVAILABLE");
            int    killProgress = killProgressMap.getOrDefault(questId, 0);

            ObjectNode q = quests.addObject();
            q.put("id",           questId);
            q.put("name",         def.get("name").asText());
            q.put("description",  def.get("description").asText());
            q.put("status",       status);
            q.put("killProgress", killProgress);
            q.set("objectives",   def.get("objectives"));
            q.set("rewards",      def.get("rewards"));
        }

        send(socket, PacketType.QUEST_LIST_RESPONSE, in.sessionToken, payload, addr, port);
        log.debug("QUEST_LIST user='{}'", session.username());
    }

    public void handleAccept(DatagramSocket socket, Packet in, Session session,
                             InetAddress addr, int port) throws Exception {
        String questId = in.payload.has("questId") ? in.payload.get("questId").asText("").trim() : "";

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("questId", questId);

        if (questId.isBlank() || !definitions.containsKey(questId)) {
            payload.put("success", false);
            payload.put("message", "Unknown quest.");
        } else {
            boolean ok = repo.acceptQuest(session.userId(), questId);
            payload.put("success", ok);
            payload.put("message", ok ? "Quest accepted." : "Quest already active or completed.");
            if (ok) log.info("QUEST_ACCEPT user='{}' quest='{}'", session.username(), questId);
        }

        send(socket, PacketType.QUEST_ACCEPT_RESPONSE, in.sessionToken, payload, addr, port);
    }

    public void handleMobKilled(DatagramSocket socket, Packet in, Session session,
                               InetAddress addr, int port) throws Exception {
        String mobType = in.payload.has("mobType") ? in.payload.get("mobType").asText("any") : "any";

        List<String> completed = repo.incrementKillProgress(session.userId(), mobType, definitions);

        // Push an updated quest list so the client panel refreshes automatically
        handleList(socket, in, session, addr, port);

        if (!completed.isEmpty()) {
            log.info("MOB_KILLED user='{}' mob='{}' completedQuests={}", session.username(), mobType, completed);
        }
    }

    public void handleAbandon(DatagramSocket socket, Packet in, Session session,
                              InetAddress addr, int port) throws Exception {
        String questId = in.payload.has("questId") ? in.payload.get("questId").asText("").trim() : "";

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("questId", questId);

        if (questId.isBlank()) {
            payload.put("success", false);
        } else {
            boolean ok = repo.abandonQuest(session.userId(), questId);
            payload.put("success", ok);
            if (ok) log.info("QUEST_ABANDON user='{}' quest='{}'", session.username(), questId);
        }

        send(socket, PacketType.QUEST_ABANDON_RESPONSE, in.sessionToken, payload, addr, port);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void send(DatagramSocket socket, PacketType type, String token,
                      ObjectNode payload, InetAddress addr, int port) throws Exception {
        byte[] data = PacketSerializer.serialize(new Packet(type, token, payload));
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
