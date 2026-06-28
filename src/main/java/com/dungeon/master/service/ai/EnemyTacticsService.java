package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.service.game.GridService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Decides ONE enemy's high-level combat intent (and which hero it targets) for a turn. The LLM
 * only <em>decides</em> — {@code CombatService} still pathfinds, moves, and rolls. When the
 * session permits AI combat the model is handed a compact battlefield snapshot and returns a
 * {@link EnemyTactic}; on ANY problem (disabled flag, parse error, an invalid target name, or a
 * thrown exception) it degrades to a deterministic default mirroring the engine's classic AI:
 * target the lowest-HP conscious hero, engage in melee (or kite if the monster is purely ranged).
 * Never throws.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnemyTacticsService {

    /** The high-level move an enemy commits to this turn. */
    public enum EnemyIntent {
        /** Close to melee reach and attack. */
        ENGAGE_MELEE,
        /** Stay at range and shoot, retreating to keep distance. */
        KITE_RANGED,
        /** Single out a caster/support hero and close on them. */
        FOCUS_CASTER,
        /** Retreat away from all heroes. */
        FLEE,
        /** Stay put; attack only who is already in range. */
        HOLD
    }

    /** The chosen target's display name and the intent the enemy commits to. */
    public record EnemyTactic(String targetName, EnemyIntent intent) {}

    /** A conscious hero the enemy could act on: name, runtime HP band, and grid position. */
    public record TargetInfo(String name, java.util.UUID playerId,
                             int currentHp, int maxHp, int x, int y) {}

    private static final String SYSTEM = """
            You are the tactical brain of a single D&D monster in turn-based combat. Pick ONE \
            target and ONE intent. Return ONLY JSON {"targetName": "<exact hero name>", \
            "intent": "<INTENT>"}. Intents: ENGAGE_MELEE (close and hit in melee), KITE_RANGED \
            (stay at range and shoot, keep distance), FOCUS_CASTER (single out a caster/support), \
            FLEE (retreat when badly hurt or outmatched), HOLD (stay put, attack who is in reach). \
            Choose targetName EXACTLY from the provided list.""";

    private final ChatModel chatModel;
    private final GridService gridService;
    private final SrdContent srdContent;

    /**
     * Decide this enemy's target + intent. {@code targets} are the conscious heroes (with grid
     * positions). Always returns a usable {@link EnemyTactic}; falls back to the deterministic
     * default on any failure.
     */
    public EnemyTactic decide(GameSession session, CombatEncounter enc, Enemy enemy,
                              List<TargetInfo> targets) {
        if (targets == null || targets.isEmpty()) {
            return new EnemyTactic(null, EnemyIntent.HOLD);
        }
        EnemyTactic deterministic = deterministic(enemy, targets);
        if (session == null || !session.isAllowAiCombat()) {
            return deterministic;
        }
        try {
            EnemyTactic tactic = requestTactic(enemy, enc, targets);
            if (tactic == null || tactic.intent() == null) {
                return deterministic;
            }
            String validated = resolveName(tactic.targetName(), targets);
            if (validated == null) {
                return deterministic; // hallucinated/blank target → safe default
            }
            return new EnemyTactic(validated, tactic.intent());
        } catch (Exception e) {
            log.warn("Enemy tactic decision failed for enemy={}, using deterministic: {}",
                    enemy.getName(), e.getMessage());
            return deterministic;
        }
    }

    /** Lowest-HP conscious hero; melee by default, kite if the monster has only ranged attacks. */
    private EnemyTactic deterministic(Enemy enemy, List<TargetInfo> targets) {
        TargetInfo lowest = targets.stream()
                .min(Comparator.comparingInt(TargetInfo::currentHp))
                .orElse(targets.get(0));
        EnemyIntent intent = onlyRanged(enemy) ? EnemyIntent.KITE_RANGED : EnemyIntent.ENGAGE_MELEE;
        return new EnemyTactic(lowest.name(), intent);
    }

    private boolean onlyRanged(Enemy enemy) {
        List<MonsterAttack> atks = enemy.getAttacks();
        if (atks == null || atks.isEmpty()) {
            return false; // legacy single-attack enemies are treated as melee
        }
        return atks.stream().allMatch(a -> "RANGED".equalsIgnoreCase(a.kind()));
    }

    private EnemyTactic requestTactic(Enemy enemy, CombatEncounter enc, List<TargetInfo> targets) {
        Token et = enc.getGridState() != null && enc.getGridState().getTokens() != null
                ? enc.getGridState().getTokens().get(enemy.getId().toString())
                : null;

        StringBuilder b = new StringBuilder();
        b.append("You control: ").append(enemy.getName())
                .append(" (HP ").append(enemy.getCurrentHp()).append("/").append(enemy.getMaxHp())
                .append(", speed ").append(enemy.getSpeed()).append(" ft).\n");
        b.append("Its attacks: ").append(describeAttacks(enemy)).append(".\n");
        srdContent.monsterEntryByName(enemy.getName()).ifPresent(entry ->
                b.append("Lore: ").append(trim(entry.content(), 280)).append("\n"));
        b.append("Conscious heroes (name — HP band — distance):\n");
        for (TargetInfo t : targets) {
            b.append("- ").append(t.name()).append(" — ").append(hpBand(t.currentHp(), t.maxHp()));
            if (et != null && t.x() >= 0 && t.y() >= 0) {
                int dist = gridService.distanceFeet(et.getX(), et.getY(), t.x(), t.y());
                b.append(" — ").append(dist).append(" ft away");
            }
            b.append("\n");
        }
        b.append("Pick ONE target (exact name above) and ONE intent.");

        return ChatClient.create(chatModel).prompt()
                .system(SYSTEM)
                .user(b.toString())
                .call()
                .entity(EnemyTactic.class);
    }

    private static String describeAttacks(Enemy enemy) {
        List<MonsterAttack> atks = enemy.getAttacks();
        if (atks == null || atks.isEmpty()) {
            return "a melee weapon (reach 5 ft)";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < atks.size(); i++) {
            MonsterAttack a = atks.get(i);
            if (i > 0) {
                b.append("; ");
            }
            b.append(a.name()).append(" (").append(a.kind() == null ? "MELEE" : a.kind());
            if ("RANGED".equalsIgnoreCase(a.kind()) && a.range() != null) {
                b.append(", range ").append(a.range()).append(" ft");
            } else if (a.reach() != null) {
                b.append(", reach ").append(a.reach()).append(" ft");
            }
            b.append(")");
        }
        return b.toString();
    }

    /** Match a model-named target against the conscious heroes (exact or first-token), else null. */
    private String resolveName(String name, List<TargetInfo> targets) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (TargetInfo t : targets) {
            String tn = t.name() == null ? "" : t.name().toLowerCase(Locale.ROOT);
            if (tn.equals(n) || tn.startsWith(n + " ") || n.startsWith(tn + " ")) {
                return t.name();
            }
        }
        return null;
    }

    private static String hpBand(int currentHp, int maxHp) {
        if (currentHp <= 0) {
            return "down";
        }
        if (maxHp <= 0) {
            return "healthy";
        }
        double pct = (double) currentHp / maxHp;
        if (pct <= 0.25) {
            return "critical";
        }
        if (pct <= 0.50) {
            return "bloodied";
        }
        return "healthy";
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
