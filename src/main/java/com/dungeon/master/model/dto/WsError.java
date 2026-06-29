package com.dungeon.master.model.dto;

/**
 * Error payload pushed to a user's {@code /queue/errors} STOMP destination.
 *
 * <p>This is the WebSocket counterpart of {@link ErrorResponse} (the REST error shape). Both carry a
 * human-readable {@code message}; {@code code} lets the client branch on a stable category without
 * parsing prose. Messages are produced via {@code WsErrors.from(..)} so raw exception text is never
 * leaked to clients.</p>
 */
public record WsError(
        String code,
        String message
) {
}
