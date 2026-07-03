package com.dungeon.master.model.enums;

/**
 * Engine-owned lifecycle of a quest on a running session. A quest starts {@code LOCKED} when it has
 * unmet prerequisites (else {@code AVAILABLE}); the DM moves it {@code AVAILABLE → ACTIVE} when the
 * party takes it up, and finally {@code COMPLETED} (pays rewards, unlocks dependents) or {@code FAILED}
 * (cascades failure to dependents). The client can never set a progressed status — the sanitizer forces
 * the authored template back to its initial value, mirroring {@code Milestone.completed}.
 */
public enum QuestStatus {
    LOCKED,
    AVAILABLE,
    ACTIVE,
    COMPLETED,
    FAILED
}
