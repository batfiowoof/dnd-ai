package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * COMBAT_START / COMBAT_TURN / COMBAT_END. Each carries the full combat snapshot
 * so clients can render the tracker without extra fetches. {@code victory} is only
 * meaningful for COMBAT_END.
 */
public record CombatLifecycleEvent(
        String type,
        UUID sessionId,
        Boolean victory,
        CombatStateDto combat
) {
    public static final String START = "COMBAT_START";
    public static final String TURN = "COMBAT_TURN";
    public static final String END = "COMBAT_END";

    public static CombatLifecycleEvent start(UUID sessionId, CombatStateDto combat) {
        return new CombatLifecycleEvent(START, sessionId, null, combat);
    }

    public static CombatLifecycleEvent turn(UUID sessionId, CombatStateDto combat) {
        return new CombatLifecycleEvent(TURN, sessionId, null, combat);
    }

    public static CombatLifecycleEvent end(UUID sessionId, boolean victory, CombatStateDto combat) {
        return new CombatLifecycleEvent(END, sessionId, victory, combat);
    }
}
