package com.dungeon.master.model.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight view of a {@link com.dungeon.master.model.entity.World} for the library list and the
 * "My Worlds" source in the session world-setting picker — name, hook, and a few counts, without the
 * full structured payload.
 */
public record WorldSummaryDto(
        UUID id,
        String name,
        String tagline,
        String tone,
        int regionCount,
        int factionCount,
        int npcCount,
        int monsterCount,
        int milestoneCount,
        LocalDateTime updatedAt
) {
}
