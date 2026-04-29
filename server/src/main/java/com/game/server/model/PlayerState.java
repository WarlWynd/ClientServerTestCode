package com.game.server.model;

/**
 * Mutable in-memory snapshot of a player's 3D position and score.
 * Held in GameHandler's ConcurrentHashMap — all writes must be
 * on the packet-handler thread pool; reads happen on the game loop.
 */
public class PlayerState {

    public final long   userId;
    public final String username;
    public final String characterName;
    public final long   joinedAt = System.currentTimeMillis();
    public final String ip;

    public volatile float x        = 0f;
    public volatile float y        = 0f;
    public volatile float z        = 0f;
    public volatile int   score    = 0;
    public volatile long  lastSeen = System.currentTimeMillis();

    // Character stats
    public volatile int hp       = 100;
    public volatile int mana     = 50;
    public volatile int statInt  = 1;
    public volatile int statStr  = 1;
    public volatile int statWis  = 1;
    public volatile int statCha  = 1;
    public volatile int statSta  = 1;
    public volatile int statAgi  = 1;
    public volatile int statDex  = 1;
    public volatile int statLuk  = 1;

    public PlayerState(long userId, String username, String characterName, String ip) {
        this.userId        = userId;
        this.username      = username;
        this.characterName = characterName != null ? characterName : username;
        this.ip            = ip;
    }

    public void update(float x, float y, float z, int score) {
        this.x        = x;
        this.y        = y;
        this.z        = z;
        this.score    = score;
        this.lastSeen = System.currentTimeMillis();
    }
}
