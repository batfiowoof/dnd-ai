package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.GameStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight summary of a session the current user belongs to, for the "your
 * adventures" list. {@code myPlayerId} and {@code joinCode} let the client restore
 * its per-session localStorage keys when rejoining from a fresh device.
 */
public record SessionSummaryDto(
        UUID sessionId,
        String joinCode,
        GameStatus status,
        String title,
        String createdBy,
        LocalDateTime createdAt,
        int playerCount,
        boolean isCreator,
        UUID myPlayerId
) {
}
