package com.dungeon.master.model.dto;

/**
 * A labelled point of interest on the battle map (e.g. "Altar", "Brazier").
 * Purely descriptive in Phase A — it has no mechanical effect.
 *
 * @param x     column (0-based)
 * @param y     row (0-based)
 * @param label human-readable name shown to players
 */
public record MapFeature(int x, int y, String label) {
}
