package com.dungeon.master.model.dto;

/**
 * Travel metadata threaded alongside a synthesized "the party travels…" action so the DM can narrate
 * the journey with the right framing. Rides on {@code PlayerActionEvent} (optional — null for ordinary
 * actions) and is consumed only when the DM prompt is assembled. The server has already resolved the
 * destination, duration, and whether an encounter occurs; {@code encounter} tells the DM whether to
 * spring a fight (by emitting the existing {@code [[ENCOUNTER]]} tag) or narrate a safe arrival.
 *
 * @param fromRegion   where the party set out from (may be null on the very first placement)
 * @param toRegion     the destination location
 * @param durationText human-readable travel time, e.g. "about 2 days"
 * @param encounter    true when the server rolled a hostile encounter for this leg
 */
public record TravelContext(String fromRegion, String toRegion, String durationText, boolean encounter) {
}
