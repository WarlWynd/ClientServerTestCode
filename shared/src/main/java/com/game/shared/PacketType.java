package com.game.shared;

/**
 * All UDP packet types exchanged between client and server.
 * Auth packets carry no session token; all game packets require one.
 */
public enum PacketType {
    // ── Version Handshake ────────────────────────────────────────
    VERSION_CHECK,
    VERSION_RESPONSE,

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

    // ── Character ────────────────────────────────────────────────
    CHARACTER_CREATE_REQUEST,
    CHARACTER_CREATE_RESPONSE,

    // ── Game ────────────────────────────────────────────────────
    GAME_JOIN,
    GAME_LEAVE,
    PLAYER_UPDATE,
    GAME_STATE,

    // ── Admin ────────────────────────────────────────────────────
    ADMIN_USER_LIST_REQUEST,
    ADMIN_USER_LIST_RESPONSE,
    ADMIN_KICK_REQUEST,
    ADMIN_KICK_RESPONSE,
    ADMIN_BAN_REQUEST,
    ADMIN_BAN_RESPONSE,
    ADMIN_SET_ADMIN_REQUEST,
    ADMIN_SET_ADMIN_RESPONSE,
    ADMIN_RESTART_REQUEST,
    ADMIN_RESTART_RESPONSE,
    ADMIN_DEPLOY_REQUEST,
    ADMIN_DEPLOY_RESPONSE
}
