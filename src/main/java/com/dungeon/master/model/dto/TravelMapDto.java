package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.TravelPace;

import java.util.List;

/**
 * The travel-map read model for a session: the campaign's locations (with resolved positions and
 * routes), where the party currently is, how much in-game time has elapsed, and the chosen pace.
 * {@code regions} is empty when the session has no authored world — the frontend hides the map then.
 *
 * @param regions          all locations with resolved coordinates and symmetrized routes
 * @param currentRegion    the party's current location name, or null if not yet placed
 * @param currentSubregion the subregion within the current region the party is at, or null
 * @param inGameMinutes    elapsed in-game time in minutes (Day N • HH:MM)
 * @param pace             the last overland pace chosen
 */
public record TravelMapDto(List<RegionNode> regions, String currentRegion, String currentSubregion,
                           long inGameMinutes, TravelPace pace) {
}
