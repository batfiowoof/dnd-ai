package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.ai.DmTags;
import com.dungeon.master.service.game.Bestiary;
import com.dungeon.master.service.game.CheckService;
import com.dungeon.master.service.game.CombatService;
import com.dungeon.master.service.game.PlayerStateService;
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
    private final com.dungeon.master.service.game.MonsterCatalog monsterCatalog;
    private final CheckService checkService;
    private final PlayerStateService playerStateService;
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

        // Parse every check-type tag up front (regardless of the allowRolls toggle) so we can tell
        // whether THIS turn is resolving a non-combat skill/ability check. A turn that asks for a
        // check must NOT also start combat (a lock-pick / sneak / search is not a fight) — see the
        // combat gate below. Combat initiative is engine-rolled in startEncounter, never via a ROLL
        // tag, so treating any of these as "a check was requested" is safe.
        List<DmTags.RollTag> rolls = DmTags.parseRolls(assembled);
        DmTags.GroupTag group = DmTags.parseGroup(assembled);
        List<DmTags.ContestTag> contests = DmTags.parseContest(assembled);
        boolean checkRequestedThisTurn = !rolls.isEmpty() || group != null || !contests.isEmpty();

        // 1) Ability-check requests (before recording so the turn holds for the roller).
        if (allowRolls) {
            if (!rolls.isEmpty()) {
                UUID roundToken = (collaborative && rolls.size() > 1) ? UUID.randomUUID() : null;
                for (DmTags.RollTag rt : rolls) {
                    Player target = resolveRollTarget(sessionId, rt.player(), anchorPlayerId, roundActions);
                    if (target == null) {
                        continue;
                    }
                    int dc = rt.dc() > 0 ? rt.dc() : defaultDc(session);
                    checkService.createPendingCheck(sessionId, target, rt.ability(), dc,
                            rt.skill(), rt.reason(), rt.mode(), turnEventId, roundToken);
                }
            }

            // Group check — one check imposed on the whole party (shares a group token). Resolution
            // applies the half-the-party success rule; abandonment is handled by a timeout sweep.
            if (group != null) {
                int dc = group.dc() > 0 ? group.dc() : defaultDc(session);
                checkService.createGroupCheck(sessionId, group.ability(), dc, group.skill(),
                        group.reason(), turnEventId);
            }

            // Contest — one actor opposed by an NPC the engine rolls. Resolve the named actor across
            // the whole session (same logic as an inspiration target). targetMod falls back to a
            // difficulty band when the tag omits it.
            for (DmTags.ContestTag ct : contests) {
                Player actor = resolveInspirationTarget(sessionId, ct.actor(), anchorPlayerId);
                if (actor == null) {
                    continue;
                }
                int targetMod = ct.targetMod() != null ? ct.targetMod() : defaultContestMod(session);
                checkService.createContestCheck(sessionId, actor, ct.actorAbility(), ct.actorSkill(),
                        targetMod, ct.targetLabel(), ct.reason(), turnEventId);
            }

            // Inspiration awards — gated with rolls (same automation toggle). Resolve the named
            // player across the whole session; a bare tag falls back to the acting/anchor player.
            for (DmTags.InspirationTag it : DmTags.parseInspiration(assembled)) {
                Player target = resolveInspirationTarget(sessionId, it.player(), anchorPlayerId);
                if (target == null) {
                    continue;
                }
                awardInspiration(sessionId, target);
            }
        }

        // 2) Record the cleaned narration onto the exact turn-event by id → DmResponseConsumer
        //    broadcasts the canonical response + advances per mode.
        turnService.recordDmResponse(turnEventId, sessionId, anchorPlayerId, cleaned, turnNumber);

        // 3) Combat trigger — only if allowed, no encounter is already active, and THIS turn did
        //    not resolve a skill/ability check (a check means the action was non-combat, so it must
        //    not also spawn a fight — e.g. a lock-pick that triggered a roll).
        if (allowCombat && !checkRequestedThisTurn && combatService.getActiveCombat(sessionId).isEmpty()) {
            List<String> keys = DmTags.parseEncounter(assembled,
                    monsterCatalog.isEmpty() ? Bestiary.keys() : monsterCatalog.keys());
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
     * the anchor only when no name was given. The DM is asked to QUOTE multi-word names, but as a
     * safety net (the KV parser truncates an unquoted name to its first word) we also try matching
     * the value against the first whitespace token of each candidate's character name.
     */
    private Player resolveRollTarget(UUID sessionId, String playerName, UUID anchorPlayerId,
                                     List<Contribution> roundActions) {
        if (roundActions == null || playerName == null || playerName.isBlank()) {
            return anchorPlayerId == null ? null : playerRepository.findById(anchorPlayerId).orElse(null);
        }
        String name = playerName.trim();
        // Exact case-insensitive match on the character name.
        for (Contribution c : roundActions) {
            if (name.equalsIgnoreCase(c.characterName())) {
                return playerRepository.findById(c.playerId()).orElse(null);
            }
        }
        // Resilient fallback: first whitespace token of the character name (e.g. "Aria" ← "Aria Brightblade").
        for (Contribution c : roundActions) {
            if (name.equalsIgnoreCase(firstToken(c.characterName()))) {
                return playerRepository.findById(c.playerId()).orElse(null);
            }
        }
        return null;
    }

    /**
     * Resolve which player an {@code [[INSPIRATION:…]]} tag awards. Unlike a roll target (which is
     * the acting player), an inspiration award names anyone in the party — so match {@code player=}
     * case-insensitively against every session member's character name (then username). The DM is
     * asked to QUOTE multi-word names; as a safety net we also try the first whitespace token of
     * each candidate's character name. CRITICAL: when a name WAS given but matches nobody we return
     * null (skip the award) rather than defaulting to the anchor — never award Inspiration to the
     * wrong player. The anchor fallback applies ONLY when no name was given.
     */
    private Player resolveInspirationTarget(UUID sessionId, String playerName, UUID anchorPlayerId) {
        if (playerName == null || playerName.isBlank()) {
            return anchorPlayerId == null ? null : playerRepository.findById(anchorPlayerId).orElse(null);
        }
        String name = playerName.trim();
        List<Player> members = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .toList();
        // Exact case-insensitive match on character name, then username.
        for (Player p : members) {
            if (name.equalsIgnoreCase(p.getCharacterName()) || name.equalsIgnoreCase(p.getUsername())) {
                return p;
            }
        }
        // Resilient fallback: first whitespace token of the character name (e.g. "Aria" ← "Aria Brightblade").
        for (Player p : members) {
            if (name.equalsIgnoreCase(firstToken(p.getCharacterName()))) {
                return p;
            }
        }
        // A name was given but matched nobody — skip rather than award to the wrong player.
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
     * line into the room. Fails safe — a player with no seeded runtime state is skipped rather than
     * crashing the consumer. The system line uses an unknown {@code SYSTEM} type that the current
     * frontend drops silently; it renders once the frontend roll phase wires it up.
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

    /** Fallback DC when a [[ROLL]] tag omits one — banded by session difficulty. */
    private int defaultDc(GameSession session) {
        Difficulty d = session == null ? Difficulty.NORMAL : session.getDifficulty();
        return switch (d) {
            case EASY -> 10;
            case NORMAL -> 13;
            case DEADLY -> 17;
        };
    }

    /**
     * Fallback NPC modifier when a [[CONTEST]] tag omits {@code targetMod} — banded by difficulty,
     * mirroring {@link #defaultDc}. The NPC side rolls {@code 1d20 + this}, so a higher band makes
     * the opposed party tougher to beat.
     */
    private int defaultContestMod(GameSession session) {
        Difficulty d = session == null ? Difficulty.NORMAL : session.getDifficulty();
        return switch (d) {
            case EASY -> 2;
            case NORMAL -> 4;
            case DEADLY -> 6;
        };
    }
}
