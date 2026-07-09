package com.dungeon.master.websocket;

import com.dungeon.master.model.dto.AddItemRequest;
import com.dungeon.master.model.dto.AttuneItemRequest;
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
import com.dungeon.master.model.dto.PreparedSpellsRequest;
import com.dungeon.master.model.dto.RerollChoiceRequest;
import com.dungeon.master.model.dto.RollRequest;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.dto.ShopBuyRequest;
import com.dungeon.master.model.dto.ShopSellRequest;
import com.dungeon.master.model.dto.ShortRestRequest;
import com.dungeon.master.model.dto.TravelRequest;
import com.dungeon.master.model.dto.UseItemRequest;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.RerollResource;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.GameSessionService;
import com.dungeon.master.service.game.PlayerService;
import com.dungeon.master.service.game.SessionMembershipService;
import com.dungeon.master.service.game.GameClockService;
import com.dungeon.master.service.game.MoneyUtil;
import com.dungeon.master.service.ai.SpellCatalog;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.RerollWindow;
import com.dungeon.master.service.game.ShopService;
import com.dungeon.master.service.game.ShopService.ShopTxnResult;
import com.dungeon.master.service.game.TravelService;
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
    private final ShopService shopService;
    private final DiceService diceService;
    private final TravelService travelService;
    private final GameClockService gameClockService;
    private final RerollWindow rerollWindow;
    private final SpellCatalog spellCatalog;

    @MessageMapping("/game/{sessionId}/roll/reroll")
    public void handleReroll(@DestinationVariable UUID sessionId,
                             @Payload RerollChoiceRequest request,
                             Principal principal) {
        // Completes the decision window the DM's roll tool is blocked on; the tool applies the spend.
        rerollWindow.resolve(sessionId, request.promptId(), RerollResource.parse(request.resource()));
    }

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
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/travel")
    public void handleTravel(@DestinationVariable UUID sessionId,
                             @Payload TravelRequest request,
                             Principal principal) {
        String username = principal.getName();
        log.info("WebSocket travel received: session={}, player={}, to={}",
                sessionId, username, request.destinationRegion());
        try {
            travelService.travel(sessionId, username, request.destinationRegion(),
                    request.destinationSubregion(), request.pace());
        } catch (Exception e) {
            log.error("Error processing travel: session={}, player={}", sessionId, username, e);
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
            PlayerRuntimeStateDto st = playerStateService.getState(player.getId());
            String name = request.spellName();
            boolean ritual = request.ritual();

            if (ritual) {
                // Ritual cast: a known, Ritual-tagged spell, cast without expending a slot.
                SpellEffect eff = name == null ? null : spellCatalog.effect(name).orElse(null);
                if (eff == null || !eff.ritual()) {
                    throw new IllegalStateException(
                            (name == null || name.isBlank() ? "That spell" : name) + " can't be cast as a ritual.");
                }
                if (!containsIgnoreCase(st.knownSpells(), name)) {
                    throw new IllegalStateException("You don't know " + name + ".");
                }
            } else {
                // A named leveled cast must be prepared (cantrips are always available); a generic
                // "cast at slot level" with no name stays lenient for free-form narration.
                if (request.spellLevel() >= 1 && name != null && !name.isBlank()
                        && !containsIgnoreCase(st.cantrips(), name)
                        && !containsIgnoreCase(st.preparedSpells(), name)) {
                    throw new IllegalStateException("You don't have " + name + " prepared.");
                }
                // Level 0 = cantrip: no slot consumed.
                if (request.spellLevel() >= 1) {
                    PlayerRuntimeStateDto state =
                            playerStateService.useSpellSlot(player.getId(), request.spellLevel());
                    broadcastState(sessionId, state);
                }
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

            String castNote = ritual ? " as a ritual (no slot)"
                    : (request.spellLevel() >= 1 ? " (level-" + request.spellLevel() + " slot)" : "");
            String summary = player.getCharacterName() + " casts " + spell + castNote
                    + (attack != null ? " — attack roll " + attack.notation() + " = " + attack.total() : "")
                    + ".";
            turnService.submitAction(sessionId, username, summary);
        } catch (Exception e) {
            log.error("Error casting spell: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/prepare")
    public void handlePrepareSpells(@DestinationVariable UUID sessionId,
                                    @Payload PreparedSpellsRequest request,
                                    Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state = playerStateService.setPreparedSpells(
                    player.id(), request == null ? null : request.spells());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error preparing spells: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    /** Case-insensitive membership test for a spell name in a name list. */
    private static boolean containsIgnoreCase(java.util.List<String> names, String name) {
        if (names == null || name == null) return false;
        for (String n : names) {
            if (name.equalsIgnoreCase(n)) return true;
        }
        return false;
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
                    player.id(),
                    new InventoryItem(request.name(), qty, request.kind(), false, null, request.subtype()));
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
                    playerStateService.equipItem(player.id(), request.name(), request.slot());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error equipping item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/attunement/attune")
    public void handleAttuneItem(@DestinationVariable UUID sessionId,
                                 @Payload AttuneItemRequest request,
                                 Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state = playerStateService.attuneItem(player.id(), request.name());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error attuning item: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/attunement/end")
    public void handleEndAttunement(@DestinationVariable UUID sessionId,
                                    @Payload AttuneItemRequest request,
                                    Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            PlayerRuntimeStateDto state = playerStateService.endAttunement(player.id(), request.name());
            broadcastState(sessionId, state);
        } catch (Exception e) {
            log.error("Error ending attunement: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/shop/buy")
    public void handleShopBuy(@DestinationVariable UUID sessionId,
                              @Payload ShopBuyRequest request,
                              Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            ShopTxnResult r = shopService.buy(
                    sessionId, player.id(), request.shopKey(), request.itemRef(), request.qty());
            broadcastState(sessionId, r.state());
            broadcastShopLog(sessionId, player.characterName() + " bought " + r.qty() + "× "
                    + r.itemName() + " for " + MoneyUtil.format(-r.copperDelta()) + " at " + r.shopName());
        } catch (Exception e) {
            log.error("Error buying: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/shop/sell")
    public void handleShopSell(@DestinationVariable UUID sessionId,
                               @Payload ShopSellRequest request,
                               Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            ShopTxnResult r = shopService.sell(
                    sessionId, player.id(), request.shopKey(), request.name(), request.qty());
            broadcastState(sessionId, r.state());
            broadcastShopLog(sessionId, player.characterName() + " sold " + r.qty() + "× "
                    + r.itemName() + " for " + MoneyUtil.format(r.copperDelta()) + " at " + r.shopName());
        } catch (Exception e) {
            log.error("Error selling: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    /** Announce a completed trade to the whole table as a system-log line (mirrors the QUEST broadcast). */
    private void broadcastShopLog(UUID sessionId, String text) {
        messagingTemplate.convertAndSend("/topic/game/" + sessionId, (Object) Map.of(
                "type", "SHOP",
                "sessionId", sessionId.toString(),
                "text", text));
    }

    @MessageMapping("/game/{sessionId}/rest")
    public void handleLongRest(@DestinationVariable UUID sessionId, Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            // A long rest is 8 hours of in-game time; advance the shared clock, then apply the rest
            // (which resets this player's exhaustion window to now) and accrue for anyone still overdue.
            long now = gameClockService.advanceClock(sessionId, GameClockService.LONG_REST_MINUTES);
            PlayerRuntimeStateDto state = playerStateService.longRest(player.id(), now);
            broadcastState(sessionId, state);
            gameClockService.accrueAndBroadcast(sessionId);
            turnService.submitAction(sessionId, username,
                    player.characterName() + " takes a long rest (8 hours), recovering hit points and spell slots.");
        } catch (Exception e) {
            log.error("Error during long rest: session={}, player={}", sessionId, username, e);
            sendError(username, e);
        }
    }

    @MessageMapping("/game/{sessionId}/short-rest")
    public void handleShortRest(@DestinationVariable UUID sessionId,
                                @Payload ShortRestRequest request,
                                Principal principal) {
        String username = principal.getName();
        try {
            PlayerDto player = playerService.getPlayerInSession(sessionId, username);
            int hitDice = request == null ? 1 : Math.max(0, request.hitDice());
            // A short rest is at least an hour; advance the clock, spend Hit Dice to heal, then accrue.
            gameClockService.advanceClock(sessionId, GameClockService.SHORT_REST_MINUTES);
            PlayerRuntimeStateDto state = playerStateService.shortRest(player.id(), hitDice);
            broadcastState(sessionId, state);
            gameClockService.accrueAndBroadcast(sessionId);
            turnService.submitAction(sessionId, username,
                    player.characterName() + " takes a short rest (about an hour), tending to wounds.");
        } catch (Exception e) {
            log.error("Error during short rest: session={}, player={}", sessionId, username, e);
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
