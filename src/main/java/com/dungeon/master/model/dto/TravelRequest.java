package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.TravelPace;

/**
 * WebSocket payload for a travel action: where the party wants to go and at what pace.
 *
 * @param destinationRegion the target location's name (must be a route-connected region)
 * @param pace              the overland pace; null defaults to NORMAL
 */
public record TravelRequest(String destinationRegion, TravelPace pace) {
}
