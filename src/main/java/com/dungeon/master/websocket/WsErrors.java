package com.dungeon.master.websocket;

import com.dungeon.master.exception.CharacterNotFoundException;
import com.dungeon.master.exception.NotYourTurnException;
import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.exception.SessionFullException;
import com.dungeon.master.exception.SessionNotFoundException;
import com.dungeon.master.model.dto.WsError;

/**
 * Translates exceptions thrown while handling STOMP messages into a safe, user-facing {@link WsError}.
 *
 * <p>STOMP handlers bypass {@code GlobalExceptionHandler} (it only covers {@code @RestController}s),
 * so this is the single place that decides what a WebSocket client is allowed to see. Domain
 * exceptions carry intentional, friendly messages and are passed through; anything unexpected
 * collapses to a generic message so internal details (NPEs, DB constraint text, stack traces) never
 * reach the player. The raw cause is logged by the caller, never sent.</p>
 */
public final class WsErrors {

    private WsErrors() {
    }

    /** Code for a rejected-but-expected action (bad turn, not host, full session, …). */
    public static final String CODE_REJECTED = "ACTION_REJECTED";
    /** Code for an unexpected server-side failure — message is deliberately generic. */
    public static final String CODE_INTERNAL = "INTERNAL_ERROR";

    private static final String GENERIC_MESSAGE = "Something went wrong processing that action.";
    private static final String EMPTY_MESSAGE_FALLBACK = "That action could not be completed.";

    public static WsError from(Throwable e) {
        if (isSafeToExpose(e)) {
            String message = e.getMessage();
            return new WsError(CODE_REJECTED,
                    (message == null || message.isBlank()) ? EMPTY_MESSAGE_FALLBACK : message);
        }
        return new WsError(CODE_INTERNAL, GENERIC_MESSAGE);
    }

    /**
     * Domain/validation exceptions whose messages are written for end users. Everything else is
     * treated as an internal error and hidden behind {@link #GENERIC_MESSAGE}.
     */
    private static boolean isSafeToExpose(Throwable e) {
        return e instanceof IllegalStateException
                || e instanceof IllegalArgumentException
                || e instanceof SessionNotFoundException
                || e instanceof PlayerNotFoundException
                || e instanceof SessionFullException
                || e instanceof NotYourTurnException
                || e instanceof CharacterNotFoundException;
    }
}
