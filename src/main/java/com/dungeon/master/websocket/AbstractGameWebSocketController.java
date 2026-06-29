package com.dungeon.master.websocket;

import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.WsError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;

import java.util.UUID;

/**
 * Shared plumbing for the STOMP game controllers: a per-user error channel, a session-wide
 * state broadcast, and a backstop exception handler. Lives in a base class so the narrative and
 * combat controllers don't duplicate it.
 */
@Slf4j
abstract class AbstractGameWebSocketController {

    @Autowired
    protected SimpMessagingTemplate messagingTemplate;

    protected void broadcastState(UUID sessionId, PlayerRuntimeStateDto state) {
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                PlayerStateEvent.of(sessionId, state));
    }

    protected void sendError(String username, Throwable e) {
        messagingTemplate.convertAndSendToUser(
                username, "/queue/errors", (Object) WsErrors.from(e));
    }

    /**
     * Backstop for any handler that doesn't catch its own exception. {@code @RestControllerAdvice}
     * does not apply to STOMP {@code @MessageMapping}s, so without this an uncaught exception would
     * be swallowed by the messaging layer and the user would see nothing. Always emits a
     * {@link WsErrors}-sanitized payload — raw exception text never reaches the client.
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public WsError handleUncaughtError(Exception e) {
        log.error("Unhandled WebSocket error", e);
        return WsErrors.from(e);
    }
}
