package com.game.shared;

/**
 * All UDP packet types exchanged between client and server.
 * Auth packets carry no session token; all game packets require one.
 */
public enum PacketType {
    // -- Authentication --
    LOGIN_REQUEST,
    LOGIN_RESPONSE,
    REGISTER_REQUEST,
    REGISTER_RESPONSE,
    LOGOUT_REQUEST,
    LOGOUT_RESPONSE,

    // -- Session / Connectivity --
    PING,
    PONG,
    ERROR,

    // -- Game --
    GAME_JOIN,
    GAME_LEAVE,
    PLAYER_UPDATE,   // client -> server: position/state delta
    GAME_STATE,      // server -> client: full authoritative world snapshot

    // -- Admin --
    ADMIN_USER_LIST_REQUEST,   // admin -> server: poll connected players
    ADMIN_USER_LIST_RESPONSE,  // server -> admin: array of player snapshots
    ADMIN_KICK_REQUEST,        // admin -> server: kick a player by username
    ADMIN_KICK_RESPONSE        // server -> admin: success/failure of kick
}
