package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.ai.DmTags;
import com.dungeon.master.service.game.Bestiary;
import com.dungeon.master.service.game.CombatService;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlayerActionConsumer {

    private final DmAiService dmAiService;
    private final TurnService turnService;
    private final CombatService combatService;
    private final com.dungeon.master.service.game.MonsterCatalog monsterCatalog;
    private final PlayerStateService playerStateService;
    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /* ── initiative / freeform: one action → one streamed DM turn (rolls via tools) ─────── */

    @KafkaListener(topics = KafkaConfig.TOPIC_PLAYER_ACTION, groupId = "dnd-ai-dm-group")
    public void handlePlayerAction(PlayerActionEvent event) {
        log.info("Received player action: session={}, player={}, turn={}",
                event.sessionId(), event.playerId(), event.turnNumber());

        String destination = "/topic/game/" + event.sessionId();
        String turnNumber = String.valueOf(event.turnNumber());

        try {
            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_THINKING",
                    "turnNumber", turnNumber,
                    "playerId", event.playerId().toString(),
                    "playerName", event.playerName(),
                    "action", event.action()));

            List<Contribution> actions = List.of(new Contribution(
                    event.playerId(), event.playerName(), event.action(), event.spendInspiration()));
            Map<UUID, Boolean> spend = Map.of(event.playerId(), event.spendInspiration());

            DmAiService.NarrativeTurnResult result = dmAiService.generateNarrativeTurn(
                    event.sessionId(), actions, spend,
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", turnNumber,
                            "playerId", event.playerId().toString(),
                            "delta", chunk)));

            postProcess(event.sessionId(), event.playerId(), event.turnEventId(),
                    result.narration(), event.turnNumber(), result.rolled());

        } catch (Exception e) {
            log.error("Failed to process player action: session={}, player={}",
                    event.sessionId(), event.playerId(), e);

            String fallbackResponse = "The Dungeon Master is momentarily distracted... " +
                    "[An error occurred processing your action. Please try again.]";
            turnService.recordDmResponse(event.turnEventId(),
                    event.sessionId(), event.playerId(), fallbackResponse, event.turnNumber());
        }
    }

    /* ── collaborative: a whole round → one combined streamed DM turn ─────────── */

    @KafkaListener(topics = KafkaConfig.TOPIC_ROUND_ACTION, groupId = "dnd-ai-round-group")
    public void handleRoundAction(RoundActionEvent event) {
        log.info("Received collaborative round: session={}, turn={}, actions={}",
                event.sessionId(), event.turnNumber(), event.actions().size());

        String destination = "/topic/game/" + event.sessionId();
        String turnNumber = String.valueOf(event.turnNumber());
        List<Contribution> actions = event.actions();
        UUID anchorPlayerId = actions.isEmpty() ? null : actions.get(0).playerId();
        String combined = actions.stream()
                .map(c -> c.passed() ? c.characterName() + " holds back."
                        : c.characterName() + ": " + c.action())
                .collect(Collectors.joining("\n"));

        try {
            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_THINKING",
                    "turnNumber", turnNumber,
                    "playerId", anchorPlayerId == null ? "" : anchorPlayerId.toString(),
                    "playerName", "The Party",
                    "action", combined));

            Map<UUID, Boolean> spend = new HashMap<>();
            for (Contribution c : actions) {
                spend.put(c.playerId(), c.spendInspiration());
            }

            DmAiService.NarrativeTurnResult result = dmAiService.generateNarrativeTurn(
                    event.sessionId(), actions, spend,
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", turnNumber,
                            "playerId", anchorPlayerId == null ? "" : anchorPlayerId.toString(),
                            "delta", chunk)));

            postProcess(event.sessionId(), anchorPlayerId, event.turnEventId(),
                    result.narration(), event.turnNumber(), result.rolled());

        } catch (Exception e) {
            log.error("Failed to process collaborative round: session={}", event.sessionId(), e);
            String fallbackResponse = "The Dungeon Master is momentarily distracted... " +
                    "[An error occurred resolving the round. Please try again.]";
            turnService.recordDmResponse(event.turnEventId(),
                    event.sessionId(), anchorPlayerId, fallbackResponse, event.turnNumber());
        }
    }

    /* ── shared post-processing: award inspiration, record, maybe start combat ─ */

    /**
     * Strip directive tags from the narration, award any [[INSPIRATION]] the DM granted, record the
     * cleaned narration (which broadcasts the canonical DM response and advances the turn per mode),
     * and start combat if the DM emitted an [[ENCOUNTER]] tag. Ability checks are no longer parsed
     * here — they are resolved inline via the engine roll tools during the DM turn ({@code rolled}
     * reports whether any roll happened, so a check turn doesn't also start a fight).
     */
    private void postProcess(UUID sessionId, UUID anchorPlayerId, UUID turnEventId, String assembled,
                             int turnNumber, boolean rolled) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        boolean allowRolls = session == null || session.isAllowAiRolls();
        boolean allowCombat = session == null || session.isAllowAiCombat();

        String cleaned = DmTags.strip(assembled);
        if (cleaned.isBlank()) {
            cleaned = "The Dungeon Master considers the scene.";
        }

        // Inspiration awards — gated with the rolls toggle. Resolve the named player across the
        // whole session; a bare tag falls back to the acting/anchor player.
        if (allowRolls) {
            for (DmTags.InspirationTag it : DmTags.parseInspiration(assembled)) {
                Player target = resolveInspirationTarget(sessionId, it.player(), anchorPlayerId);
                if (target == null) {
                    continue;
                }
                awardInspiration(sessionId, target);
            }
        }

        // Record the cleaned narration onto the exact turn-event → DmResponseConsumer broadcasts the
        // canonical response + advances per mode.
        turnService.recordDmResponse(turnEventId, sessionId, anchorPlayerId, cleaned, turnNumber);

        // Combat trigger — only if allowed, no encounter is already active, and THIS turn did not roll
        // a skill/ability check (a check means the action was non-combat, so it must not also fight).
        if (allowCombat && !rolled && combatService.getActiveCombat(sessionId).isEmpty()) {
            List<String> keys = DmTags.parseEncounter(assembled,
                    monsterCatalog.isEmpty() ? Bestiary.keys() : monsterCatalog.keys());
            if (!keys.isEmpty()) {
                log.info("DM-triggered encounter: session={}, enemies={}", sessionId, keys);
                combatService.startEncounter(sessionId, keys);
            }
        }
    }

    /**
     * Resolve which player an {@code [[INSPIRATION:…]]} tag awards. Match {@code player=}
     * case-insensitively against every session member's character name (then username), with a
     * first-token fallback for multi-word names. When a name was given but matches nobody, return
     * null (skip) rather than defaulting to the anchor — never award to the wrong player. The anchor
     * fallback applies ONLY when no name was given.
     */
    private Player resolveInspirationTarget(UUID sessionId, String playerName, UUID anchorPlayerId) {
        if (playerName == null || playerName.isBlank()) {
            return anchorPlayerId == null ? null : playerRepository.findById(anchorPlayerId).orElse(null);
        }
        String name = playerName.trim();
        List<Player> members = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .toList();
        for (Player p : members) {
            if (name.equalsIgnoreCase(p.getCharacterName()) || name.equalsIgnoreCase(p.getUsername())) {
                return p;
            }
        }
        for (Player p : members) {
            if (name.equalsIgnoreCase(firstToken(p.getCharacterName()))) {
                return p;
            }
        }
        return null;
    }

    /** First whitespace-delimited token of a name, or null when the name is null/blank. */
    private static String firstToken(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int sp = 0;
        while (sp < trimmed.length() && !Character.isWhitespace(trimmed.charAt(sp))) {
            sp++;
        }
        return trimmed.substring(0, sp);
    }

    /**
     * Grant Inspiration to a player, broadcast the updated runtime state, and drop a short system
     * line into the room. Fails safe — a player with no seeded runtime state is skipped.
     */
    private void awardInspiration(UUID sessionId, Player target) {
        try {
            PlayerRuntimeStateDto state = playerStateService.grantInspiration(target.getId());
            messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                    PlayerStateEvent.of(sessionId, state));
            String name = target.getCharacterName() != null && !target.getCharacterName().isBlank()
                    ? target.getCharacterName() : target.getUsername();
            messagingTemplate.convertAndSend("/topic/game/" + sessionId, (Object) Map.of(
                    "type", "SYSTEM",
                    "sessionId", sessionId.toString(),
                    "text", name + " gains Inspiration!"));
            log.info("Inspiration granted: session={}, player={}", sessionId, target.getId());
        } catch (Exception e) {
            log.warn("Failed to grant inspiration: session={}, player={}: {}",
                    sessionId, target.getId(), e.getMessage());
        }
    }
}
