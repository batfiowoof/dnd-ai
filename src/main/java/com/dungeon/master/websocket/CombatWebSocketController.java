package com.dungeon.master.websocket;

import com.dungeon.master.model.dto.CombatActionRequest;
import com.dungeon.master.model.dto.MoveRequest;
import com.dungeon.master.model.dto.StabilizeRequest;
import com.dungeon.master.model.dto.StartEncounterRequest;
import com.dungeon.master.service.game.CombatService;
import com.dungeon.master.service.game.GameSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP handlers for the turn-based combat engine. Carries the {@code /game/{sessionId}/combat/*}
 * group; shares {@link #sendError} and the backstop exception handler with the narrative controller
 * via {@link AbstractGameWebSocketController}.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class CombatWebSocketController extends AbstractGameWebSocketController {

    private final CombatService combatService;
    private final GameSessionService gameSessionService;

    @MessageMapping("/game/{sessionId}/combat/start")
    public void handleCombatStart(@DestinationVariable UUID sessionId,
                                  @Payload StartEncounterRequest request,
                                  Principal principal) {
        String username = principal.getName();
        try {
            gameSessionService.requireHost(sessionId, username, "start an encounter");
            combatService.startEncounter(sessionId, request.enemies());
        } catch (Exception e) {
            log.error("Error starting combat: session={}, host={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/attack")
    public void handleCombatAttack(@DestinationVariable UUID sessionId,
                                   @Payload CombatActionRequest request,
                                   Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerAttack(sessionId, username, request.targetEnemyId());
        } catch (Exception e) {
            log.error("Error in combat attack: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/cast")
    public void handleCombatCast(@DestinationVariable UUID sessionId,
                                 @Payload CombatActionRequest request,
                                 Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerCastSpell(sessionId, username, request.spellName(),
                    request.spellLevel() == null ? 0 : request.spellLevel(),
                    request.targetIds(), request.originX(), request.originY());
        } catch (Exception e) {
            log.error("Error in combat cast: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/use-item")
    public void handleCombatUseItem(@DestinationVariable UUID sessionId,
                                    @Payload CombatActionRequest request,
                                    Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerUseItem(sessionId, username, request.itemName());
        } catch (Exception e) {
            log.error("Error in combat use-item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/end-turn")
    public void handleCombatEndTurn(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerEndTurn(sessionId, username);
        } catch (Exception e) {
            log.error("Error ending combat turn: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/move")
    public void handleCombatMove(@DestinationVariable UUID sessionId,
                                 @Payload MoveRequest request,
                                 Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerMove(sessionId, username, request.x(), request.y());
        } catch (Exception e) {
            log.error("Error in combat move: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/dash")
    public void handleCombatDash(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerDash(sessionId, username);
        } catch (Exception e) {
            log.error("Error in combat dash: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/disengage")
    public void handleCombatDisengage(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerDisengage(sessionId, username);
        } catch (Exception e) {
            log.error("Error in combat disengage: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/dodge")
    public void handleCombatDodge(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerDodge(sessionId, username);
        } catch (Exception e) {
            log.error("Error in combat dodge: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/stabilize")
    public void handleCombatStabilize(@DestinationVariable UUID sessionId,
                                      @Payload StabilizeRequest request,
                                      Principal principal) {
        String username = principal.getName();
        try {
            combatService.playerStabilize(sessionId, username, request.targetPlayerId());
        } catch (Exception e) {
            log.error("Error in combat stabilize: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/combat/end")
    public void handleCombatEnd(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            gameSessionService.requireHost(sessionId, username, "end the encounter");
            combatService.endEncounterByHost(sessionId);
        } catch (Exception e) {
            log.error("Error ending combat: session={}, host={}", sessionId, username, e);
            sendError(username, e);
        }
    }
}
