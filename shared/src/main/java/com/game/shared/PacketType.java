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
    FORCE_LOGOUT,

    // ── Character ────────────────────────────────────────────────
    CHARACTER_CREATE_REQUEST,
    CHARACTER_CREATE_RESPONSE,

    // ── Game ────────────────────────────────────────────────────
    GAME_JOIN,
    GAME_LEAVE,
    PLAYER_UPDATE,
    GAME_STATE,

    // ── Server broadcasts ────────────────────────────────────────────────────
    SERVER_NOTICE,
    SERVER_SETTINGS,

    // ── Admin ────────────────────────────────────────────────────
    ADMIN_CONNECT_REQUEST,
    ADMIN_CONNECT_RESPONSE,
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
    ADMIN_DEPLOY_RESPONSE,
    ADMIN_SAVE_SETTINGS_REQUEST,
    ADMIN_SAVE_SETTINGS_RESPONSE,
    ADMIN_SET_DEV_REQUEST,
    ADMIN_SET_DEV_RESPONSE,
    ADMIN_GET_BOARDS_REQUEST,
    ADMIN_GET_BOARDS_RESPONSE,

    // ── Inventory ────────────────────────────────────────────────────────
    INVENTORY_REQUEST,
    INVENTORY_RESPONSE,
    INVENTORY_EQUIP_REQUEST,
    INVENTORY_EQUIP_RESPONSE,
    INVENTORY_DROP_REQUEST,
    INVENTORY_DROP_RESPONSE,
    INVENTORY_GIVE_ITEM_REQUEST,
    INVENTORY_GIVE_ITEM_RESPONSE,

    // ── Spells ───────────────────────────────────────────────────────────
    SPELL_CAST_REQUEST,
    SPELL_CAST_RESPONSE,

    // ── Quests ───────────────────────────────────────────────────────────
    QUEST_LIST_REQUEST,
    QUEST_LIST_RESPONSE,
    QUEST_ACCEPT_REQUEST,
    QUEST_ACCEPT_RESPONSE,
    QUEST_ABANDON_REQUEST,
    QUEST_ABANDON_RESPONSE,
    MOB_KILLED,
}
