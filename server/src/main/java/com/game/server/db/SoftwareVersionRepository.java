package com.game.server.db;

import com.game.server.model.SoftwareVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class SoftwareVersionRepository {

    private static final Logger log = LoggerFactory.getLogger(SoftwareVersionRepository.class);

    private final DatabaseManager db;

    public SoftwareVersionRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Insert a new version entry. Returns the generated id, or -1 on failure. */
    public long insert(SoftwareVersion v) {
        String sql = """
                INSERT INTO software_versions (server_version, client_version, changes, released_at)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, v.getServerVersion());
            ps.setString(2, v.getClientVersion());
            ps.setString(3, v.getChanges());
            ps.setDate(4, Date.valueOf(v.getReleasedAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    v.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to insert software version: {}", e.getMessage(), e);
        }
        return -1;
    }

    /** All entries, newest release date first. */
    public List<SoftwareVersion> findAll() {
        String sql = "SELECT * FROM software_versions ORDER BY released_at DESC, id DESC";
        List<SoftwareVersion> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            log.error("Failed to fetch software versions: {}", e.getMessage(), e);
        }
        return list;
    }

    /** The most recently released entry, or null if table is empty. */
    public SoftwareVersion findLatest() {
        String sql = "SELECT * FROM software_versions ORDER BY released_at DESC, id DESC LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return map(rs);
        } catch (SQLException e) {
            log.error("Failed to fetch latest software version: {}", e.getMessage(), e);
        }
        return null;
    }

    /** Find by id, or null if not found. */
    public SoftwareVersion findById(long id) {
        String sql = "SELECT * FROM software_versions WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to fetch software version {}: {}", id, e.getMessage(), e);
        }
        return null;
    }

    /** Update an existing entry. Returns true on success. */
    public boolean update(SoftwareVersion v) {
        String sql = """
                UPDATE software_versions
                SET server_version=?, client_version=?, changes=?, released_at=?
                WHERE id=?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, v.getServerVersion());
            ps.setString(2, v.getClientVersion());
            ps.setString(3, v.getChanges());
            ps.setDate(4, Date.valueOf(v.getReleasedAt()));
            ps.setLong(5, v.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update software version {}: {}", v.getId(), e.getMessage(), e);
        }
        return false;
    }

    /** Delete by id. Returns true on success. */
    public boolean delete(long id) {
        String sql = "DELETE FROM software_versions WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete software version {}: {}", id, e.getMessage(), e);
        }
        return false;
    }

    private SoftwareVersion map(ResultSet rs) throws SQLException {
        SoftwareVersion v = new SoftwareVersion();
        v.setId(rs.getLong("id"));
        v.setServerVersion(rs.getString("server_version"));
        v.setClientVersion(rs.getString("client_version"));
        v.setChanges(rs.getString("changes"));
        Date d = rs.getDate("released_at");
        if (d != null) v.setReleasedAt(d.toLocalDate());
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) v.setCreatedAt(ts.toLocalDateTime());
        return v;
    }
}
