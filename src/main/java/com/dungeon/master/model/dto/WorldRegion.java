package com.dungeon.master.model.dto;

/**
 * One notable location in an authored {@link com.dungeon.master.model.entity.World} — a place that
 * matters to the campaign (home base, rival stronghold, mystery site, wild zone, neutral ground).
 * Persisted as JSON on the world and rendered into the session world-setting text.
 *
 * @param name        the location's name (e.g. "Saltmarsh")
 * @param type        a short kind tag (e.g. "City", "Ruin", "Wilds")
 * @param description a sentence or two of flavour and why adventurers care
 */
public record WorldRegion(String name, String type, String description) {
}
