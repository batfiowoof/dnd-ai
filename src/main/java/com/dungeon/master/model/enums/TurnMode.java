package com.dungeon.master.model.enums;

/**
 * How a session handles narrative (non-combat) turns. Chosen by the host at session
 * creation and stored on {@code GameSession.turnMode}.
 *
 * <ul>
 *   <li>{@link #COLLABORATIVE} (default) — actions collect in a short debounced window; the
 *       DM resolves the whole round in one combined reply (exactly one LLM call per round).</li>
 *   <li>{@link #INITIATIVE} — initiative-ordered rotation gates play, one player at a time.</li>
 *   <li>{@link #FREEFORM} — soft-lock first-come; input locks only while the DM is streaming.</li>
 * </ul>
 */
public enum TurnMode {
    COLLABORATIVE,
    INITIATIVE,
    FREEFORM
}
