package com.dungeon.master.model.dto;

/**
 * A key non-player character in an authored {@link com.dungeon.master.model.entity.World}. Each NPC
 * should be tied to a place and ideally bound to the party, so the DM can weave them in. Persisted as
 * JSON on the world and rendered into the session world-setting text. Distinct from combat enemies —
 * these are narrative figures, not stat blocks (see {@link CustomMonster} for combatants).
 *
 * @param name        the NPC's name
 * @param race        their species/ancestry
 * @param role        what they do (e.g. "Harbourmaster", "Cult leader")
 * @param region      the region they belong to (should match a {@link WorldRegion} name; may be null)
 * @param subregion   the subregion within that region they're found in (may be null)
 * @param location    an optional finer "specific spot" free-text note (kept for back-compat)
 * @param bond        their hook/relationship to the party or central conflict
 * @param description optional extra flavour
 */
public record WorldNpc(String name, String race, String role, String region, String subregion,
                       String location, String bond, String description) {
}
