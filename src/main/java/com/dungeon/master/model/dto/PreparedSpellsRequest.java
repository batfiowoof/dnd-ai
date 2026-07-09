package com.dungeon.master.model.dto;

import java.util.List;

/** WebSocket inbound: set the caster's prepared leveled spells (a subset of their known spells). */
public record PreparedSpellsRequest(
        List<String> spells
) {
}
