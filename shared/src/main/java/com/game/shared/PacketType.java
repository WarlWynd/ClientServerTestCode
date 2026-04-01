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
    VERSION_CHECK_REQUEST,   // client -> server: send client version for compatibility check
    VERSION_CHECK_RESPONSE,  // server -> client: compatible true/false + min required version

    // -- Game --
    GAME_JOIN,
    GAME_LEAVE,
    PLAYER_UPDATE,   // client -> server: position/state delta
    GAME_STATE,      // server -> client: full authoritative world snapshot

    // -- Admin --
    ADMIN_USER_LIST_REQUEST,   // admin -> server: poll connected players
    ADMIN_USER_LIST_RESPONSE,  // server -> admin: array of player snapshots
    ADMIN_KICK_REQUEST,        // admin -> server: kick a player by username
    ADMIN_KICK_RESPONSE,       // server -> admin: success/failure of kick
    ADMIN_BAN_REQUEST,         // admin -> server: ban/unban a user by username
    ADMIN_BAN_RESPONSE,        // server -> admin: success/failure of ban
    ADMIN_SET_ADMIN_REQUEST,   // admin -> server: grant/revoke admin for a user
    ADMIN_SET_ADMIN_RESPONSE,  // server -> admin: success/failure of admin change
    ADMIN_RESTART_REQUEST,     // admin -> server: restart the server process
    ADMIN_RESTART_RESPONSE,    // server -> admin: acknowledged, restarting
    ADMIN_DEPLOY_REQUEST,      // admin -> server: git pull, rebuild, restart
    ADMIN_DEPLOY_RESPONSE      // server -> admin: deploy started / error
}
