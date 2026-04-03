package com.game.server.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SoftwareVersion {

    private long          id;
    private String        serverVersion;
    private String        clientVersion;
    private String        changes;
    private LocalDate     releasedAt;
    private LocalDateTime createdAt;

    public SoftwareVersion() {}

    public SoftwareVersion(String serverVersion, String clientVersion,
                           String changes, LocalDate releasedAt) {
        this.serverVersion = serverVersion;
        this.clientVersion = clientVersion;
        this.changes       = changes;
        this.releasedAt    = releasedAt;
    }

    public long          getId()            { return id; }
    public String        getServerVersion() { return serverVersion; }
    public String        getClientVersion() { return clientVersion; }
    public String        getChanges()       { return changes; }
    public LocalDate     getReleasedAt()    { return releasedAt; }
    public LocalDateTime getCreatedAt()     { return createdAt; }

    public void setId(long id)                        { this.id = id; }
    public void setServerVersion(String serverVersion){ this.serverVersion = serverVersion; }
    public void setClientVersion(String clientVersion){ this.clientVersion = clientVersion; }
    public void setChanges(String changes)            { this.changes = changes; }
    public void setReleasedAt(LocalDate releasedAt)   { this.releasedAt = releasedAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "SoftwareVersion[id=%d, server=%s, client=%s, released=%s]"
                .formatted(id, serverVersion, clientVersion, releasedAt);
    }
}
