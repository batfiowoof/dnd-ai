package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.model.dto.DiceRollEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.RollRequestEvent;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.PendingCheck;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CheckKind;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.PendingCheckRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.DmAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * outcome. The DC and modifier are computed server-side. The roll mode is computed server-side
 * too: the DM's situational ADVANTAGE/DISADVANTAGE (persisted on the pending check) is RAW-combined
 * with ADVANTAGE if the player spends Inspiration — the client's only roll-mode lever.
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
    /** Shared scheduler — used to narrate a GROUP dropout off the caller's transactional thread. */
    private final TaskScheduler taskScheduler;

    /**
     * In-memory accumulator of collaborative round check results, keyed by
     * {@code "<sessionId>:<roundToken>"}. Single-instance only — like {@code RoundCollector},
     * a multi-instance deployment would need a shared store.
     */
    private final Map<String, List<String>> batches = new ConcurrentHashMap<>();

    /**
     * In-memory accumulator of GROUP check results, keyed by {@code "<sessionId>:<groupToken>"}.
     * Tracks per-player success (not just a formatted line) so the half-the-party rule can be
     * applied on completion. Single-instance only, like {@link #batches} — a multi-instance
     * deployment would need a shared store, and successes accumulated before a restart are lost
     * (the abandonment sweep still safely resolves the surviving DB rows either way).
     */
    private final Map<String, GroupBatch> groupBatches = new ConcurrentHashMap<>();

    /** Seconds after which an unfinished GROUP check is force-resolved with the rolls received. */
    @Value("${dnd.group-check.timeout-seconds:60}")
    private long groupTimeoutSeconds;

    /**
     * Mutable accumulator for one GROUP check. Mutated only under {@code synchronized(this)} on the
     * instance itself. {@code done} fast-fails same-instance waiters; the real cross-path guarantee
     * (last-roller vs sweep) comes from the mutually-exclusive DB delete counts.
     */
    private static final class GroupBatch {
        final List<String> lines = new CopyOnWriteArrayList<>();
        int successes = 0;
        UUID anyPlayerId;   // a participant id, for attributing the narrative beat
        boolean done = false;
    }

    /* ── phase 1: request ────────────────────────────────────────── */

    /**
     * Persist a pending check for a player and broadcast a ROLL_REQUEST to the room. {@code dmMode}
     * is the DM's situational ADVANTAGE/DISADVANTAGE (null/NORMAL when none), persisted and applied
     * authoritatively when the player rolls.
     */
    @Transactional
    public void createPendingCheck(UUID sessionId, Player player, String ability, int dc,
                                   String skill, String reason, RollMode dmMode,
                                   UUID turnEventId, UUID roundToken) {
        RollMode mode = dmMode == null ? RollMode.NORMAL : dmMode;
        PendingCheck pc = PendingCheck.builder()
                .sessionId(sessionId)
                .playerId(player.getId())
                .ability(ability)
                .dc(dc)
                .skill(skill)
                .reason(reason)
                .dmMode(mode)
                .turnEventId(turnEventId)
                .roundToken(roundToken)
                .build();
        pendingCheckRepository.save(pc);

        int suggested = computeModifier(player, ability, skill);
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                RollRequestEvent.of(sessionId, player.getId(), ability, dc, skill, reason, suggested, mode));
        log.info("Pending check created: session={}, player={}, {} DC{} dmMode={}",
                sessionId, player.getId(), ability, dc, mode);
    }

    /**
     * Create a GROUP check imposed on EVERY player in the session: one pending check per player,
     * all sharing a single group token (reusing {@code roundToken}) with {@code check_kind=GROUP},
     * the same ability/dc/skill. Each player is prompted with a ROLL_REQUEST carrying the GROUP
     * marker. Resolution applies the D&D rule that the party succeeds iff at least half the
     * participants succeed (see {@link #resolveGroupBatched}).
     */
    @Transactional
    public void createGroupCheck(UUID sessionId, String ability, int dc, String skill,
                                 String reason, UUID turnEventId) {
        List<Player> members = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .toList();
        if (members.isEmpty()) {
            log.info("GROUP check requested but session has no players: session={}", sessionId);
            return;
        }
        UUID groupToken = UUID.randomUUID();
        for (Player p : members) {
            PendingCheck pc = PendingCheck.builder()
                    .sessionId(sessionId)
                    .playerId(p.getId())
                    .ability(ability)
                    .dc(dc)
                    .skill(skill)
                    .reason(reason)
                    .dmMode(RollMode.NORMAL)
                    .turnEventId(turnEventId)
                    .roundToken(groupToken)
                    .checkKind(CheckKind.GROUP)
                    .build();
            pendingCheckRepository.save(pc);

            int suggested = computeModifier(p, ability, skill);
            messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                    RollRequestEvent.of(sessionId, p.getId(), ability, dc, skill, reason, suggested,
                            RollMode.NORMAL, "GROUP", null));
        }
        log.info("GROUP check created: session={}, token={}, {} DC{} participants={}",
                sessionId, groupToken, ability, dc, members.size());
    }

    /**
     * Create a CONTEST check for a single actor (a player): {@code check_kind=CONTEST}, persisting
     * the NPC side's flat {@code targetMod} and {@code targetLabel}. When the actor rolls, the
     * engine also rolls the NPC side ({@code 1d20 + targetMod}) and compares totals — see
     * {@link #resolveContest}. {@code targetMod} is resolved by the caller (tag value or a
     * difficulty-banded fallback) so it is always non-null here.
     */
    @Transactional
    public void createContestCheck(UUID sessionId, Player actor, String ability, String skill,
                                   int targetMod, String targetLabel, String reason,
                                   UUID turnEventId) {
        PendingCheck pc = PendingCheck.builder()
                .sessionId(sessionId)
                .playerId(actor.getId())
                .ability(ability)
                .dc(0) // unused for CONTEST — the opposed NPC roll decides win/loss
                .skill(skill)
                .reason(reason)
                .dmMode(RollMode.NORMAL)
                .turnEventId(turnEventId)
                .roundToken(null) // single-actor → single (claim-gated) resolve path
                .checkKind(CheckKind.CONTEST)
                .targetMod(targetMod)
                .targetLabel(targetLabel)
                .build();
        pendingCheckRepository.save(pc);

        int suggested = computeModifier(actor, ability, skill);
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                RollRequestEvent.of(sessionId, actor.getId(), ability, 0, skill, reason, suggested,
                        RollMode.NORMAL, "CONTEST", targetLabel));
        log.info("CONTEST check created: session={}, actor={}, {} vs {} (mod {})",
                sessionId, actor.getId(), ability, targetLabel, targetMod);
    }

    /* ── phase 2: resolve ────────────────────────────────────────── */

    /**
     * Roll the player's pending check, broadcast the dice, then stream a DM narration of the
     * outcome. Not wrapped in a single transaction — the multi-second LLM stream must not hold
     * a DB connection; the small mutations go through individually-transactional helpers.
     */
    public void resolveCheck(UUID sessionId, String username, boolean spendInspiration) {
        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + username));
        PendingCheck pc = pendingCheckRepository.findBySessionIdAndPlayerId(sessionId, player.getId())
                .orElseThrow(() -> new IllegalStateException("You have no pending check to roll"));

        // Capture the immutable check fields up-front; the row may be claimed (deleted) below.
        String ability = pc.getAbility();
        int dc = pc.getDc();
        String skill = pc.getSkill();
        RollMode dmMode = pc.getDmMode();
        String reason = pc.getReason();
        UUID roundToken = pc.getRoundToken();
        CheckKind kind = pc.getCheckKind() == null ? CheckKind.STANDARD : pc.getCheckKind();
        Integer targetMod = pc.getTargetMod();
        String targetLabel = pc.getTargetLabel();

        if (roundToken == null) {
            // Single-player path (STANDARD or CONTEST) CLAIMS-BEFORE-ROLL: atomically delete the
            // pending row first and only proceed if this submit won the claim. This closes the old
            // find→roll→delete window where two rapid submits could both roll and both stream a
            // narration. A lost race returns quietly and (because inspiration is consumed only after
            // the claim) never burns the player's inspiration.
            int claimed = pendingCheckRepository.deleteBySessionIdAndPlayerId(sessionId, player.getId());
            if (claimed == 0) {
                log.info("Pending check already claimed, ignoring duplicate roll: session={}, player={}",
                        sessionId, player.getId());
                return;
            }
            RollMode rollMode = applySpentInspiration(sessionId, player, spendInspiration, dmMode);
            DiceRollResult result = rollAndAnimate(sessionId, player, ability, skill, rollMode);
            if (kind == CheckKind.CONTEST) {
                resolveContest(sessionId, player, reason, ability, skill, result.total(),
                        targetMod == null ? 0 : targetMod, targetLabel);
            } else {
                boolean success = result.total() >= dc;
                String checkLine = checkLine(player, ability, skill, result.total(), dc, success);
                resolveSingle(sessionId, player, reason, ability, skill, dc, result.total(), success, checkLine);
                log.info("Check resolved: session={}, player={}, total={}, success={}",
                        sessionId, player.getId(), result.total(), success);
            }
        } else if (kind == CheckKind.GROUP) {
            // GROUP rolls INSIDE resolveGroupBatched, AFTER its claim-gate, so a sweep or dropout that
            // wins the claim first never burns the player's inspiration on a discarded roll (mirrors
            // the single-path claim-before-roll discipline).
            resolveGroupBatched(sessionId, player, roundToken, ability, skill, dc, dmMode, spendInspiration);
        } else {
            // Collaborative STANDARD batch stays serialized by resolveBatched's per-token monitor,
            // which deletes the pending row INSIDE the lock so the "am I the last roller?" decision
            // is atomic.
            RollMode rollMode = applySpentInspiration(sessionId, player, spendInspiration, dmMode);
            DiceRollResult result = rollAndAnimate(sessionId, player, ability, skill, rollMode);
            boolean success = result.total() >= dc;
            String checkLine = checkLine(player, ability, skill, result.total(), dc, success);
            resolveBatched(sessionId, player.getId(), roundToken, pc, checkLine);
            log.info("Check resolved (batched): session={}, player={}, total={}, success={}",
                    sessionId, player.getId(), result.total(), success);
        }
    }

    /**
     * Spend the player's Inspiration (only after the check is claimed, so a lost race never burns
     * it) and broadcast the cleared state. Returns the combined roll mode: the DM's situational mode
     * RAW-combined with ADVANTAGE when inspiration was actually consumed.
     */
    private RollMode applySpentInspiration(UUID sessionId, Player player, boolean spendInspiration,
                                           RollMode dmMode) {
        boolean used = false;
        if (spendInspiration) {
            used = playerStateService.consumeInspiration(player.getId());
            if (used) {
                messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                        PlayerStateEvent.of(sessionId, playerStateService.getState(player.getId())));
            }
        }
        return RollMode.combine(dmMode, used ? RollMode.ADVANTAGE : null);
    }

    /** Roll the check authoritatively with the combined mode and animate it (existing dice modal). */
    private DiceRollResult rollAndAnimate(UUID sessionId, Player player, String ability, String skill,
                                          RollMode rollMode) {
        int modifier = computeModifier(player, ability, skill);
        DiceRollResult result = diceService.roll(notation(modifier), rollMode);
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                DiceRollEvent.of(sessionId, player.getId(), player.getCharacterName(),
                        label(ability, skill), result));
        return result;
    }

    private String label(String ability, String skill) {
        String skillPart = (skill == null || skill.isBlank()) ? "" : " (" + skill + ")";
        return ability + skillPart + " check";
    }

    private String checkLine(Player player, String ability, String skill, int total, int dc,
                             boolean success) {
        return player.getCharacterName() + "'s " + label(ability, skill)
                + ": " + total + " vs DC " + dc + " — " + (success ? "SUCCESS" : "FAILURE");
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

    /* ── CONTEST: actor vs an engine-rolled NPC side ─────────────────────────────── */

    /**
     * Resolve a CONTEST: the actor's side has already been rolled (and animated). The engine now
     * rolls the NPC side ({@code 1d20 + targetMod}) and compares totals — higher wins, and ties
     * favour the defender (RAW), so the actor wins only on a strictly higher total. The NPC roll is
     * broadcast for parity (synthetic null playerId), then the outcome is narrated.
     */
    private void resolveContest(UUID sessionId, Player player, String reason, String ability,
                                String skill, int actorTotal, int targetMod, String targetLabel) {
        DiceRollResult npc = diceService.roll(notation(targetMod), RollMode.NORMAL);
        int targetTotal = npc.total();
        boolean actorWon = actorTotal > targetTotal; // ties favour the defender
        String label = targetLabel == null || targetLabel.isBlank() ? "the opposed party" : targetLabel;

        // Optional cosmetic broadcast of the NPC's opposing roll (frontend tolerance is a later phase).
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                DiceRollEvent.of(sessionId, null, label, "Contested roll", npc));

        String contestLine = player.getCharacterName() + "'s " + label(ability, skill) + " (" + actorTotal
                + ") vs " + label + " (" + targetTotal + ") — "
                + (actorWon ? player.getCharacterName() + " WINS" : label + " WINS");

        TurnEvent beat = turnService.createNarrativeBeat(sessionId, player.getId(), contestLine + ".");
        int turnNumber = beat.getTurnNumber();
        String destination = "/topic/game/" + sessionId;

        messagingTemplate.convertAndSend(destination, (Object) Map.of(
                "type", "DM_THINKING",
                "turnNumber", String.valueOf(turnNumber),
                "playerId", player.getId().toString(),
                "playerName", player.getCharacterName(),
                "action", contestLine + "."));

        String narration = dmAiService.generateContestResolution(
                sessionId, player.getCharacterName(), actorTotal, label, targetTotal, actorWon,
                chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                        "type", "DM_CHUNK",
                        "turnNumber", String.valueOf(turnNumber),
                        "playerId", player.getId().toString(),
                        "delta", chunk)));

        turnService.recordDmResponse(beat.getId(), sessionId, player.getId(), narration, turnNumber);
        log.info("CONTEST resolved: session={}, actor={}, {} vs {} — actorWon={}",
                sessionId, player.getId(), actorTotal, targetTotal, actorWon);
    }

    /* ── GROUP: half-the-party rule + abandonment timeout ────────────────────────── */

    /**
     * Claim, roll, accumulate one GROUP roll and decide — atomically — whether it is the last of its
     * token. Everything through the remaining-count runs inside the per-token monitor (mirroring
     * {@link #resolveBatched}) so two near-simultaneous rolls can't both fire the verdict. The
     * per-player delete count is the claim-gate against the {@link #sweepAbandonedGroupChecks() sweep}
     * and the {@link #handleGroupDropout dropout}: a zero count means the row was already removed, so
     * this late roll stays silent. Inspiration is spent and the die rolled only AFTER the claim wins,
     * so a lost claim never burns the player's inspiration. The slow LLM stream happens AFTER the lock.
     */
    private void resolveGroupBatched(UUID sessionId, Player player, UUID groupToken, String ability,
                                     String skill, int dc, RollMode dmMode, boolean spendInspiration) {
        String key = sessionId + ":" + groupToken;
        GroupBatch gb = groupBatches.computeIfAbsent(key, k -> new GroupBatch());

        List<String> snapshotLines;
        int snapshotSuccesses;
        UUID beatPlayerId;
        synchronized (gb) {
            if (gb.done) {
                return; // already force-resolved by the sweep or a dropout
            }
            int claimed = pendingCheckRepository.deleteBySessionIdAndPlayerId(sessionId, player.getId());
            if (claimed == 0) {
                // The sweep/dropout (or a duplicate submit) already removed this row — don't roll or
                // double-count. Only drop a genuinely empty, freshly-created orphan (post-resolution
                // late roller). A duplicate submit lands on the SHARED, non-empty batch (the first
                // submit added its line under this same lock) — removing it would erase prior results.
                if (gb.lines.isEmpty()) {
                    groupBatches.remove(key, gb);
                }
                return;
            }
            // Claim won — only NOW spend inspiration and roll, so a lost claim never burns it.
            RollMode rollMode = applySpentInspiration(sessionId, player, spendInspiration, dmMode);
            DiceRollResult result = rollAndAnimate(sessionId, player, ability, skill, rollMode);
            boolean success = result.total() >= dc;
            String checkLine = checkLine(player, ability, skill, result.total(), dc, success);
            gb.lines.add(checkLine);
            if (success) {
                gb.successes++;
            }
            if (gb.anyPlayerId == null) {
                gb.anyPlayerId = player.getId();
            }
            log.info("GROUP check rolled: session={}, player={}, total={}, success={}",
                    sessionId, player.getId(), result.total(), success);
            long remaining = pendingCheckRepository.countBySessionIdAndRoundToken(sessionId, groupToken);
            if (remaining > 0) {
                log.info("GROUP check pending: session={}, token={}, remaining={}",
                        sessionId, groupToken, remaining);
                return; // not the last roller — another thread (or the sweep/dropout) will narrate.
            }
            gb.done = true;
            groupBatches.remove(key, gb);
            snapshotLines = new ArrayList<>(gb.lines);
            snapshotSuccesses = gb.successes;
            beatPlayerId = gb.anyPlayerId;
        }
        narrateGroup(sessionId, beatPlayerId, snapshotLines, snapshotSuccesses, false);
    }

    /**
     * Force-resolve a GROUP check whose LAST outstanding row was just dropped because that player
     * submitted a normal action instead of rolling ({@link TurnService#submitAction} deletes the
     * pending check). Without this no roller is ever "last", so the group would never narrate and its
     * in-memory batch would leak. Mirrors the sweep's mutual-exclusion discipline: enter the per-token
     * monitor, guard on {@code done} (so the last-roller and sweep paths can't also narrate), mark
     * done, and remove from the map. The dropped player's row is already gone, so a competing last
     * roller's claim returns 0 and stays silent; whichever path reaches the monitor's terminal block
     * first wins. The narration is scheduled off this (transactional) caller's thread so the DB tx is
     * not held across the multi-second LLM stream. The all-skip case yields {@code total == 0} → FAIL.
     */
    public void handleGroupDropout(UUID sessionId, UUID groupToken, UUID actingPlayerId) {
        String key = sessionId + ":" + groupToken;
        GroupBatch gb = groupBatches.computeIfAbsent(key, k -> new GroupBatch());

        List<String> snapshotLines;
        int snapshotSuccesses;
        UUID beatPlayerId;
        synchronized (gb) {
            if (gb.done) {
                if (gb.lines.isEmpty()) {
                    groupBatches.remove(key, gb); // drop a freshly-created orphan
                }
                return; // a roller or the sweep already resolved this group
            }
            gb.done = true;
            groupBatches.remove(key, gb);
            snapshotLines = new ArrayList<>(gb.lines);
            snapshotSuccesses = gb.successes;
            beatPlayerId = gb.anyPlayerId != null ? gb.anyPlayerId : actingPlayerId;
        }
        final List<String> lines = snapshotLines;
        final int successes = snapshotSuccesses;
        final UUID bpid = beatPlayerId;
        taskScheduler.schedule(() -> {
            try {
                narrateGroup(sessionId, bpid, lines, successes, true);
            } catch (Exception e) {
                log.error("Failed to narrate GROUP dropout: session={}, token={}", sessionId, groupToken, e);
            }
        }, Instant.now());
        log.info("GROUP check dropout claimed: session={}, token={}, responders={}",
                sessionId, groupToken, snapshotLines.size());
    }

    /**
     * Narrate a resolved GROUP check. The total is the number of participants who actually rolled
     * (non-responders excluded — the simpler, documented rule for the abandonment path; on the
     * normal path everyone has rolled so total == party size). The party succeeds iff at least half
     * succeed AND at least one rolled ({@code successes * 2 >= total && total > 0}).
     */
    private void narrateGroup(UUID sessionId, UUID beatPlayerId, List<String> lines,
                              int successes, boolean timedOut) {
        int total = lines.size();
        boolean groupSucceeded = total > 0 && successes * 2 >= total;
        String summary = String.join("\n", lines);

        String header = timedOut
                ? "The party's group check resolves (some members did not act in time):\n"
                : "The party's group check resolves:\n";
        String action = header + summary + "\n"
                + successes + " of " + total + " succeeded — the group "
                + (groupSucceeded ? "SUCCEEDS" : "FAILS") + ".";

        TurnEvent beat = turnService.createNarrativeBeat(sessionId, beatPlayerId, action);
        int turnNumber = beat.getTurnNumber();
        String destination = "/topic/game/" + sessionId;
        String beatPlayerStr = beatPlayerId == null ? "" : beatPlayerId.toString();

        messagingTemplate.convertAndSend(destination, (Object) Map.of(
                "type", "DM_THINKING",
                "turnNumber", String.valueOf(turnNumber),
                "playerId", beatPlayerStr,
                "playerName", "The Party",
                "action", action));

        String narration = dmAiService.generateGroupCheckResolution(
                sessionId, summary, successes, total, groupSucceeded,
                chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                        "type", "DM_CHUNK",
                        "turnNumber", String.valueOf(turnNumber),
                        "playerId", beatPlayerStr,
                        "delta", chunk)));

        turnService.recordDmResponse(beat.getId(), sessionId, beatPlayerId, narration, turnNumber);
        log.info("GROUP check narrated: session={}, successes={}/{}, succeeded={}, timedOut={}",
                sessionId, successes, total, groupSucceeded, timedOut);
    }

    /**
     * Force-resolve GROUP checks that have stalled past the timeout (a participant disconnected, so
     * the beat would otherwise never complete). Runs every ~15s. For each abandoned group token, the
     * bulk delete is the claim-gate: only a non-zero count narrates, mirroring the per-player gate in
     * {@link #resolveGroupBatched}. {@code deleted > 0} with no accumulated rolls is the legitimate
     * "nobody rolled" timeout (fails, since a group needs total &gt; 0); {@code deleted == 0} means
     * the last roller already resolved it, so the sweep stays silent — no double or empty narration.
     * Not {@code @Transactional}: the LLM stream must not hold a DB connection (the {@code @Modifying}
     * delete is its own transaction).
     */
    @Scheduled(fixedDelayString = "${dnd.group-check.sweep-millis:15000}")
    public void sweepAbandonedGroupChecks() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(groupTimeoutSeconds);
        List<PendingCheck> stale = pendingCheckRepository.findByCheckKindAndCreatedAtBefore(
                CheckKind.GROUP, cutoff);
        if (stale.isEmpty()) {
            return;
        }
        // Group the stale rows by their (session, token); preserve a representative player id.
        Map<String, UUID[]> tokens = new LinkedHashMap<>();
        for (PendingCheck pc : stale) {
            String key = pc.getSessionId() + ":" + pc.getRoundToken();
            tokens.putIfAbsent(key, new UUID[]{pc.getSessionId(), pc.getRoundToken(), pc.getPlayerId()});
        }

        for (UUID[] t : tokens.values()) {
            UUID sessionId = t[0];
            UUID groupToken = t[1];
            UUID fallbackPlayerId = t[2];
            String key = sessionId + ":" + groupToken;
            GroupBatch gb = groupBatches.computeIfAbsent(key, k -> new GroupBatch());

            List<String> snapshotLines;
            int snapshotSuccesses;
            UUID beatPlayerId;
            synchronized (gb) {
                if (gb.done) {
                    continue;
                }
                int deleted = pendingCheckRepository.deleteBySessionIdAndRoundToken(sessionId, groupToken);
                gb.done = true;
                groupBatches.remove(key, gb);
                if (deleted == 0) {
                    // The last roller already claimed and narrated this group — stay silent.
                    continue;
                }
                snapshotLines = new ArrayList<>(gb.lines);
                snapshotSuccesses = gb.successes;
                beatPlayerId = gb.anyPlayerId != null ? gb.anyPlayerId : fallbackPlayerId;
            }
            log.info("GROUP check abandonment sweep firing: session={}, token={}, responders={}",
                    sessionId, groupToken, snapshotLines.size());
            try {
                narrateGroup(sessionId, beatPlayerId, snapshotLines, snapshotSuccesses, true);
            } catch (Exception e) {
                log.error("Failed to force-resolve abandoned GROUP check: session={}, token={}",
                        sessionId, groupToken, e);
            }
        }
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
