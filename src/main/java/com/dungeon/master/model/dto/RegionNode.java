package com.dungeon.master.model.dto;

import java.util.List;

/**
 * One location as it appears on the travel map: the authored region flavour plus its resolved
 * position on the node-graph canvas. Unlike {@link WorldRegion}, {@code x}/{@code y} here are always
 * present (the {@code TravelMapService} fills in an auto-layout for regions the author never placed)
 * and {@code connections} is symmetrized.
 *
 * @param name        the location's name
 * @param type        short kind tag (e.g. "City", "Ruin")
 * @param description flavour text
 * @param x           resolved map x in [0, 100]
 * @param y           resolved map y in [0, 100]
 * @param connections names of regions directly reachable from here (symmetrized, de-duplicated)
 * @param subregions  resolved local mini-map for this region (nested nodes carry an empty list)
 */
public record RegionNode(String name, String type, String description,
                         double x, double y, List<String> connections,
                         List<RegionNode> subregions) {

    /** A leaf node (a subregion, or a region with no local mini-map): no nested subregions. */
    public RegionNode(String name, String type, String description,
                      double x, double y, List<String> connections) {
        this(name, type, description, x, y, connections, List.of());
    }
}
