package com.dungeon.master.model.dto;

/**
 * Travel metadata threaded alongside a synthesized "the party travels…" action so the DM can narrate
 * the journey with the right framing. Rides on {@code PlayerActionEvent} (optional — null for ordinary
 * actions) and is consumed only when the DM prompt is assembled. The server has already resolved the
 * destination, duration, and whether an encounter occurs; {@code encounter} tells the DM whether to
 * spring a fight (by emitting the existing {@code [[ENCOUNTER]]} tag) or narrate a safe arrival.
 *
 * <p>When {@code local} is true this was a short hop between subregions <em>within</em> a region
 * ({@code fromSubregion} → {@code toSubregion}), which the DM should narrate as a brief local move
 * rather than a multi-day journey; local moves never carry an encounter.
 *
 * @param fromRegion    where the party set out from (may be null on the very first placement)
 * @param toRegion      the destination region
 * @param fromSubregion the subregion set out from on a local move (null for overland travel)
 * @param toSubregion   the destination subregion on a local move (null for overland travel)
 * @param local         true when this is an intra-region hop between subregions
 * @param durationText  human-readable travel time, e.g. "about 2 days" or "a short while"
 * @param encounter     true when the server rolled a hostile encounter for this leg
 */
public record TravelContext(String fromRegion, String toRegion, String fromSubregion, String toSubregion,
                            boolean local, String durationText, boolean encounter) {
}
