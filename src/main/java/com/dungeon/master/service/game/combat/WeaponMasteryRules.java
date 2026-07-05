package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.WeaponMastery;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.service.game.ConditionRules;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.GridService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 2024 PHB weapon-mastery resolver — the martial equivalent of the spell resolver's "combat buttons".
 * When a mastery-eligible attack lands (or, for Graze, misses), the wielded weapon's mastery applies
 * one effect built on an existing engine primitive: prone ({@link ConditionRules}), forced movement
 * ({@link GridService}), advantage/disadvantage (synthetic {@code vexed}/{@code sapped} conditions),
 * or bonus damage. Gated to martial classes.
 *
 * <p>Documented simplifications (engine, not RAW): Slow reuses the halve-speed {@code slowed}
 * condition rather than a −10 ft penalty; Cleave auto-carries to the nearest other adjacent foe (no
 * target picker); Nick adds off-hand-die damage rather than resolving a separate attack roll. The DC
 * for Topple is the 2024 weapon-mastery DC (8 + proficiency bonus + the attack's ability modifier).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeaponMasteryRules {

    private final DiceService diceService;
    private final EnemyRepository enemyRepository;
    private final CombatEncounterRepository encounterRepository;
    private final GridService gridService;

    /** Classes that gain Weapon Mastery in the 2024 rules. */
    private static final Set<String> MARTIAL_CLASSES =
            Set.of("barbarian", "fighter", "monk", "paladin", "ranger", "rogue");

    /** Whether this character's class gets weapon-mastery effects at all. */
    public boolean isMartial(Character c) {
        return c != null && c.getCharacterClass() != null
                && MARTIAL_CLASSES.contains(c.getCharacterClass().toLowerCase(Locale.ROOT));
    }

    /** The equipped weapon's mastery, or {@code null} when the wielder isn't martial or has none. */
    public WeaponMastery masteryFor(Character attacker, List<InventoryItem> inv) {
        if (!isMartial(attacker)) {
            return null;
        }
        return WeaponMastery.fromSrd(CombatMath.masteryFor(inv));
    }

    /**
     * Apply the on-HIT mastery effect of the attacker's equipped weapon after the main-hand damage has
     * landed. Mutates and persists the target (and, for Push, the grid; for Cleave, a second foe).
     * Returns narration beat line(s) — empty when there's no on-hit mastery to apply.
     */
    public List<String> applyOnHit(CombatEncounter enc, Player attacker, Character attackerChar,
                                   Enemy target, List<InventoryItem> inv, int damageDealt) {
        WeaponMastery mastery = masteryFor(attackerChar, inv);
        if (mastery == null || !target.isAlive()) {
            return List.of();
        }
        return switch (mastery) {
            case TOPPLE -> topple(enc, attackerChar, target);
            case VEX -> mark(enc, target, ConditionRules.VEXED,
                    target.getName() + " is left reeling — your next attack against it has advantage.");
            case SAP -> mark(enc, target, ConditionRules.SAPPED,
                    target.getName() + " is sapped — its next attack roll has disadvantage.");
            case SLOW -> mark(enc, target, ConditionRules.SLOWED,
                    target.getName() + "'s speed is reduced until the start of your next turn.");
            case PUSH -> push(enc, attacker, target);
            case CLEAVE -> cleave(enc, attackerChar, target, damageDealt);
            case NICK -> nick(attackerChar, target, inv);
            case GRAZE -> List.of(); // resolves on a miss, not a hit
        };
    }

    /**
     * Apply Graze on a MISS: the weapon still deals its ability-modifier in damage. Returns beat
     * line(s) — empty when the weapon's mastery isn't Graze (or the attacker isn't martial).
     */
    public List<String> applyOnMiss(Player attacker, Character attackerChar, Enemy target,
                                    List<InventoryItem> inv) {
        if (masteryFor(attackerChar, inv) != WeaponMastery.GRAZE || !target.isAlive()) {
            return List.of();
        }
        int dmg = Math.max(0, attackAbilityMod(attackerChar));
        if (dmg <= 0) {
            return List.of();
        }
        applyDamage(target, dmg);
        return List.of(target.getName() + " is grazed for " + dmg
                + (target.isAlive() ? " damage." : " damage and falls."));
    }

    /* ── individual masteries ────────────────────────────────────────── */

    private List<String> topple(CombatEncounter enc, Character attacker, Enemy target) {
        if (ConditionRules.has(target.getConditions(), ConditionRules.PRONE)) {
            return List.of();
        }
        int dc = 8 + CombatMath.attackBonus(attacker);
        boolean autoFail = ConditionRules.autoFailsSave(target.getConditions(), "CON");
        int saveMod = CombatMath.enemySaveMod(target, "CON")
                + ConditionRules.saveModifier(target.getConditions());
        DiceRollResult save = diceService.roll(CombatMath.notation(saveMod),
                ConditionRules.saveMode(target.getConditions(), "CON"));
        boolean saved = !autoFail && save.total() >= dc;
        if (saved) {
            return List.of(target.getName() + " stays on its feet (CON save "
                    + save.total() + " vs DC " + dc + ").");
        }
        target.getConditions().removeIf(c -> c.name() != null
                && c.name().equalsIgnoreCase(ConditionRules.PRONE));
        target.getConditions().add(ActiveCondition.of(ConditionRules.PRONE));
        enemyRepository.save(target);
        return List.of(target.getName() + " is knocked prone (CON save "
                + save.total() + " vs DC " + dc + ").");
    }

    /** Add a short-lived pseudo-condition (Vex/Sap/Slow) that lapses at the start of the wielder's next turn. */
    private List<String> mark(CombatEncounter enc, Enemy target, String condition, String beat) {
        target.getConditions().removeIf(c -> c.name() != null && c.name().equalsIgnoreCase(condition));
        target.getConditions().add(ActiveCondition.of(condition).expiringAt(enc.getRound() + 1));
        enemyRepository.save(target);
        return List.of(beat);
    }

    private List<String> push(CombatEncounter enc, Player attacker, Enemy target) {
        GridState grid = enc.getGridState();
        Token atk = CombatMath.tokenFor(grid, attacker.getId().toString());
        Token tgt = CombatMath.tokenFor(grid, target.getId().toString());
        if (grid == null || atk == null || tgt == null) {
            return List.of("The blow drives " + target.getName() + " back a step.");
        }
        int dx = Integer.signum(tgt.getX() - atk.getX());
        int dy = Integer.signum(tgt.getY() - atk.getY());
        if (dx == 0 && dy == 0) {
            return List.of("The blow drives " + target.getName() + " back a step.");
        }
        int moved = 0;
        for (int step = 0; step < 2; step++) { // 10 ft = two 5 ft squares
            int nx = tgt.getX() + dx;
            int ny = tgt.getY() + dy;
            if (nx < 0 || ny < 0 || nx >= grid.getWidth() || ny >= grid.getHeight()
                    || occupied(grid, nx, ny)) {
                break;
            }
            tgt.setX(nx);
            tgt.setY(ny);
            moved += GridService.FEET_PER_SQUARE;
        }
        if (moved == 0) {
            return List.of(target.getName() + " is driven against an obstacle but holds its ground.");
        }
        encounterRepository.save(enc);
        return List.of(target.getName() + " is shoved back " + moved + " feet.");
    }

    private List<String> cleave(CombatEncounter enc, Character attacker, Enemy primary, int damageDealt) {
        if (damageDealt <= 0) {
            return List.of();
        }
        Enemy second = nearestOtherFoe(enc, primary);
        if (second == null) {
            return List.of();
        }
        applyDamage(second, damageDealt);
        return List.of("The blow cleaves into " + second.getName() + " for " + damageDealt
                + (second.isAlive() ? " damage." : " damage, felling it."));
    }

    private List<String> nick(Character attacker, Enemy target, List<InventoryItem> inv) {
        InventoryItem offHand = CombatMath.offHandWeapon(inv);
        if (offHand == null) {
            return List.of();
        }
        DiceRollResult dmg = CombatMath.rollExpr(diceService, CombatMath.offHandDamageDice(offHand));
        if (dmg.total() <= 0) {
            return List.of();
        }
        applyDamage(target, dmg.total());
        return List.of("A quick off-hand nick lands for " + dmg.total()
                + (target.isAlive() ? " damage." : " damage, dropping it."));
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private int attackAbilityMod(Character c) {
        if (c == null) {
            return 0;
        }
        return Math.max(Math.floorDiv(c.getStrength() - 10, 2),
                Math.floorDiv(c.getDexterity() - 10, 2));
    }

    private void applyDamage(Enemy e, int dmg) {
        if (dmg <= 0) {
            return;
        }
        e.setCurrentHp(Math.max(0, e.getCurrentHp() - dmg));
        if (e.getCurrentHp() == 0) {
            e.setAlive(false);
        }
        enemyRepository.save(e);
    }

    private boolean occupied(GridState grid, int x, int y) {
        for (Token t : grid.getTokens().values()) {
            if (t.getX() == x && t.getY() == y) {
                return true;
            }
        }
        return false;
    }

    /** The nearest living enemy other than {@code primary} (adjacent on a grid; any other otherwise). */
    private Enemy nearestOtherFoe(CombatEncounter enc, Enemy primary) {
        GridState grid = enc.getGridState();
        Token primaryTok = CombatMath.tokenFor(grid, primary.getId().toString());
        Enemy best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Enemy e : enemyRepository.findBySessionId(enc.getSessionId())) {
            if (e.getId().equals(primary.getId()) || !e.isAlive()) {
                continue;
            }
            if (grid == null || primaryTok == null) {
                return e; // no grid — any other foe qualifies
            }
            Token t = CombatMath.tokenFor(grid, e.getId().toString());
            if (t == null) {
                continue;
            }
            int dist = gridService.distanceFeet(primaryTok.getX(), primaryTok.getY(), t.getX(), t.getY());
            if (dist <= GridService.FEET_PER_SQUARE && dist < bestDist) {
                best = e;
                bestDist = dist;
            }
        }
        return best;
    }
}
