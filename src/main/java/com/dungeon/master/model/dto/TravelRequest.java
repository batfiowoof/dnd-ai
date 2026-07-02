package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.TravelPace;

/**
 * WebSocket payload for a travel action: where the party wants to go and at what pace.
 *
 * <p>When {@code destinationSubregion} is set, this is a <em>local</em> move within the party's current
 * region (a short hop between subregions, no wilderness encounter). Otherwise it's an overland
 * region-to-region journey.
 *
 * @param destinationRegion    the target region's name (must be a route-connected region)
 * @param destinationSubregion the target subregion within the current region, or null for overland travel
 * @param pace                 the overland pace; null defaults to NORMAL
 */
public record TravelRequest(String destinationRegion, String destinationSubregion, TravelPace pace) {
}
