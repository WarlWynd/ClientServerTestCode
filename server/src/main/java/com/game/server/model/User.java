package com.game.server.model;

/** Represents an authenticated user. Immutable value type. */
public record User(long id, String username, boolean isAdmin, boolean isAudioDev) {}
