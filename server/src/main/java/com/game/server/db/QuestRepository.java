package com.game.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access object for character_quests.
 * All methods identify progress by characterId.
 *
 * Table is created automatically on first use.
 */
public class QuestRepository {

    private static final Logger log = LoggerFactory.getLogger(QuestRepository.class);

    private final DatabaseManager     db       = DatabaseManager.getInstance();
    private final CharacterRepository charRepo = new CharacterRepository();

    public record QuestProgress(String questId, String status, int killProgress) {}

    public QuestRepository() {
        ensureTable();
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void ensureTable() {
        String createSql = """
                CREATE TABLE IF NOT EXISTS character_quests (
                    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                    character_id  BIGINT      NOT NULL,
                    quest_id      VARCHAR(64) NOT NULL,
                    status        ENUM('ACTIVE','COMPLETED','ABANDONED') NOT NULL DEFAULT 'ACTIVE',
                    kill_progress INT         NOT NULL DEFAULT 0,
                    accepted_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    completed_at  TIMESTAMP NULL,
                    UNIQUE KEY uq_char_quest (character_id, quest_id),
                    FOREIGN KEY (character_id) REFERENCES characters(id)
                )
                """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createSql);
            // Add kill_progress column for tables that existed before this column was introduced
            try {
                st.execute("ALTER TABLE character_quests ADD COLUMN kill_progress INT NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // ER_DUP_FIELDNAME (1060) — column already exists, safe to ignore
            }
        } catch (SQLException e) {
            log.error("ensureTable() failed: {}", e.getMessage());
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<QuestProgress> getProgress(long userId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return List.of();

        String sql = "SELECT quest_id, status, kill_progress FROM character_quests WHERE character_id = ? ORDER BY id";
        List<QuestProgress> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new QuestProgress(
                            rs.getString("quest_id"),
                            rs.getString("status"),
                            rs.getInt("kill_progress")));
                }
            }
        } catch (SQLException e) {
            log.error("getProgress() failed for userId={}: {}", userId, e.getMessage());
        }
        return result;
    }

    /** Accepts a quest for the user. Returns false if already accepted or no character found. */
    public boolean acceptQuest(long userId, String questId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return false;

        String sql = "INSERT IGNORE INTO character_quests (character_id, quest_id, status) VALUES (?, ?, 'ACTIVE')";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, charId);
            ps.setString(2, questId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("acceptQuest() failed for userId={} questId='{}': {}", userId, questId, e.getMessage());
            return false;
        }
    }

    /** Abandons (removes) an active quest. Returns false if quest wasn't active or no character found. */
    public boolean abandonQuest(long userId, String questId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return false;

        String sql = "DELETE FROM character_quests WHERE character_id = ? AND quest_id = ? AND status = 'ACTIVE'";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, charId);
            ps.setString(2, questId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("abandonQuest() failed for userId={} questId='{}': {}", userId, questId, e.getMessage());
            return false;
        }
    }

    /**
     * Increments kill_progress by 1 on every ACTIVE quest that has KILL_MOB objectives
     * matching the given mob type (or "any"). Quests that reach their kill target are
     * auto-completed and their gold reward is granted.
     *
     * @param userId   the user who scored the kill
     * @param mobType  type string from the kill event (e.g. "wolf"), or "any"
     * @param killQuestDefs  map of questId → definition node (passed in from QuestHandler)
     * @return list of questIds that were just completed by this kill
     */
    public List<String> incrementKillProgress(long userId, String mobType,
                                              java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> killQuestDefs) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return List.of();

        List<QuestProgress> active = getProgress(userId).stream()
                .filter(p -> "ACTIVE".equals(p.status()))
                .toList();

        List<String> completed = new ArrayList<>();

        for (QuestProgress p : active) {
            com.fasterxml.jackson.databind.JsonNode def = killQuestDefs.get(p.questId());
            if (def == null) continue;

            com.fasterxml.jackson.databind.JsonNode objectives = def.get("objectives");
            if (objectives == null || !objectives.isArray()) continue;

            // Check if any objective matches KILL_MOB with this mob type
            boolean matches = false;
            int     target  = 1;
            for (com.fasterxml.jackson.databind.JsonNode obj : objectives) {
                if (!"KILL_MOB".equals(obj.has("type") ? obj.get("type").asText() : "")) continue;
                String t = obj.has("target") ? obj.get("target").asText("any") : "any";
                if ("any".equals(t) || t.equalsIgnoreCase(mobType)) {
                    matches = true;
                    target  = obj.has("count") ? obj.get("count").asInt(1) : 1;
                    break;
                }
            }
            if (!matches) continue;

            int newProgress = p.killProgress() + 1;
            String updateSql = "UPDATE character_quests SET kill_progress = ? WHERE character_id = ? AND quest_id = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, newProgress);
                ps.setLong(2, charId);
                ps.setString(3, p.questId());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("incrementKillProgress() update failed: {}", e.getMessage());
                continue;
            }

            if (newProgress >= target) {
                completeQuest(userId, p.questId());
                // Grant gold reward
                com.fasterxml.jackson.databind.JsonNode rewards = def.get("rewards");
                if (rewards != null && rewards.has("gold")) {
                    grantGold(charId, rewards.get("gold").asInt());
                }
                completed.add(p.questId());
                log.info("Quest '{}' completed by userId={} (kills={}/{})", p.questId(), userId, newProgress, target);
            }
        }
        return completed;
    }

    private void grantGold(long charId, int amount) {
        String sql = "UPDATE characters SET currency_gold = currency_gold + ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, charId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("grantGold() failed for charId={}: {}", charId, e.getMessage());
        }
    }

    /** Marks an active quest as completed. Returns false if not found or already completed. */
    public boolean completeQuest(long userId, String questId) {
        long charId = charRepo.getCharacterId(userId);
        if (charId < 0) return false;

        String sql = "UPDATE character_quests SET status = 'COMPLETED', completed_at = NOW() " +
                     "WHERE character_id = ? AND quest_id = ? AND status = 'ACTIVE'";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, charId);
            ps.setString(2, questId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("completeQuest() failed: {}", e.getMessage());
            return false;
        }
    }
}
