package com.dungeon.master.websocket;

import com.dungeon.master.model.dto.GameStateDto;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerActionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.service.game.GameSessionService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class GameWebSocketController {

    private final TurnService turnService;
    private final GameSessionService gameSessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/game/{sessionId}/action")
    public void handlePlayerAction(@DestinationVariable UUID sessionId,
                                    @Payload PlayerActionRequest request,
                                    Principal principal) {
        String username = principal.getName();
        log.info("WebSocket action received: session={}, player={}, action={}",
                sessionId, username, request.action());

        try {
            turnService.submitAction(sessionId, username, request.action());
        } catch (Exception e) {
            log.error("Error processing player action: session={}, player={}",
                    sessionId, username, e);
            messagingTemplate.convertAndSendToUser(
                    username, "/queue/errors",
                    (Object) Map.of("error", e.getMessage()));
        }
    }

    @MessageMapping("/game/{sessionId}/join")
    @SendToUser("/queue/errors")
    public void handlePlayerJoin(@DestinationVariable UUID sessionId,
                                  @Payload JoinSessionRequest request,
                                  Principal principal) {
        String username = principal.getName();
        log.info("WebSocket join received: session={}, player={}", sessionId, username);

        try {
            PlayerDto player = gameSessionService.joinSession(sessionId, request, username);
            GameStateDto state = gameSessionService.getGameState(sessionId);

            messagingTemplate.convertAndSendToUser(
                    username, "/queue/joined",
                    (Object) Map.of("player", player, "gameState", state));

        } catch (Exception e) {
            log.error("Error joining session via WebSocket: session={}, player={}",
                    sessionId, username, e);
            messagingTemplate.convertAndSendToUser(
                    username, "/queue/errors",
                    (Object) Map.of("error", e.getMessage()));
        }
    }
}
