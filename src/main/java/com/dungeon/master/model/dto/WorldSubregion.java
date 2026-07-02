package com.dungeon.master.model.dto;

import java.util.List;

/**
 * A location <em>inside</em> a {@link WorldRegion} — a district, landmark, or site the party can move
 * between locally once they have arrived in the parent region (e.g. "The Docks" within "Saltmarsh").
 * Persisted as JSON nested on its parent region and rendered into the session world-setting text.
 *
 * <p>Subregions mirror the region's light spatial graph, but scoped to their parent: {@code x}/{@code y}
 * place them on the region's own local mini-map (normalized 0–100; null → laid out automatically) and
 * {@code connections} name <em>sibling</em> subregions this one has a direct local route to (undirected —
 * the map builder symmetrizes them). Names only need to be unique within one region. All spatial fields
 * are nullable so worlds authored before subregions still deserialize.
 *
 * @param name        the subregion's name (e.g. "The Docks")
 * @param type        a short kind tag (e.g. "District", "Landmark")
 * @param description a sentence or two of flavour
 * @param x           normalized local x-coordinate in [0, 100] (null when unplaced)
 * @param y           normalized local y-coordinate in [0, 100] (null when unplaced)
 * @param connections names of sibling subregions directly reachable from here by a local route
 */
public record WorldSubregion(String name, String type, String description,
                             Double x, Double y, List<String> connections) {

    /** Back-compat convenience for callers that don't carry map data (tests, legacy construction). */
    public WorldSubregion(String name, String type, String description) {
        this(name, type, description, null, null, null);
    }
}
