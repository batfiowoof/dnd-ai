package com.dungeon.master.kafka.event;

import java.util.List;
import java.util.UUID;

/**
 * A collaborative round's collected actions, emitted by {@code RoundCollector} once its
 * debounced window flushes. The {@code PlayerActionConsumer} builds a single combined DM
 * prompt from {@code actions} so the whole party is resolved in one streamed reply — exactly
 * one LLM call per round, never raced by concurrent submissions.
 *
 * @param turnNumber  the turn number allocated for this combined round.
 * @param turnEventId the persisted combined {@code TurnEvent} for this round — the DM narration
 *                    is attached to it by id (never "find latest").
 */
public record RoundActionEvent(
        UUID sessionId,
        int turnNumber,
        UUID turnEventId,
        List<Contribution> actions
) {
    /** One player's contribution to the round. A passed player has a {@code null} action. */
    public record Contribution(UUID playerId, String characterName, String action) {
        public boolean passed() {
            return action == null || action.isBlank();
        }
    }
}
