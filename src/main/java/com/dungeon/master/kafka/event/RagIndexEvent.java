package com.dungeon.master.kafka.event;

import java.util.UUID;

/**
 * A request to (re)index a session's recent history into the RAG vector store. Emitted from
 * {@code DmResponseConsumer} on the periodic "every N turns" tick so the blocking embedding call +
 * pgvector write happen off the DM-response broadcast thread, in {@code RagIndexConsumer}. The
 * indexing is idempotent (it replaces the session's prior history snapshot), so this event is safe
 * to redeliver.
 *
 * @param turnNumber the turn that triggered the tick — informational, for logging/ordering only.
 */
public record RagIndexEvent(
        UUID sessionId,
        int turnNumber
) {
}
