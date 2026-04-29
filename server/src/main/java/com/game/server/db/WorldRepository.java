package com.game.server.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.shared.WorldConstants;
import com.game.shared.WorldDef;
import com.game.shared.WorldObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CRUD operations for the {@code worlds} table.
 *
 * Heightmap is stored as a raw MEDIUMBLOB (big-endian IEEE-754 float array).
 * Objects are stored as a JSON string (MEDIUMTEXT).
 * Texture layers are stored as a comma-separated VARCHAR.
 *
 * The heightmap is intentionally NOT included in the WORLD_DEF UDP packet
 * (it is too large for UDP). The server loads it from DB into the active
 * WorldDef and will deliver it to clients via chunked packets in a later phase.
 */
public class WorldRepository {

    private static final Logger log = LoggerFactory.getLogger(WorldRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DatabaseManager db;

    public WorldRepository() {
        this.db = DatabaseManager.getInstance();
    }

    // ── Summary record (no heightmap — for list views) ────────────────────────

    public record WorldSummary(long id, long ownerUserId, String name,
                               int heightmapSize, float xzScale, float yScale,
                               Timestamp createdAt, Timestamp updatedAt) {}

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Saves a new world. Returns the generated DB id. */
    public long save(WorldDef def, long ownerUserId) throws Exception {
        String sql = """
                INSERT INTO worlds
                    (owner_user_id, name, heightmap_size, xz_scale, y_scale,
                     texture_layers, spawn_x, spawn_y, spawn_z, heightmap, objects)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ownerUserId);
            ps.setString(2, def.name);
            ps.setInt(3, def.heightmapSize);
            ps.setFloat(4, def.xzScale);
            ps.setFloat(5, def.yScale);
            ps.setString(6, layersToString(def.textureLayers));
            ps.setFloat(7, def.spawnX);
            ps.setFloat(8, def.spawnY);
            ps.setFloat(9, def.spawnZ);
            ps.setBytes(10, heightmapToBytes(def.heightmap));
            ps.setString(11, MAPPER.writeValueAsString(def.objects));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    log.info("World '{}' saved with id={}", def.name, id);
                    return id;
                }
            }
        }
        throw new SQLException("World insert returned no generated key.");
    }

    /** Updates an existing world owned by ownerUserId. Returns true if a row was updated. */
    public boolean update(long id, WorldDef def, long ownerUserId) throws Exception {
        String sql = """
                UPDATE worlds
                SET name=?, heightmap_size=?, xz_scale=?, y_scale=?,
                    texture_layers=?, spawn_x=?, spawn_y=?, spawn_z=?,
                    heightmap=?, objects=?
                WHERE id=? AND owner_user_id=?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, def.name);
            ps.setInt(2, def.heightmapSize);
            ps.setFloat(3, def.xzScale);
            ps.setFloat(4, def.yScale);
            ps.setString(5, layersToString(def.textureLayers));
            ps.setFloat(6, def.spawnX);
            ps.setFloat(7, def.spawnY);
            ps.setFloat(8, def.spawnZ);
            ps.setBytes(9, heightmapToBytes(def.heightmap));
            ps.setString(10, MAPPER.writeValueAsString(def.objects));
            ps.setLong(11, id);
            ps.setLong(12, ownerUserId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Updates any world regardless of owner (admin use). Returns true if a row was updated. */
    public boolean updateAny(long id, WorldDef def) throws Exception {
        String sql = """
                UPDATE worlds
                SET name=?, heightmap_size=?, xz_scale=?, y_scale=?,
                    texture_layers=?, spawn_x=?, spawn_y=?, spawn_z=?,
                    heightmap=?, objects=?
                WHERE id=?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, def.name);
            ps.setInt(2, def.heightmapSize);
            ps.setFloat(3, def.xzScale);
            ps.setFloat(4, def.yScale);
            ps.setString(5, layersToString(def.textureLayers));
            ps.setFloat(6, def.spawnX);
            ps.setFloat(7, def.spawnY);
            ps.setFloat(8, def.spawnZ);
            ps.setBytes(9, heightmapToBytes(def.heightmap));
            ps.setString(10, MAPPER.writeValueAsString(def.objects));
            ps.setLong(11, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Deletes a world owned by ownerUserId. Returns true if a row was deleted. */
    public boolean delete(long id, long ownerUserId) throws SQLException {
        String sql = "DELETE FROM worlds WHERE id=? AND owner_user_id=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, ownerUserId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Deletes a world regardless of owner (admin use). Returns true if a row was deleted. */
    public boolean deleteAny(long id) throws SQLException {
        String sql = "DELETE FROM worlds WHERE id=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the first world in the table (by id), or null if the table is empty. */
    public WorldDef findFirst() throws Exception {
        String sql = """
                SELECT id, owner_user_id, name, heightmap_size, xz_scale, y_scale,
                       texture_layers, spawn_x, spawn_y, spawn_z, heightmap, objects
                FROM worlds ORDER BY id ASC LIMIT 1
                """;
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            if (rs.next()) return fromRow(rs);
        }
        return null;
    }

    /** Returns a full WorldDef by id, or null if not found. */
    public WorldDef findById(long id) throws Exception {
        String sql = """
                SELECT id, owner_user_id, name, heightmap_size, xz_scale, y_scale,
                       texture_layers, spawn_x, spawn_y, spawn_z, heightmap, objects
                FROM worlds WHERE id=?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return fromRow(rs);
            }
        }
        return null;
    }

    /** Returns all worlds as lightweight summaries (no heightmap bytes loaded). */
    public List<WorldSummary> findAll() throws SQLException {
        String sql = """
                SELECT id, owner_user_id, name, heightmap_size, xz_scale, y_scale,
                       created_at, updated_at
                FROM worlds ORDER BY name
                """;
        List<WorldSummary> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new WorldSummary(
                        rs.getLong("id"),
                        rs.getLong("owner_user_id"),
                        rs.getString("name"),
                        rs.getInt("heightmap_size"),
                        rs.getFloat("xz_scale"),
                        rs.getFloat("y_scale"),
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("updated_at")));
            }
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static WorldDef fromRow(ResultSet rs) throws Exception {
        WorldDef def = new WorldDef();
        def.id            = String.valueOf(rs.getLong("id"));
        def.name          = rs.getString("name");
        def.heightmapSize = rs.getInt("heightmap_size");
        def.xzScale       = rs.getFloat("xz_scale");
        def.yScale        = rs.getFloat("y_scale");
        def.textureLayers = stringToLayers(rs.getString("texture_layers"));
        def.spawnX        = rs.getFloat("spawn_x");
        def.spawnY        = rs.getFloat("spawn_y");
        def.spawnZ        = rs.getFloat("spawn_z");

        byte[] hmBytes = rs.getBytes("heightmap");
        def.heightmap = (hmBytes != null && hmBytes.length > 0)
                ? bytesToHeightmap(hmBytes)
                : new float[def.heightmapSize * def.heightmapSize];

        String objJson = rs.getString("objects");
        def.objects = (objJson != null && !objJson.isBlank())
                ? MAPPER.readValue(objJson, new TypeReference<List<WorldObject>>() {})
                : new ArrayList<>();

        return def;
    }

    /** Converts float[] heightmap to big-endian IEEE-754 byte array. */
    private static byte[] heightmapToBytes(float[] hm) {
        if (hm == null || hm.length == 0) return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(hm.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (float f : hm) buf.putFloat(f);
        return buf.array();
    }

    /** Converts big-endian IEEE-754 byte array back to float[] heightmap. */
    private static float[] bytesToHeightmap(byte[] bytes) {
        float[] hm  = new float[bytes.length / 4];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < hm.length; i++) hm[i] = buf.getFloat();
        return hm;
    }

    private static String layersToString(String[] layers) {
        return (layers == null || layers.length == 0)
                ? "grass,dirt,rock,snow"
                : String.join(",", layers);
    }

    private static String[] stringToLayers(String s) {
        return (s == null || s.isBlank())
                ? new String[]{"grass", "dirt", "rock", "snow"}
                : s.split(",");
    }
}
