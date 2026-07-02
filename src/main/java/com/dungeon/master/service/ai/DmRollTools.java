package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.DiceRollEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CheckModifierService;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.ExhaustionRules;
import com.dungeon.master.service.game.PlayerStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The engine-owned dice tools the AI DM calls during a single narrative turn. Each tool performs the
 * AUTHORITATIVE roll server-side (the model never invents numbers), broadcasts the dice animation,
 * and returns a structured verdict the model then narrates. This replaces the old two-call flow
 * (pre-roll directive → player rolls → post-roll narration) with one DM turn.
 *
 * <p>Per-turn context (session id, character-name→id map, per-player Inspiration intent, default DC)
 * is passed via Spring AI's {@link ToolContext} from {@code DmAiService}, not by the model.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmRollTools {

    public static final String K_SESSION = "sessionId";              // UUID
    public static final String K_NAME_TO_PLAYER = "nameToPlayer";    // Map<String,UUID> (lowercased)
    public static final String K_SPEND_INSP = "spendInspiration";    // Map<UUID,Boolean>
    public static final String K_DEFAULT_DC = "defaultDc";           // Integer
    public static final String K_DEFAULT_CONTEST_MOD = "defaultContestMod"; // Integer

    private final DiceService diceService;
    private final CheckModifierService modifiers;
    private final PlayerStateService playerStateService;
    private final PlayerRepository playerRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** One character's check result. {@code error} is non-null only when the character couldn't be resolved. */
    public record CheckResult(String character, String ability, String skill, int dc, int total,
                              int modifier, String mode, boolean success, boolean crit, boolean fumble,
                              String error) {}

    public record GroupResult(List<CheckResult> rolls, int successes, int total, boolean groupSucceeded) {}

    public record ContestResult(String actor, int actorTotal, String target, int targetTotal,
                                boolean actorWon, String error) {}

    @Tool(description = "Roll ONE character's ability or skill check when the outcome of their action "
            + "is genuinely uncertain. The engine rolls 1d20 plus the character's modifier "
            + "authoritatively and returns the result (total vs DC, success/failure). Call this once "
            + "per acting character who needs a check. Do not narrate before calling it.")
    public CheckResult rollCheck(
            @ToolParam(description = "Exact character name of the acting player, verbatim from the prompt") String playerName,
            @ToolParam(description = "Ability: one of STR, DEX, CON, INT, WIS, CHA") String ability,
            @ToolParam(description = "Skill name (e.g. Acrobatics, Stealth, Perception); empty if a raw ability check") String skill,
            @ToolParam(description = "Difficulty Class, typically 8-20; pass 0 to use the session default") int dc,
            @ToolParam(description = "Situational roll mode: NORMAL, ADVANTAGE, or DISADVANTAGE") String mode,
            ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        UUID sessionId = (UUID) ctx.get(K_SESSION);
        Player player = resolvePlayer(ctx, playerName);
        if (player == null) {
            log.warn("rollCheck: unresolved character '{}' (session={})", playerName, sessionId);
            return new CheckResult(playerName, ability, skill, dc, 0, 0,
                    RollMode.NORMAL.name(), false, false, false, "no such character: " + playerName);
        }
        int useDc = dc > 0 ? dc : intCtx(ctx, K_DEFAULT_DC, 13);
        RollMode finalMode = withExhaustion(player, withInspiration(sessionId, player, parseMode(mode), ctx));
        int modifier = modifiers.computeModifier(player, ability, skill);
        DiceRollResult r = diceService.roll(modifiers.notation(modifier), finalMode);
        broadcastRoll(sessionId, player.getId(), player.getCharacterName(),
                modifiers.label(ability, skill), r);
        boolean success = r.total() >= useDc;
        log.info("Tool rollCheck: session={}, char={}, {} total={} vs DC{} -> {}",
                sessionId, player.getCharacterName(), ability, r.total(), useDc, success ? "SUCCESS" : "FAILURE");
        return new CheckResult(player.getCharacterName(), ability, skill, useDc, r.total(),
                modifier, finalMode.name(), success, r.crit(), r.fumble(), null);
    }

    @Tool(description = "Roll a GROUP check: the SAME uncertain task faces the whole party at once "
            + "(everyone sneaks past, all swim the rapids). Every player rolls; the party succeeds "
            + "only if at least half its members succeed. Returns each roll plus the group verdict.")
    public GroupResult groupCheck(
            @ToolParam(description = "Ability: one of STR, DEX, CON, INT, WIS, CHA") String ability,
            @ToolParam(description = "Skill name (e.g. Stealth, Athletics); empty if a raw ability check") String skill,
            @ToolParam(description = "Difficulty Class, typically 8-20; pass 0 to use the session default") int dc,
            ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        UUID sessionId = (UUID) ctx.get(K_SESSION);
        int useDc = dc > 0 ? dc : intCtx(ctx, K_DEFAULT_DC, 13);
        List<CheckResult> rolls = new ArrayList<>();
        int successes = 0;
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER) {
                continue;
            }
            RollMode finalMode = withExhaustion(p, withInspiration(sessionId, p, RollMode.NORMAL, ctx));
            int modifier = modifiers.computeModifier(p, ability, skill);
            DiceRollResult r = diceService.roll(modifiers.notation(modifier), finalMode);
            broadcastRoll(sessionId, p.getId(), p.getCharacterName(), modifiers.label(ability, skill), r);
            boolean success = r.total() >= useDc;
            if (success) {
                successes++;
            }
            rolls.add(new CheckResult(p.getCharacterName(), ability, skill, useDc, r.total(),
                    modifier, finalMode.name(), success, r.crit(), r.fumble(), null));
        }
        int total = rolls.size();
        boolean groupSucceeded = total > 0 && successes * 2 >= total;
        log.info("Tool groupCheck: session={}, {}/{} succeeded -> group {}",
                sessionId, successes, total, groupSucceeded ? "SUCCEEDS" : "FAILS");
        return new GroupResult(rolls, successes, total, groupSucceeded);
    }

    @Tool(description = "Resolve a CONTEST: ONE character directly opposed by an NPC (an arm-wrestle, "
            + "stealth vs perception, a shove). The engine rolls BOTH sides and returns the winner. "
            + "Ties favour the defender (the NPC), so the actor wins only on a strictly higher total.")
    public ContestResult contest(
            @ToolParam(description = "Exact character name of the acting player") String actorName,
            @ToolParam(description = "Actor's ability: STR, DEX, CON, INT, WIS, CHA") String actorAbility,
            @ToolParam(description = "Actor's skill (e.g. Athletics, Stealth); empty if a raw ability check") String actorSkill,
            @ToolParam(description = "Flat modifier for the NPC side; pass 0 to use the difficulty-banded default") int targetMod,
            @ToolParam(description = "In-fiction label of the opposed party, e.g. 'the guard'") String targetLabel,
            ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        UUID sessionId = (UUID) ctx.get(K_SESSION);
        Player actor = resolvePlayer(ctx, actorName);
        if (actor == null) {
            log.warn("contest: unresolved actor '{}' (session={})", actorName, sessionId);
            return new ContestResult(actorName, 0, targetLabel, 0, false, "no such character: " + actorName);
        }
        String label = (targetLabel == null || targetLabel.isBlank()) ? "the opposed party" : targetLabel;
        int npcMod = targetMod != 0 ? targetMod : intCtx(ctx, K_DEFAULT_CONTEST_MOD, 4);

        RollMode actorMode = withExhaustion(actor, withInspiration(sessionId, actor, RollMode.NORMAL, ctx));
        int actorModifier = modifiers.computeModifier(actor, actorAbility, actorSkill);
        DiceRollResult actorRoll = diceService.roll(modifiers.notation(actorModifier), actorMode);
        broadcastRoll(sessionId, actor.getId(), actor.getCharacterName(),
                modifiers.label(actorAbility, actorSkill), actorRoll);

        DiceRollResult npcRoll = diceService.roll(modifiers.notation(npcMod), RollMode.NORMAL);
        broadcastRoll(sessionId, null, label, "Contested roll", npcRoll);

        boolean actorWon = actorRoll.total() > npcRoll.total(); // ties favour the defender
        log.info("Tool contest: session={}, {} {} vs {} {} -> {}",
                sessionId, actor.getCharacterName(), actorRoll.total(), label, npcRoll.total(),
                actorWon ? "actor WINS" : "NPC WINS");
        return new ContestResult(actor.getCharacterName(), actorRoll.total(), label, npcRoll.total(),
                actorWon, null);
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private Player resolvePlayer(Map<String, Object> ctx, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, UUID> nameToPlayer = (Map<String, UUID>) ctx.get(K_NAME_TO_PLAYER);
        if (nameToPlayer == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        UUID id = nameToPlayer.get(key);
        if (id == null) {
            // Fall back to the first whitespace token (e.g. "Aria" ← "Aria Brightblade").
            int sp = key.indexOf(' ');
            if (sp > 0) {
                id = nameToPlayer.get(key.substring(0, sp));
            }
        }
        return id == null ? null : playerRepository.findById(id).orElse(null);
    }

    /**
     * Combine the DM's situational mode with ADVANTAGE when the player chose to spend Inspiration
     * this turn (and actually holds it). Consuming Inspiration broadcasts the cleared state, mirroring
     * the old CheckService behaviour.
     */
    @SuppressWarnings("unchecked")
    private RollMode withInspiration(UUID sessionId, Player player, RollMode dmMode, Map<String, Object> ctx) {
        Map<UUID, Boolean> spend = (Map<UUID, Boolean>) ctx.get(K_SPEND_INSP);
        boolean wants = spend != null && Boolean.TRUE.equals(spend.get(player.getId()));
        boolean used = false;
        if (wants) {
            used = playerStateService.consumeInspiration(player.getId());
            if (used) {
                messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                        PlayerStateEvent.of(sessionId, playerStateService.getState(player.getId())));
            }
        }
        return RollMode.combine(dmMode, used ? RollMode.ADVANTAGE : null);
    }

    /** Fold in disadvantage on ability checks when the character has exhaustion (level 1+). */
    private RollMode withExhaustion(Player player, RollMode mode) {
        int level;
        try {
            level = playerStateService.getState(player.getId()).exhaustionLevel();
        } catch (RuntimeException e) {
            return mode; // no runtime state — no exhaustion to apply
        }
        return RollMode.combine(mode, ExhaustionRules.checkMode(level));
    }

    private void broadcastRoll(UUID sessionId, UUID playerId, String name, String label, DiceRollResult r) {
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                DiceRollEvent.of(sessionId, playerId, name, label, r));
    }

    private static RollMode parseMode(String mode) {
        if (mode == null) {
            return RollMode.NORMAL;
        }
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "ADVANTAGE" -> RollMode.ADVANTAGE;
            case "DISADVANTAGE" -> RollMode.DISADVANTAGE;
            default -> RollMode.NORMAL;
        };
    }

    private static int intCtx(Map<String, Object> ctx, String key, int fallback) {
        Object v = ctx.get(key);
        return v instanceof Integer i ? i : fallback;
    }
}
