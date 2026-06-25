package com.dungeon.master.kafka.event;

import java.util.UUID;

/**
 * One combat "beat" awaiting DM narration. Fired from {@code CombatService} (fast,
 * inside its transaction) once the mechanical resolution of a beat is complete; the
 * blocking, multi-second LLM narration then happens off-thread in
 * {@code CombatNarrationConsumer}. {@code turnEventId} points at the already-persisted
 * {@code TurnEvent} whose {@code dmResponse} the consumer fills once narration finishes.
 *
 * @param beatSummary mechanical truth of what happened (rolls, hits, damage, current HP).
 *                    The narration must describe — never alter — this outcome.
 */
public record CombatNarrationEvent(
        UUID sessionId,
        UUID turnEventId,
        int turnNumber,
        String beatSummary
) {
}
