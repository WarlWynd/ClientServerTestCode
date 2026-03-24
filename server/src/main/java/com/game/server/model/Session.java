package com.game.server.model;

import java.time.LocalDateTime;

/** Represents a valid server-side session. Immutable value type. */
public record Session(String token, long userId, String username, LocalDateTime expiresAt) {}
