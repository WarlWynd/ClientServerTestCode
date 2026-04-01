package com.game.server.model;

/**
 * Mutable in-memory snapshot of a player's position and score.
 * Held in the GameHandler's ConcurrentHashMap — all writes must be
 * done on the packet-handler thread-pool; reads happen on the game loop.
 */
public class PlayerState {

    public final long   userId;
    public final String username;
    public final String ip;

    public volatile float x        = 400f;
    public volatile float y        = 300f;
    public volatile int   score    = 0;
    public volatile long  lastSeen = System.currentTimeMillis();
    public final    long  joinedAt = System.currentTimeMillis();

    public PlayerState(long userId, String username, String ip) {
        this.userId   = userId;
        this.username = username;
        this.ip       = ip;
    }

    public void update(float x, float y, int score) {
        this.x        = x;
        this.y        = y;
        this.score    = score;
        this.lastSeen = System.currentTimeMillis();
    }
}
