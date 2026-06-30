package com.dungeon.master.model.dto;

/**
 * Context the World Builder sends with a per-section "Generate with AI" request. Carries the current
 * draft so generated content stays coherent with what the author already wrote, plus an optional
 * free-text {@code instruction} (a premise for the overview, a theme for a monster, "more political
 * factions", etc.). All fields are optional — an empty request still yields a usable suggestion.
 */
public record WorldGenerateRequest(
        String name,
        String overview,
        String tone,
        String magicLevel,
        String instruction
) {
}
