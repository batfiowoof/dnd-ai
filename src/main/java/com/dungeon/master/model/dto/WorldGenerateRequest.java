package com.dungeon.master.model.dto;

import java.util.List;

/**
 * Context the World Builder sends with a per-section "Generate with AI" request. Carries the current
 * draft so generated content stays coherent with what the author already wrote, plus an optional
 * free-text {@code instruction} (a premise for the overview, a theme for a monster, "more political
 * factions", etc.). All fields are optional — an empty request still yields a usable suggestion.
 *
 * <p>{@code regions} carries the already-authored geography (with any subregions) so sections that must
 * agree with the map — generating NPCs that live in a real place, or subregions for a named region —
 * can be grounded on it.
 */
public record WorldGenerateRequest(
        String name,
        String overview,
        String tone,
        String magicLevel,
        String instruction,
        List<WorldRegion> regions
) {
}
