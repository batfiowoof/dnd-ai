package com.dungeon.master.model.enums;

/**
 * Distinguishes how a {@link com.dungeon.master.model.entity.TurnEvent} was produced so the
 * client can replay history faithfully. {@code NARRATIVE} rows are a player's spoken action +
 * the DM's reply; {@code COMBAT} rows are an auto-resolved combat beat whose {@code action}
 * is a mechanical summary (not something a player "said") and whose {@code dmResponse} is the
 * DM's narration of that beat.
 */
public enum TurnSource {
    NARRATIVE,
    COMBAT
}
