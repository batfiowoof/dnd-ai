package com.dungeon.master.model.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full read model for a single {@link com.dungeon.master.model.entity.World}, returned by the World
 * Builder when loading a world for editing. Entities never cross the web boundary — built by
 * {@code WorldMapper}.
 */
public record WorldDto(
        UUID id,
        String name,
        String tagline,
        String overview,
        String tone,
        String magicLevel,
        List<WorldRegion> regions,
        List<WorldFaction> factions,
        List<WorldNpc> npcs,
        List<CustomMonster> customMonsters,
        List<Milestone> milestones,
        List<Quest> quests,
        List<Shop> shops,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
