package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.model.dto.DiceRollEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.RollRequestEvent;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.PendingCheck;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.PendingCheckRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.DmAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LLM-requested ability checks. Phase 1 ({@link #createPendingCheck}) persists the DM's
 * request and prompts the player. Phase 2 ({@link #resolveCheck}) rolls authoritatively via
 * {@link DiceService}, broadcasts the dice animation, and streams a DM narration of the
 * outcome. The DC and modifier are computed server-side — the client only chooses
 * normal / advantage / disadvantage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckService {

    private final PendingCheckRepository pendingCheckRepository;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final PlayerStateService playerStateService;
    private final DiceService diceService;
    private final DmAiService dmAiService;
    private final TurnService turnService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * In-memory accumulator of collaborative round check results, keyed by
     * {@code "<sessionId>:<roundToken>"}. Single-instance only — like {@code RoundCollector},
     * a multi-instance deployment would need a shared store.
     */
    private final Map<String, List<String>> batches = new ConcurrentHashMap<>();

    /* ── phase 1: request ────────────────────────────────────────── */

    /** Persist a pending check for a player and broadcast a ROLL_REQUEST to the room. */
    @Transactional
    public void createPendingCheck(UUID sessionId, Player player, String ability, int dc,
                                   String skill, String reason, UUID turnEventId, UUID roundToken) {
        PendingCheck pc = PendingCheck.builder()
                .sessionId(sessionId)
                .playerId(player.getId())
                .ability(ability)
                .dc(dc)
                .skill(skill)
                .reason(reason)
                .turnEventId(turnEventId)
                .roundToken(roundToken)
                .build();
        pendingCheckRepository.save(pc);

        int suggested = computeModifier(player, ability, skill);
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                RollRequestEvent.of(sessionId, player.getId(), ability, dc, skill, reason, suggested));
        log.info("Pending check created: session={}, player={}, {} DC{}",
                sessionId, player.getId(), ability, dc);
    }

    /* ── phase 2: resolve ────────────────────────────────────────── */

    /**
     * Roll the player's pending check, broadcast the dice, then stream a DM narration of the
     * outcome. Not wrapped in a single transaction — the multi-second LLM stream must not hold
     * a DB connection; the small mutations go through individually-transactional helpers.
     */
    public void resolveCheck(UUID sessionId, String username, RollMode mode) {
        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + username));
        PendingCheck pc = pendingCheckRepository.findBySessionIdAndPlayerId(sessionId, player.getId())
                .orElseThrow(() -> new IllegalStateException("You have no pending check to roll"));

        RollMode rollMode = mode == null ? RollMode.NORMAL : mode;
        int modifier = computeModifier(player, pc.getAbility(), pc.getSkill());
        DiceRollResult result = diceService.roll(notation(modifier), rollMode);
        boolean success = result.total() >= pc.getDc();

        String skillPart = (pc.getSkill() == null || pc.getSkill().isBlank())
                ? "" : " (" + pc.getSkill() + ")";
        String label = pc.getAbility() + skillPart + " check";

        // Animate the authoritative roll (reuses the existing dice modal).
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                DiceRollEvent.of(sessionId, player.getId(), player.getCharacterName(), label, result));

        UUID roundToken = pc.getRoundToken();
        String checkLine = player.getCharacterName() + "'s " + label
                + ": " + result.total() + " vs DC " + pc.getDc()
                + " — " + (success ? "SUCCESS" : "FAILURE");

        if (roundToken != null) {
            // Batched path consumes the pending row INSIDE the per-token critical section so the
            // "am I the last roller?" decision is atomic (see resolveBatched).
            resolveBatched(sessionId, player.getId(), roundToken, pc, checkLine);
        } else {
            // Consume the request so reconnects / double-rolls can't re-trigger it.
            pendingCheckRepository.delete(pc);
            resolveSingle(sessionId, player, pc.getReason(), pc.getAbility(), pc.getSkill(),
                    pc.getDc(), result.total(), success, checkLine);
        }
        log.info("Check resolved: session={}, player={}, total={}, success={}",
                sessionId, player.getId(), result.total(), success);
    }

    /** Single-player path: narrate this check's outcome immediately. */
    private void resolveSingle(UUID sessionId, Player player, String reason, String ability,
                               String skill, int dc, int total, boolean success, String checkLine) {
        TurnEvent beat = turnService.createNarrativeBeat(sessionId, player.getId(), checkLine + ".");
        int turnNumber = beat.getTurnNumber();
        String destination = "/topic/game/" + sessionId;

        messagingTemplate.convertAndSend(destination, (Object) Map.of(
                "type", "DM_THINKING",
                "turnNumber", String.valueOf(turnNumber),
                "playerId", player.getId().toString(),
                "playerName", player.getCharacterName(),
                "action", checkLine + "."));

        String narration = dmAiService.generateCheckResolution(
                sessionId, player.getId(), reason, ability, skill, dc, total, success,
                chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                        "type", "DM_CHUNK",
                        "turnNumber", String.valueOf(turnNumber),
                        "playerId", player.getId().toString(),
                        "delta", chunk)));

        turnService.recordDmResponse(beat.getId(), sessionId, player.getId(), narration, turnNumber);
    }

    /**
     * Collaborative path: accumulate this check's result and decide — atomically — whether it is
     * the last of its round token. The delete + accumulate + remaining-count run inside a
     * per-token monitor so two near-simultaneous rolls can't both fire (or both defer) the
     * combined narration. The slow LLM stream happens AFTER the lock is released.
     */
    private void resolveBatched(UUID sessionId, UUID playerId, UUID roundToken,
                                PendingCheck pc, String checkLine) {
        String key = sessionId + ":" + roundToken;
        List<String> lines = batches.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());

        String summary;
        synchronized (lines) {
            // Claim this roll: delete the row (committed), record its line, then count what's left.
            pendingCheckRepository.delete(pc);
            lines.add(checkLine);
            long remaining = pendingCheckRepository.countBySessionIdAndRoundToken(sessionId, roundToken);
            if (remaining > 0) {
                log.info("Batched check pending: session={}, token={}, remaining={}",
                        sessionId, roundToken, remaining);
                return; // not the last roller — another thread will narrate.
            }
            batches.remove(key);
            summary = String.join("\n", lines);
        }

        String action = "The party's checks resolve:\n" + summary;
        TurnEvent beat = turnService.createNarrativeBeat(sessionId, playerId, action);
        int turnNumber = beat.getTurnNumber();
        String destination = "/topic/game/" + sessionId;

        messagingTemplate.convertAndSend(destination, (Object) Map.of(
                "type", "DM_THINKING",
                "turnNumber", String.valueOf(turnNumber),
                "playerId", playerId.toString(),
                "playerName", "The Party",
                "action", action));

        String narration = dmAiService.generateBatchedCheckResolution(
                sessionId, summary,
                chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                        "type", "DM_CHUNK",
                        "turnNumber", String.valueOf(turnNumber),
                        "playerId", playerId.toString(),
                        "delta", chunk)));

        turnService.recordDmResponse(beat.getId(), sessionId, playerId, narration, turnNumber);
    }

    /* ── modifier (v1: ability mod + proficiency only when the skill is proficient) ── */

    /**
     * Ability modifier from runtime state, plus the character's proficiency bonus when the
     * named skill is one the character is proficient in. Falls back to ability mod only when
     * skill/proficiency data is absent (no character, no skill, or not proficient) — kept
     * deliberately simple for v1, since runtime state carries no per-skill proficiency data.
     */
    private int computeModifier(Player player, String ability, String skill) {
        int score = 10;
        try {
            PlayerRuntimeStateDto st = playerStateService.getState(player.getId());
            if (st.abilities() != null) {
                score = st.abilities().getOrDefault(ability.toUpperCase(Locale.ROOT), 10);
            }
        } catch (RuntimeException e) {
            // No runtime state — default ability score.
        }
        int mod = Math.floorDiv(score - 10, 2);

        if (player.getCharacterId() != null && skill != null && !skill.isBlank()) {
            Character c = characterRepository.findById(player.getCharacterId()).orElse(null);
            if (c != null && isProficient(c, skill)) {
                mod += c.getProficiencyBonus();
            }
        }
        return mod;
    }

    private boolean isProficient(Character c, String skill) {
        if (c.getProficiencies() == null) return false;
        return c.getProficiencies().stream()
                .anyMatch(p -> p != null && p.equalsIgnoreCase(skill.trim()));
    }

    private String notation(int modifier) {
        return "1d20" + (modifier > 0 ? "+" + modifier : modifier < 0 ? String.valueOf(modifier) : "");
    }
}
