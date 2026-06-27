package com.dungeon.master.model.enums;

/**
 * The flavour of an LLM-requested ability check, persisted on {@code pending_checks.check_kind}.
 * <ul>
 *   <li>{@link #STANDARD} — a single player's check (also used for collaborative-round batches).</li>
 *   <li>{@link #GROUP} — one check imposed on every player at once; the party succeeds iff at
 *       least half the participants succeed (D&D 5e group-check rule), resolved server-side.</li>
 *   <li>{@link #CONTEST} — one player's check opposed by an NPC; the engine rolls both sides and
 *       compares totals, ties favouring the defender.</li>
 * </ul>
 * Existing rows default to {@link #STANDARD}.
 */
public enum CheckKind {
    STANDARD,
    GROUP,
    CONTEST
}
