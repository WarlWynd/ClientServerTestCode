package com.game.shared;

/**
 * All UDP packet types exchanged between client and server.
 * Auth packets carry no session token; all game packets require one.
 */
public enum PacketType {
    // ── Authentication ──────────────────────────────────────────
    LOGIN_REQUEST,
    LOGIN_RESPONSE,
    REGISTER_REQUEST,
    REGISTER_RESPONSE,
    LOGOUT_REQUEST,
    LOGOUT_RESPONSE,

    // ── Session / Connectivity ───────────────────────────────────
    PING,
    PONG,
    ERROR,

    // ── Game ────────────────────────────────────────────────────
    GAME_JOIN,
    GAME_LEAVE,
    PLAYER_UPDATE,   // client → server: position/state delta
    GAME_STATE       // server → client: full authoritative world snapshot
}
