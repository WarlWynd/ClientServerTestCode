package com.game.server.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Data-access object for character_inventory.
 * All methods identify inventory by userId; character lookup is handled internally.
 */
public class InventoryRepository {

    private static final Logger log = LoggerFactory.getLogger(InventoryRepository.class);

    private final DatabaseManager      db       = DatabaseManager.getInstance();
    private final CharacterRepository  charRepo = new CharacterRepository();

    public record InventoryEntry(long id, String itemName, int quantity, boolean equipped) {}
    public record CurrencyRecord(int platinum, int gold, int silver, int bronze) {}

    public CurrencyRecord getCurrency(long userId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return new CurrencyRecord(0, 0, 0, 0);
        String sql = "SELECT currency_platinum, currency_gold, currency_silver, currency_bronze " +
                     "FROM characters WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new CurrencyRecord(
                        rs.getInt("currency_platinum"), rs.getInt("currency_gold"),
                        rs.getInt("currency_silver"),   rs.getInt("currency_bronze"));
            }
        } catch (SQLException e) {
            log.error("getCurrency() failed for userId={}: {}", userId, e.getMessage());
        }
        return new CurrencyRecord(0, 0, 0, 0);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<InventoryEntry> getInventory(long userId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return List.of();

        String sql = "SELECT id, item_name, quantity, equipped FROM character_inventory " +
                     "WHERE character_id = ? ORDER BY id";
        List<InventoryEntry> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new InventoryEntry(
                            rs.getLong("id"),
                            rs.getString("item_name"),
                            rs.getInt("quantity"),
                            rs.getBoolean("equipped")));
                }
            }
        } catch (SQLException e) {
            log.error("getInventory() failed for userId={}: {}", userId, e.getMessage());
        }
        return result;
    }

    /**
     * Adds {@code quantity} of {@code itemName} to the character's inventory.
     * If the item already exists (non-equipped), its quantity is incremented.
     * Returns true on success.
     */
    public boolean addItem(long userId, String itemName, int quantity) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return false;

        String checkSql = "SELECT id, quantity FROM character_inventory " +
                          "WHERE character_id = ? AND item_name = ? LIMIT 1";
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, charId);
                ps.setString(2, itemName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long entryId = rs.getLong("id");
                        int  newQty  = rs.getInt("quantity") + quantity;
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE character_inventory SET quantity = ? WHERE id = ?")) {
                            upd.setInt(1, newQty);
                            upd.setLong(2, entryId);
                            upd.executeUpdate();
                        }
                        return true;
                    }
                }
            }
            // No existing entry — insert new row
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO character_inventory (character_id, item_name, quantity) VALUES (?, ?, ?)")) {
                ins.setLong(1, charId);
                ins.setString(2, itemName);
                ins.setInt(3, quantity);
                ins.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            log.error("addItem() failed for userId={} item='{}': {}", userId, itemName, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes all inventory entries whose item is flagged noLog=true in item-registry.json.
     * Called on explicit logout and session eviction so temporary items don't persist.
     */
    public void removeNoLogItems(long userId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return;

        Set<String> noLogNames = loadNoLogItemNames();
        if (noLogNames.isEmpty()) return;

        String sql = "DELETE FROM character_inventory WHERE character_id = ? AND item_name = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String name : noLogNames) {
                ps.setLong(1, charId);
                ps.setString(2, name);
                ps.addBatch();
            }
            ps.executeBatch();
            log.debug("removeNoLogItems() cleared noLog items for userId={}", userId);
        } catch (SQLException e) {
            log.error("removeNoLogItems() failed for userId={}: {}", userId, e.getMessage());
        }
    }

    private static Set<String> loadNoLogItemNames() {
        Set<String> names = new HashSet<>();
        File f = new File("client/src/main/resources/graphics/sprites/item-registry.json");
        if (!f.exists()) return names;
        try {
            ObjectMapper om = new ObjectMapper();
            for (JsonNode node : om.readTree(f)) {
                if (node.path("noLog").asBoolean(false)) {
                    String name = node.path("name").asText(null);
                    if (name != null) names.add(name);
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(InventoryRepository.class)
                    .warn("Could not load item-registry.json for noLog check: {}", e.getMessage());
        }
        return names;
    }

    /** Toggles the equipped state of an inventory entry. Verifies ownership by userId. */
    public boolean setEquipped(long entryId, long userId, boolean equipped) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return false;

        String sql = "UPDATE character_inventory SET equipped = ? WHERE id = ? AND character_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, equipped);
            ps.setLong(2, entryId);
            ps.setLong(3, charId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("setEquipped() failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Drops (removes or decrements) an inventory entry.
     * If {@code quantity} ≥ current stack, the entry is deleted entirely.
     * Verifies ownership by userId. Returns true on success.
     */
    public boolean dropItem(long entryId, long userId, int quantity) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return false;

        String checkSql = "SELECT quantity FROM character_inventory WHERE id = ? AND character_id = ?";
        try (Connection conn = db.getConnection()) {
            int current;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, entryId);
                ps.setLong(2, charId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    current = rs.getInt("quantity");
                }
            }
            if (quantity >= current) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM character_inventory WHERE id = ? AND character_id = ?")) {
                    del.setLong(1, entryId);
                    del.setLong(2, charId);
                    del.executeUpdate();
                }
            } else {
                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE character_inventory SET quantity = ? WHERE id = ? AND character_id = ?")) {
                    upd.setInt(1, current - quantity);
                    upd.setLong(2, entryId);
                    upd.setLong(3, charId);
                    upd.executeUpdate();
                }
            }
            return true;
        } catch (SQLException e) {
            log.error("dropItem() failed: {}", e.getMessage());
            return false;
        }
    }
}
