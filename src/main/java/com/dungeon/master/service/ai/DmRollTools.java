package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.DiceRollEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.RerollPromptEvent;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.RerollResource;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CheckModifierService;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.ExhaustionRules;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.RerollWindow;
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
    public static final String K_DEFAULT_DC = "defaultDc";           // Integer
    public static final String K_DEFAULT_CONTEST_MOD = "defaultContestMod"; // Integer

    /** Seconds a player has to answer a reroll prompt before it auto-keeps the original roll. */
    private static final int REROLL_WINDOW_SECONDS = 12;

    private final DiceService diceService;
    private final CheckModifierService modifiers;
    private final PlayerStateService playerStateService;
    private final PlayerRepository playerRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RerollWindow rerollWindow;

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
        RollMode finalMode = withExhaustion(player, parseMode(mode));
        int modifier = modifiers.computeModifier(player, ability, skill);
        String notation = modifiers.notation(modifier);
        String label = modifiers.label(ability, skill);
        DiceRollResult r = diceService.roll(notation, finalMode);
        broadcastRoll(sessionId, player.getId(), player.getCharacterName(), label, r);
        r = offerReroll(sessionId, player, label, notation, useDc, r);
        boolean success = r.total() >= useDc;
        log.info("Tool rollCheck: session={}, char={}, {} total={} vs DC{} -> {}",
                sessionId, player.getCharacterName(), ability, r.total(), useDc, success ? "SUCCESS" : "FAILURE");
        return new CheckResult(player.getCharacterName(), ability, skill, useDc, r.total(),
                modifier, finalMode.name(), success, r.crit(), r.fumble(), null);
    }

    @Tool(description = "Roll ONE character's SAVING THROW when they must resist an effect out of combat "
            + "(a trap's poison, a charm, a blast of fire, a wave of fear). The engine rolls 1d20 plus "
            + "the character's save modifier (ability modifier, plus proficiency when their class is "
            + "proficient in that save) authoritatively and returns the result vs the DC. Do not narrate "
            + "before calling it.")
    public CheckResult rollSave(
            @ToolParam(description = "Exact character name of the saving player, verbatim from the prompt") String playerName,
            @ToolParam(description = "Save ability: one of STR, DEX, CON, INT, WIS, CHA") String ability,
            @ToolParam(description = "Difficulty Class, typically 8-20; pass 0 to use the session default") int dc,
            @ToolParam(description = "Situational roll mode: NORMAL, ADVANTAGE, or DISADVANTAGE") String mode,
            ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        UUID sessionId = (UUID) ctx.get(K_SESSION);
        Player player = resolvePlayer(ctx, playerName);
        if (player == null) {
            log.warn("rollSave: unresolved character '{}' (session={})", playerName, sessionId);
            return new CheckResult(playerName, ability, null, dc, 0, 0,
                    RollMode.NORMAL.name(), false, false, false, "no such character: " + playerName);
        }
        int useDc = dc > 0 ? dc : intCtx(ctx, K_DEFAULT_DC, 13);
        RollMode finalMode = withExhaustion(player, parseMode(mode));
        int modifier = modifiers.computeSaveModifier(player, ability);
        String notation = modifiers.notation(modifier);
        String label = modifiers.saveLabel(ability);
        DiceRollResult r = diceService.roll(notation, finalMode);
        broadcastRoll(sessionId, player.getId(), player.getCharacterName(), label, r);
        r = offerReroll(sessionId, player, label, notation, useDc, r);
        boolean success = r.total() >= useDc;
        log.info("Tool rollSave: session={}, char={}, {} save total={} vs DC{} -> {}",
                sessionId, player.getCharacterName(), ability, r.total(), useDc, success ? "SUCCESS" : "FAILURE");
        return new CheckResult(player.getCharacterName(), ability, null, useDc, r.total(),
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
            RollMode finalMode = withExhaustion(p, RollMode.NORMAL);
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

        RollMode actorMode = withExhaustion(actor, RollMode.NORMAL);
        int actorModifier = modifiers.computeModifier(actor, actorAbility, actorSkill);
        String actorNotation = modifiers.notation(actorModifier);
        String actorLabel = modifiers.label(actorAbility, actorSkill);
        DiceRollResult actorRoll = diceService.roll(actorNotation, actorMode);
        broadcastRoll(sessionId, actor.getId(), actor.getCharacterName(), actorLabel, actorRoll);

        DiceRollResult npcRoll = diceService.roll(modifiers.notation(npcMod), RollMode.NORMAL);
        broadcastRoll(sessionId, null, label, "Contested roll", npcRoll);

        // The actor wins only on a strictly higher total, so they "succeed" at npcTotal + 1.
        actorRoll = offerReroll(sessionId, actor, actorLabel, actorNotation, npcRoll.total() + 1, actorRoll);
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
     * When a d20 roll <em>fails</em> and the player holds a reroll resource (2024 Heroic Inspiration
     * and/or Lucky points), offer an interactive reroll and resolve it. The DM's roll tool blocks on
     * the {@link RerollWindow} until the player answers or the window elapses (auto-keep). Inspiration
     * must use the new roll; Lucky keeps the better of the two. Returns the roll to score against the
     * DC — the original when the roll already succeeded, no resource is held, or the player declines.
     */
    private DiceRollResult offerReroll(UUID sessionId, Player player, String label,
                                       String notation, int dc, DiceRollResult original) {
        if (original.total() >= dc) {
            return original; // already a success — nothing to improve
        }
        PlayerRuntimeStateDto state;
        try {
            state = playerStateService.getState(player.getId());
        } catch (RuntimeException e) {
            return original; // no runtime state to spend from
        }
        List<String> options = new ArrayList<>();
        if (state.inspiration()) {
            options.add(RerollResource.INSPIRATION.name());
        }
        if (state.luckPoints() > 0) {
            options.add(RerollResource.LUCK.name());
        }
        if (options.isEmpty()) {
            return original;
        }
        UUID promptId = UUID.randomUUID();
        RerollResource choice = rerollWindow.offer(sessionId, player.getUsername(),
                RerollPromptEvent.of(sessionId, promptId, label, original.total(), dc, options,
                        state.luckPoints(), REROLL_WINDOW_SECONDS));
        boolean spent = switch (choice) {
            case INSPIRATION -> playerStateService.consumeInspiration(player.getId());
            case LUCK -> playerStateService.consumeLuckPoint(player.getId());
            case KEEP -> false;
        };
        if (!spent) {
            return original;
        }
        // Broadcast the spent resource so the client's Inspiration / Luck badges update.
        messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                PlayerStateEvent.of(sessionId, playerStateService.getState(player.getId())));
        DiceRollResult reroll = diceService.roll(notation);
        broadcastRoll(sessionId, player.getId(), player.getCharacterName(), label + " (reroll)", reroll);
        // Lucky keeps whichever die is better; Heroic Inspiration must take the new roll.
        if (choice == RerollResource.LUCK) {
            return reroll.total() > original.total() ? reroll : original;
        }
        return reroll;
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
