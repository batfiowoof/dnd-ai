package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.ai.DmTags;
import com.dungeon.master.service.game.Bestiary;
import com.dungeon.master.service.game.CheckService;
import com.dungeon.master.service.game.CombatService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

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
    private final CheckService checkService;
    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /* ── initiative / freeform: one action → one streamed DM reply ─────────────── */

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

            String dmResponse = dmAiService.generateResponseStreaming(
                    event.sessionId(), event.playerId(), event.playerName(), event.action(),
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", turnNumber,
                            "playerId", event.playerId().toString(),
                            "delta", chunk)));

            postProcess(event.sessionId(), event.playerId(), event.turnEventId(), dmResponse,
                    event.turnNumber(), null);

        } catch (Exception e) {
            log.error("Failed to process player action: session={}, player={}",
                    event.sessionId(), event.playerId(), e);

            String fallbackResponse = "The Dungeon Master is momentarily distracted... " +
                    "[An error occurred processing your action. Please try again.]";
            turnService.recordDmResponse(event.turnEventId(),
                    event.sessionId(), event.playerId(), fallbackResponse, event.turnNumber());
        }
    }

    /* ── collaborative: a whole round → one combined streamed DM reply ─────────── */

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

            String dmResponse = dmAiService.generateCollaborativeResponse(
                    event.sessionId(), actions,
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", turnNumber,
                            "playerId", anchorPlayerId == null ? "" : anchorPlayerId.toString(),
                            "delta", chunk)));

            postProcess(event.sessionId(), anchorPlayerId, event.turnEventId(), dmResponse,
                    event.turnNumber(), actions);

        } catch (Exception e) {
            log.error("Failed to process collaborative round: session={}", event.sessionId(), e);
            String fallbackResponse = "The Dungeon Master is momentarily distracted... " +
                    "[An error occurred resolving the round. Please try again.]";
            turnService.recordDmResponse(event.turnEventId(),
                    event.sessionId(), anchorPlayerId, fallbackResponse, event.turnNumber());
        }
    }

    /* ── shared post-processing: parse DM directive tags, then record + dispatch ─ */

    /**
     * Parse the assembled DM reply for {@code [[ROLL:…]]} and {@code [[ENCOUNTER:…]]} tags,
     * strip them from the narration, record the cleaned narration (which broadcasts the
     * canonical DM response and advances the turn per mode), and dispatch the side effects:
     * pending ability checks and/or a new combat encounter. Tag automation is gated by the
     * session's {@code allowAiRolls} / {@code allowAiCombat} toggles.
     *
     * <p>Roll requests are created BEFORE recording the narration so the turn-advance logic in
     * {@code DmResponseConsumer} sees the pending check and holds the turn.
     */
    private void postProcess(UUID sessionId, UUID anchorPlayerId, UUID turnEventId, String assembled,
                             int turnNumber, List<Contribution> roundActions) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        boolean allowRolls = session == null || session.isAllowAiRolls();
        boolean allowCombat = session == null || session.isAllowAiCombat();
        boolean collaborative = roundActions != null;

        String cleaned = DmTags.strip(assembled);
        if (cleaned.isBlank()) {
            cleaned = "The Dungeon Master considers the scene.";
        }

        // 1) Ability-check requests (before recording so the turn holds for the roller).
        if (allowRolls) {
            List<DmTags.RollTag> rolls = DmTags.parseRolls(assembled);
            if (!rolls.isEmpty()) {
                UUID roundToken = (collaborative && rolls.size() > 1) ? UUID.randomUUID() : null;
                for (DmTags.RollTag rt : rolls) {
                    Player target = resolveRollTarget(sessionId, rt.player(), anchorPlayerId, roundActions);
                    if (target == null) {
                        continue;
                    }
                    int dc = rt.dc() > 0 ? rt.dc() : defaultDc(session);
                    checkService.createPendingCheck(sessionId, target, rt.ability(), dc,
                            rt.skill(), rt.reason(), turnEventId, roundToken);
                }
            }
        }

        // 2) Record the cleaned narration onto the exact turn-event by id → DmResponseConsumer
        //    broadcasts the canonical response + advances per mode.
        turnService.recordDmResponse(turnEventId, sessionId, anchorPlayerId, cleaned, turnNumber);

        // 3) Combat trigger — only if allowed and no encounter is already active.
        if (allowCombat && combatService.getActiveCombat(sessionId).isEmpty()) {
            List<String> keys = DmTags.parseEncounter(assembled, Bestiary.keys());
            if (!keys.isEmpty()) {
                log.info("DM-triggered encounter: session={}, enemies={}", sessionId, keys);
                combatService.startEncounter(sessionId, keys);
            }
        }
    }

    /**
     * Resolve which player a {@code [[ROLL:…]]} tag targets. In the single-action path the
     * target is the acting player. In a collaborative round, {@code player=Name} names the
     * character; match it case-insensitively among the round's contributors, falling back to
     * the anchor only when no name was given.
     */
    private Player resolveRollTarget(UUID sessionId, String playerName, UUID anchorPlayerId,
                                     List<Contribution> roundActions) {
        if (roundActions == null || playerName == null || playerName.isBlank()) {
            return anchorPlayerId == null ? null : playerRepository.findById(anchorPlayerId).orElse(null);
        }
        return roundActions.stream()
                .filter(c -> c.characterName() != null && c.characterName().equalsIgnoreCase(playerName.trim()))
                .findFirst()
                .map(Contribution::playerId)
                .flatMap(playerRepository::findById)
                .orElse(null);
    }

    /** Fallback DC when a [[ROLL]] tag omits one — banded by session difficulty. */
    private int defaultDc(GameSession session) {
        Difficulty d = session == null ? Difficulty.NORMAL : session.getDifficulty();
        return switch (d) {
            case EASY -> 10;
            case NORMAL -> 13;
            case DEADLY -> 17;
        };
    }
}
