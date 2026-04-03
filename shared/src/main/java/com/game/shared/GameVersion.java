package com.game.shared;

/**
 * Single source of truth for the client/server protocol version.
 *
 * Bump this any time a breaking change is made to the packet protocol
 * or game logic. Clients with a mismatched version will be rejected
 * before they reach the login screen.
 */
public final class GameVersion {

    /** Current protocol version. Must match on both client and server. */
    public static final String VERSION = "1.0.0";

    private GameVersion() {}
}
