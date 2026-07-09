package com.dungeon.master.model.dto;

import java.util.List;

/**
 * WebSocket inbound (host only): start an encounter from bestiary keys.
 *
 * @param enemies bestiary keys to spawn, one entry per creature
 * @param lair    the party fights in the monster's lair, so lair-capable enemies take a lair action
 *                each round on initiative count 20. False for DM-parsed {@code [[ENCOUNTER:…]]} fights.
 */
public record StartEncounterRequest(
        List<String> enemies,
        boolean lair
) {
}
