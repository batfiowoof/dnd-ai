package com.dungeon.master.websocket;

import com.dungeon.master.model.dto.AddItemRequest;
import com.dungeon.master.model.dto.CastRequest;
import com.dungeon.master.model.dto.DiceRollEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.DropItemRequest;
import com.dungeon.master.model.dto.EquipItemRequest;
import com.dungeon.master.model.dto.GameStateDto;
import com.dungeon.master.model.dto.HpChangeRequest;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerActionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.RollRequest;
import com.dungeon.master.model.dto.UseItemRequest;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.GameSessionService;
import com.dungeon.master.service.game.PlayerService;
import com.dungeon.master.service.game.SessionMembershipService;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class GameWebSocketController extends AbstractGameWebSocketController {

    private final TurnService turnService;
    private final GameSessionService gameSessionService;
    private final SessionMembershipService sessionMembershipService;
    private final PlayerService playerService;
    private final PlayerStateService playerStateService;
    private final DiceService diceService;

    @MessageMapping("/game/{sessionId}/action")
    public void handlePlayerAction(@DestinationVariable UUID sessionId,
                                    @Payload PlayerActionRequest request,
                                    Principal principal) {
        String username = principal.getName();
        log.info("WebSocket action received: session={}, player={}, action={}",
                sessionId, username, request.action());

        try {
            turnService.submitAction(sessionId, username, request.action(),
                    Boolean.TRUE.equals(request.spendInspiration()));
        } catch (Exception e) {
            log.error("Error processing player action: session={}, player={}",
                    sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/pass")
    public void handlePass(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            turnService.passAction(sessionId, username);
        } catch (Exception e) {
            log.error("Error passing turn: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/roll")
    public void handleRoll(@DestinationVariable UUID sessionId,
                           @Payload RollRequest request,
                           Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            RollMode mode = request.mode() == null ? RollMode.NORMAL : request.mode();
            DiceRollResult result = diceService.roll(request.notation(), mode);
            String label = request.label() == null || request.label().isBlank()
                    ? "Roll" : request.label();

            log.info("Dice roll: session={}, player={}, {}={}",
                    sessionId, username, result.notation(), result.total());

            messagingTemplate.convertAndSend(
                    "/topic/game/" + sessionId,
                    DiceRollEvent.of(sessionId, player.id(), player.characterName(), label, result));
        } catch (Exception e) {
            log.error("Error rolling dice: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/cast")
    public void handleCast(@DestinationVariable UUID sessionId,
                           @Payload CastRequest request,
                           Principal principal) {
        String username = principal.getName();
        try {
            Player player = turnService.requireActiveTurn(sessionId, username);

            // Level 0 = cantrip: no slot consumed.
            if (request.spellLevel() >= 1) {
                PlayerRuntimeStateDto state =
                        playerStateService.useSpellSlot(player.getId(), request.spellLevel());
                broadcastState(sessionId, state);
            }

            String spell = request.spellName() == null || request.spellName().isBlank()
                    ? (request.spellLevel() < 1 ? "a cantrip" : "a level-" + request.spellLevel() + " spell")
                    : request.spellName();

            DiceRollResult attack = null;
            if (request.attackNotation() != null && !request.attackNotation().isBlank()) {
                attack = diceService.roll(request.attackNotation());
                messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                        DiceRollEvent.of(sessionId, player.getId(), player.getCharacterName(),
                                "Spell Attack", attack));
            }

            String summary = player.getCharacterName() + " casts " + spell
                    + (request.spellLevel() >= 1 ? " (level-" + request.spellLevel() + " slot)" : "")
                    + (attack != null ? " — attack roll " + attack.notation() + " = " + attack.total() : "")
                    + ".";
            turnService.submitAction(sessionId, username, summary);
        } catch (Exception e) {
            log.error("Error casting spell: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/use-item")
    public void handleUseItem(@DestinationVariable UUID sessionId,
                              @Payload UseItemRequest request,
                              Principal principal) {
        String username = principal.getName();
        try {
            Player player = turnService.requireActiveTurn(sessionId, username);
            PlayerStateService.ItemUseResult result =
                    playerStateService.useItem(player.getId(), request.itemName());
            broadcastState(sessionId, result.state());

            if (result.healRoll() != null) {
                messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                        DiceRollEvent.of(sessionId, player.getId(), player.getCharacterName(),
                                "Healing", result.healRoll()));
            }

            String summary = player.getCharacterName() + " uses " + result.itemName()
                    + (result.healed() > 0
                            ? " and recovers " + result.healed() + " HP (now "
                                    + result.state().currentHp() + "/" + result.state().maxHp() + ")"
                            : "")
                    + ".";
            turnService.submitAction(sessionId, username, summary);
        } catch (Exception e) {
            log.error("Error using item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/hp")
    public void handleHpChange(@DestinationVariable UUID sessionId,
                               @Payload HpChangeRequest request,
                               Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state =
                    playerStateService.applyHpDelta(player.id(), request.amount());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error changing HP: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/inventory/add")
    public void handleAddItem(@DestinationVariable UUID sessionId,
                              @Payload AddItemRequest request,
                              Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            int qty = Math.max(1, request.qty());
            PlayerRuntimeStateDto state = playerStateService.addItem(
                    player.id(), new InventoryItem(request.name(), qty, request.kind()));
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error adding item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/inventory/drop")
    public void handleDropItem(@DestinationVariable UUID sessionId,
                               @Payload DropItemRequest request,
                               Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state = playerStateService.dropItem(player.id(), request.name());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error dropping item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/inventory/equip")
    public void handleEquipItem(@DestinationVariable UUID sessionId,
                                @Payload EquipItemRequest request,
                                Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state =
                    playerStateService.equipItem(player.id(), request.name(), request.equipped());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error equipping item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/rest")
    public void handleLongRest(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state = playerStateService.longRest(player.id());
            broadcastState(sessionId, state);
            turnService.submitAction(sessionId, username,
                    player.characterName() + " takes a long rest, recovering hit points and spell slots.");
        } catch (Exception e) {
            log.error("Error during long rest: session={}, player={}", sessionId, username, e);
            sendError(username, e);
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
            PlayerDto player = sessionMembershipService.joinSession(sessionId, request, username);
            GameStateDto state = gameSessionService.getGameState(sessionId);

            messagingTemplate.convertAndSendToUser(
                    username, "/queue/joined",
                    (Object) Map.of("player", player, "gameState", state));

        } catch (Exception e) {
            log.error("Error joining session via WebSocket: session={}, player={}",
                    sessionId, username, e);
            sendError(username, e);
        }
    }
}
