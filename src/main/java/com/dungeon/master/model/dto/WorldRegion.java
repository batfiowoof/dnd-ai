package com.dungeon.master.model.dto;

import java.util.List;

/**
 * One notable location in an authored {@link com.dungeon.master.model.entity.World} — a place that
 * matters to the campaign (home base, rival stronghold, mystery site, wild zone, neutral ground).
 * Persisted as JSON on the world and rendered into the session world-setting text.
 *
 * <p>The travel system overlays a light spatial graph on top of these: {@code x}/{@code y} place the
 * location on the node-graph map (normalized to a 0–100 canvas; null → laid out automatically) and
 * {@code connections} name the other regions this one has a direct travel route to (undirected — the
 * map builder symmetrizes them). All three are nullable so worlds authored before the travel system
 * still deserialize.
 *
 * @param name        the location's name (e.g. "Saltmarsh")
 * @param type        a short kind tag (e.g. "City", "Ruin", "Wilds")
 * @param description a sentence or two of flavour and why adventurers care
 * @param x           normalized map x-coordinate in [0, 100] (null when unplaced)
 * @param y           normalized map y-coordinate in [0, 100] (null when unplaced)
 * @param connections names of regions directly reachable from here by a travel route
 */
public record WorldRegion(String name, String type, String description,
                          Double x, Double y, List<String> connections) {

    /** Back-compat convenience for callers that don't carry map data (tests, legacy construction). */
    public WorldRegion(String name, String type, String description) {
        this(name, type, description, null, null, null);
    }
}
