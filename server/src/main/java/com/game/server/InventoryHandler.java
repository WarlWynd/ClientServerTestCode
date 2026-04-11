package com.game.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.server.db.InventoryRepository;
import com.game.server.model.Session;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/**
 * Handles all inventory-related UDP packets.
 *
 * Packet flow:
 *   INVENTORY_REQUEST          → fetch from DB → INVENTORY_RESPONSE
 *   INVENTORY_GIVE_ITEM_REQUEST → add item to DB → INVENTORY_GIVE_ITEM_RESPONSE
 *   INVENTORY_EQUIP_REQUEST     → toggle equipped in DB → INVENTORY_EQUIP_RESPONSE
 *   INVENTORY_DROP_REQUEST      → decrement/delete in DB → INVENTORY_DROP_RESPONSE
 */
public class InventoryHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryHandler.class);

    private final InventoryRepository repo = new InventoryRepository();

    // ── Handlers ─────────────────────────────────────────────────────────────

    public void handleRequest(DatagramSocket socket, Packet in, Session session,
                              InetAddress addr, int port) throws Exception {
        List<InventoryRepository.InventoryEntry> entries = repo.getInventory(session.userId());

        InventoryRepository.CurrencyRecord currency = repo.getCurrency(session.userId());

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("platinum", currency.platinum());
        payload.put("gold",     currency.gold());
        payload.put("silver",   currency.silver());
        payload.put("bronze",   currency.bronze());
        ArrayNode  items   = payload.putArray("items");
        for (InventoryRepository.InventoryEntry e : entries) {
            ObjectNode item = items.addObject();
            item.put("id",       e.id());
            item.put("itemName", e.itemName());
            item.put("quantity", e.quantity());
            item.put("equipped", e.equipped());
        }
        send(socket, PacketType.INVENTORY_RESPONSE, in.sessionToken, payload, addr, port);
        log.debug("INVENTORY_REQUEST user='{}' entries={}", session.username(), entries.size());
    }

    public void handleGiveItem(DatagramSocket socket, Packet in, Session session,
                               InetAddress addr, int port) throws Exception {
        String itemName = in.payload.has("itemName") ? in.payload.get("itemName").asText("").trim() : "";
        int    quantity = in.payload.has("quantity")  ? Math.max(1, in.payload.get("quantity").asInt(1)) : 1;

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        if (itemName.isBlank()) {
            payload.put("success", false);
            payload.put("message", "Invalid item name.");
        } else {
            boolean ok = repo.addItem(session.userId(), itemName, quantity);
            payload.put("success", ok);
            payload.put("message", ok
                    ? "Added " + quantity + "x " + itemName + " to inventory."
                    : "Could not add item — no character found for this account.");
            if (ok) log.info("INVENTORY_GIVE user='{}' item='{}' qty={}", session.username(), itemName, quantity);
        }
        send(socket, PacketType.INVENTORY_GIVE_ITEM_RESPONSE, in.sessionToken, payload, addr, port);
    }

    public void handleEquip(DatagramSocket socket, Packet in, Session session,
                            InetAddress addr, int port) throws Exception {
        long    entryId  = in.payload.has("entryId")  ? in.payload.get("entryId").asLong()      : -1L;
        boolean equipped = in.payload.has("equipped")  ? in.payload.get("equipped").asBoolean()  : false;

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        if (entryId < 0) {
            payload.put("success", false);
        } else {
            boolean ok = repo.setEquipped(entryId, session.userId(), equipped);
            payload.put("success",  ok);
            payload.put("entryId",  entryId);
            payload.put("equipped", equipped);
        }
        send(socket, PacketType.INVENTORY_EQUIP_RESPONSE, in.sessionToken, payload, addr, port);
    }

    public void handleDrop(DatagramSocket socket, Packet in, Session session,
                           InetAddress addr, int port) throws Exception {
        long entryId  = in.payload.has("entryId")  ? in.payload.get("entryId").asLong()  : -1L;
        int  quantity = in.payload.has("quantity")  ? Math.max(1, in.payload.get("quantity").asInt(1)) : 1;

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        if (entryId < 0) {
            payload.put("success", false);
        } else {
            boolean ok = repo.dropItem(entryId, session.userId(), quantity);
            payload.put("success", ok);
            payload.put("entryId", entryId);
            if (ok) log.info("INVENTORY_DROP user='{}' entryId={} qty={}", session.username(), entryId, quantity);
        }
        send(socket, PacketType.INVENTORY_DROP_RESPONSE, in.sessionToken, payload, addr, port);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void send(DatagramSocket socket, PacketType type, String token,
                      ObjectNode payload, InetAddress addr, int port) throws Exception {
        byte[] data = PacketSerializer.serialize(new Packet(type, token, payload));
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
