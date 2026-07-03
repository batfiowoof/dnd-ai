package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create/update payload for the World Builder wizard. Only the name is strictly required so a draft
 * world can be saved early and filled in over time; the structured lists are sanitized server-side by
 * {@code WorldService} (blank entries dropped, monster keys namespaced and validated for combat).
 */
public record WorldCreateUpdateRequest(
        @NotBlank(message = "World name is required") @Size(max = 120) String name,
        @Size(max = 255) String tagline,
        String overview,
        @Size(max = 100) String tone,
        @Size(max = 100) String magicLevel,
        List<WorldRegion> regions,
        List<WorldFaction> factions,
        List<WorldNpc> npcs,
        List<CustomMonster> customMonsters,
        List<Milestone> milestones,
        List<Quest> quests
) {
}
