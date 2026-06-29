package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.TerrainType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * A spell-created terrain effect on the grid (e.g. Entangle's difficult-terrain square) with
 * the lifecycle metadata needed to remove it again. The actual {@link TerrainCell}s are stamped
 * into {@link GridState#getTerrain()} so movement + rendering see them; this record remembers
 * exactly which cells THIS spell added so they can be cleared on concentration break / expiry
 * without disturbing the base map terrain or an overlapping zone.
 *
 * @param type           the terrain stamped (currently always DIFFICULT)
 * @param cells          the cells this zone added to the grid terrain
 * @param sourceCasterId the player who created it
 * @param sourceSpell    the spell name (narration / debugging)
 * @param concentration  true → ends when the caster's concentration ends
 * @param expiresAtRound combat round after which it lapses; {@code null} = until concentration drops
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerrainZone(
        TerrainType type,
        List<TerrainCell> cells,
        UUID sourceCasterId,
        String sourceSpell,
        boolean concentration,
        Integer expiresAtRound
) {
}
