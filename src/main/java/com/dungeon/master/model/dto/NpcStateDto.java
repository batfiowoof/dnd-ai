package com.dungeon.master.model.dto;

/**
 * An NPC's current attitude toward the party, for the client relationship panel and the DM directive.
 *
 * @param name        the NPC's name (as authored)
 * @param disposition the current signed attitude score in [-100, 100]
 * @param band        the human-readable band label (e.g. "Friendly") derived from the score
 */
public record NpcStateDto(String name, int disposition, String band) {
}
