package com.dungeon.master.model.dto;

/**
 * A concrete "real impact" applied when a quest completes: nudge a named NPC's disposition toward the
 * party by {@code delta} (signed, on the −100..100 scale). Resolved by name through
 * {@code NpcStateService.adjust}, the same path the DM's {@code adjustDisposition} tool uses.
 */
public record QuestDispositionShift(String npcName, int delta) {
}
