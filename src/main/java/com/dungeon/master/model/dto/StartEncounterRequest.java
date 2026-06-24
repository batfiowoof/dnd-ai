package com.dungeon.master.model.dto;

import java.util.List;

/** WebSocket inbound (host only): start an encounter from bestiary keys. */
public record StartEncounterRequest(
        List<String> enemies
) {
}
