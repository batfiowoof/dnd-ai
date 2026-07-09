package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.CombatActionEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.RollSummary;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.model.enums.SpellEffectType;
import com.dungeon.master.model.enums.SpellResolution;
import com.dungeon.master.model.enums.SpellTargetType;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.service.game.ConditionRules;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.GridService;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.SpellcastingRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a cast spell's mechanical effect: HEAL restores ally HP, DAMAGE rolls a spell attack
 * or save vs the caster's spell DC, and everything else applies a structured condition/buff.
 *
 * <p>Collaborator contract: appends narration to the {@code beat} list passed in and never owns
 * its own; mutates enemies/player runtime state (persisting Enemy / player-state — never the
 * {@link CombatEncounter}); broadcasts player-state updates over the websocket. The orchestrator
 * owns the encounter save and turn flow.</p>
 */
@Service
@RequiredArgsConstructor
public class CombatSpellResolver {

    private final PlayerStateService playerStateService;
    private final EnemyRepository enemyRepository;
    private final DiceService diceService;
    private final GridService gridService;
    private final CombatBroadcaster combatBroadcaster;
    private final CombatLookups combatLookups;

    public void resolveHeal(UUID sessionId, Player caster, Character casterChar, SpellEffect effect,
                            int spellLevel, List<UUID> targetIds,
                            List<CombatActionEvent.Target> results, List<String> beat) {
        String dice = CombatMath.scaledNotation(effect.healDice(), effect, spellLevel, casterChar);
        for (UUID pid : playerTargets(sessionId, targetIds, effect, caster)) {
            PlayerRuntimeStateDto before = playerStateService.getState(pid);
            int amount = CombatMath.rollExpr(diceService, dice).total();
            PlayerRuntimeStateDto updated = playerStateService.applyHpDelta(pid, amount);
            combatBroadcaster.broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            int healed = updated.currentHp() - before.currentHp();
            String name = combatLookups.playerName(pid);
            results.add(CombatActionEvent.Target.heal(CombatantKind.PLAYER, name, healed,
                    updated.currentHp(), updated.maxHp()));
            beat.add(caster.getCharacterName() + "'s " + effect.name() + " restores " + healed
                    + " HP to " + name + " (now " + updated.currentHp() + "/" + updated.maxHp() + ").");
        }
    }

    /** A damaging spell whose to-hit/saves are resolved but whose damage the player hasn't rolled. */
    public record PendingSpell(SpellEffect effect, int spellLevel, String dice, int dc,
                               List<PendingSpellTarget> targets) {
        /** True when at least one target will take damage once rolled (else the cast misses / fizzles). */
        public boolean anyDamage() {
            for (PendingSpellTarget t : targets) {
                boolean dmg = switch (t.mode()) {
                    case SPELL_ATTACK -> t.hit();
                    case SAVE -> !t.saved() || effect.halfOnSave();
                    default -> t.darts() > 0;
                };
                if (dmg) return true;
            }
            return false;
        }
    }

    /** One target's resolved phase-1 outcome (carries the to-hit/save roll for the phase-2 beat). */
    public record PendingSpellTarget(UUID enemyId, SpellResolution mode, DiceRollResult roll,
                                     int targetAc, boolean hit, boolean saved, int darts) {}

    /**
     * Phase 1 of a damaging spell: roll each target's spell attack / save and emit phase-1
     * result rows ({@code damageRoll == null}). Rolls NO damage, applies NO HP/conditions, and
     * adds NO narration — those happen in {@link #applySpellDamage} when the player rolls damage.
     */
    public PendingSpell resolveSpellToHit(CombatEncounter enc, UUID sessionId, Player caster, Character casterChar,
                                          SpellEffect effect, int spellLevel, List<UUID> targetIds,
                                          List<CombatActionEvent.Target> results, List<String> beat) {
        List<Enemy> targets = enemyTargets(sessionId, targetIds, effect);
        String dice = CombatMath.scaledNotation(effect.damageDice(), effect, spellLevel, casterChar);
        int dc = SpellcastingRules.spellSaveDc(casterChar);
        int atkBonus = SpellcastingRules.spellAttackBonus(casterChar)
                + ConditionRules.attackModifier(combatLookups.playerConds(caster.getId()));
        int[] darts = CombatMath.distribute(Math.max(1, effect.projectiles()), targets.size());
        GridState grid = enc.getGridState();
        Token casterTok = CombatMath.tokenFor(grid, caster.getId().toString());
        List<PendingSpellTarget> outcomes = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Enemy e = targets.get(i);
            switch (effect.resolution()) {
                case SPELL_ATTACK -> {
                    Token targetTok = CombatMath.tokenFor(grid, e.getId().toString());
                    // Touch spells are melee spell attacks (matters for advantage vs a prone target);
                    // ranged spell attacks are not. Auto-crit still never applies to spell attacks.
                    RollMode mode = RollMode.combine(
                            ConditionRules.attackMode(combatLookups.playerConds(caster.getId()),
                                    e.getConditions(), effect.isMeleeRange()),
                            targetTok != null && targetTok.isDodging()
                                    ? RollMode.DISADVANTAGE : RollMode.NORMAL);
                    DiceRollResult atk = CombatMath.rollAttack(diceService, atkBonus, mode);
                    int targetAc = CombatMath.effectiveAc(gridService, e.getArmorClass(), casterTok, targetTok, grid);
                    boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);
                    results.add(CombatActionEvent.Target.attack(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(atk), targetAc, hit, null,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    outcomes.add(new PendingSpellTarget(e.getId(), SpellResolution.SPELL_ATTACK,
                            atk, targetAc, hit, false, 0));
                }
                case SAVE -> {
                    EnemySave s = rollEnemySave(e, effect, dc, beat);
                    results.add(CombatActionEvent.Target.save(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(s.roll()), dc, s.saved(), null,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    outcomes.add(new PendingSpellTarget(e.getId(), SpellResolution.SAVE,
                            s.roll(), 0, false, s.saved(), 0));
                }
                default -> { // AUTO — auto-hit; projectiles (darts) distributed across targets
                    results.add(CombatActionEvent.Target.autoDamage(CombatantKind.ENEMY, e.getName(),
                            null, e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    outcomes.add(new PendingSpellTarget(e.getId(), SpellResolution.AUTO,
                            null, 0, false, false, darts[i]));
                }
            }
        }
        return new PendingSpell(effect, spellLevel, dice, dc, outcomes);
    }

    /**
     * Phase 2 of a damaging spell: roll + apply each resolved target's damage (crit-double on a
     * spell-attack crit, half-on-save, summed darts), apply any failed-save condition, and emit
     * the reveal rows (with {@code damageRoll}) + narration beats. Mirrors the original one-shot
     * resolver, just deferred to the player's damage roll.
     */
    public List<String> applySpellDamage(CombatEncounter enc, Player caster, PendingSpell ps,
                                         List<CombatActionEvent.Target> results) {
        SpellEffect effect = ps.effect();
        String dice = ps.dice();
        int dc = ps.dc();
        String label = caster.getCharacterName() + "'s " + effect.name();
        List<String> beat = new ArrayList<>();
        for (PendingSpellTarget t : ps.targets()) {
            Enemy e = enemyRepository.findById(t.enemyId()).orElse(null);
            if (e == null) continue;
            switch (t.mode()) {
                case SPELL_ATTACK -> {
                    DiceRollResult atk = t.roll();
                    RollSummary dmg = null;
                    if (t.hit()) {
                        DiceRollResult d = CombatMath.rollExpr(diceService,
                                atk.crit() ? CombatMath.critDouble(dice) : dice);
                        applyEnemyDamage(e, d.total());
                        dmg = RollSummary.of(d);
                    }
                    results.add(CombatActionEvent.Target.attack(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(atk), t.targetAc(), t.hit(), dmg,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    beat.add(CombatNarrationFormatter.describeAttack(label, e.getName(), atk, t.targetAc(), t.hit(), dmg,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                }
                case SAVE -> {
                    DiceRollResult save = t.roll();
                    boolean saved = t.saved();
                    DiceRollResult d = CombatMath.rollExpr(diceService, dice);
                    int dealt = saved ? (effect.halfOnSave() ? d.total() / 2 : 0) : d.total();
                    applyEnemyDamage(e, dealt);
                    // A damage spell can also impose a condition on a failed save (e.g. Web → restrained).
                    if (!saved && effect.condition() != null && e.isAlive()) {
                        applyEnemyCondition(enc, e, effect, caster, dc);
                    }
                    results.add(CombatActionEvent.Target.save(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(save), dc, saved, RollSummary.of(d),
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    beat.add(label + (saved ? " — " + e.getName() + " saves (DC " + dc + ") for "
                            : " hits " + e.getName() + " (failed DC " + dc + ") for ") + dealt
                            + " damage (" + e.getName() + " " + e.getCurrentHp() + "/" + e.getMaxHp() + ").");
                }
                default -> { // AUTO
                    int hits = t.darts();
                    if (hits <= 0) continue;  // this target got no darts
                    int total = 0;
                    List<Integer> faces = new ArrayList<>();
                    for (int h = 0; h < hits; h++) {
                        DiceRollResult d = CombatMath.rollExpr(diceService, dice);
                        total += d.total();
                        faces.addAll(d.faces());
                    }
                    applyEnemyDamage(e, total);
                    RollSummary dmg = new RollSummary(hits > 1 ? hits + "×" + dice : dice,
                            faces, total, false, false);
                    results.add(CombatActionEvent.Target.autoDamage(CombatantKind.ENEMY, e.getName(),
                            dmg, e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    beat.add(label + " strikes " + e.getName() + " for " + total + " damage ("
                            + e.getName() + " " + e.getCurrentHp() + "/" + e.getMaxHp() + ").");
                }
            }
        }
        return beat;
    }

    /** The outcome of one enemy saving throw: the d20 that was rolled, and whether it succeeded. */
    private record EnemySave(DiceRollResult roll, boolean saved) {}

    /**
     * Roll one enemy's saving throw against a spell, honouring conditions that auto-fail it or grant
     * advantage/disadvantage — the single place enemies roll saves.
     *
     * <p>A legendary creature may spend a <b>Legendary Resistance</b> to turn a failure into a
     * success, but only against an effect that would impose a condition. That keeps a boss from
     * burning its charges soaking Fireball damage while still letting it shrug off the save-or-lose
     * spells (Hold Monster, Banishment) that would otherwise end the fight before it acts.
     *
     * <p>Deliberately not applied to the "save ends" re-rolls in {@code expireEnemyConditions} — a
     * boss spends its charges resisting the spell, not escaping it afterwards.
     */
    private EnemySave rollEnemySave(Enemy e, SpellEffect effect, int dc, List<String> beat) {
        boolean autoFail = ConditionRules.autoFailsSave(e.getConditions(), effect.saveAbility());
        int saveMod = CombatMath.enemySaveMod(e, effect.saveAbility())
                + ConditionRules.saveModifier(e.getConditions());
        DiceRollResult save = diceService.roll(CombatMath.notation(saveMod),
                ConditionRules.saveMode(e.getConditions(), effect.saveAbility()));
        boolean saved = !autoFail && save.total() >= dc;

        if (!saved && effect.condition() != null && e.getLegendaryResistances() > 0) {
            int left = e.getLegendaryResistances() - 1;
            e.setLegendaryResistances(left);
            enemyRepository.save(e);
            saved = true;
            if (beat != null) {
                beat.add(e.getName() + " shrugs off " + effect.name()
                        + " with Legendary Resistance (" + left + (left == 1 ? " use" : " uses")
                        + " left).");
            }
        }
        return new EnemySave(save, saved);
    }

    /** BUFF / DEBUFF / CONTROL / UTILITY — tag a structured condition (save-gated for enemies) and narrate. */
    public void resolveSpellEffect(CombatEncounter enc, UUID sessionId, Player caster, Character casterChar,
                                   SpellEffect effect, List<UUID> targetIds,
                                   List<CombatActionEvent.Target> results, List<String> beat) {
        String cond = effect.condition();
        boolean offensive = effect.targetType() == SpellTargetType.ENEMY
                || (effect.targetType() == SpellTargetType.AREA
                    && effect.effectType() != SpellEffectType.BUFF);
        if (offensive) {
            int dc = SpellcastingRules.spellSaveDc(casterChar);
            for (Enemy e : enemyTargets(sessionId, targetIds, effect)) {
                boolean applied = true;
                RollSummary saveSummary = null;
                if (effect.resolution() == SpellResolution.SAVE) {
                    EnemySave s = rollEnemySave(e, effect, dc, beat);
                    applied = !s.saved();
                    saveSummary = RollSummary.of(s.roll());
                }
                if (applied && cond != null) {
                    applyEnemyCondition(enc, e, effect, caster, dc);
                }
                results.add(CombatActionEvent.Target.of(CombatantKind.ENEMY, e.getName(),
                        null, null, null, saveSummary, saveSummary == null ? null : dc,
                        saveSummary == null ? null : !applied, null, null,
                        applied ? cond : null, e.getCurrentHp(), e.getMaxHp(), false));
                beat.add(caster.getCharacterName() + "'s " + effect.name()
                        + (applied && cond != null ? " leaves " + e.getName() + " " + cond + "."
                        : " has no effect on " + e.getName() + "."));
            }
        } else {
            for (UUID pid : playerTargets(sessionId, targetIds, effect, caster)) {
                if (cond != null) {
                    ActiveCondition c = ActiveCondition.fromSpell(cond, caster.getId(), effect.name(),
                            effect.concentration());
                    c = effect.concentration() ? c : c.expiringAt(enc.getRound() + 10);
                    combatBroadcaster.broadcast(sessionId, PlayerStateEvent.of(sessionId,
                            playerStateService.applyCondition(pid, c)));
                }
                // Temp-HP buffs (False Life, Heroism): roll and grant (doesn't stack — take higher).
                if (effect.tempHpDice() != null) {
                    int amount = CombatMath.rollExpr(diceService, effect.tempHpDice()).total();
                    combatBroadcaster.broadcast(sessionId, PlayerStateEvent.of(sessionId,
                            playerStateService.applyTempHp(pid, amount)));
                }
                PlayerRuntimeStateDto s = playerStateService.getState(pid);
                results.add(CombatActionEvent.Target.effect(CombatantKind.PLAYER, combatLookups.playerName(pid),
                        cond, s.currentHp(), s.maxHp()));
                beat.add(caster.getCharacterName() + "'s " + effect.name() + " bolsters "
                        + combatLookups.playerName(pid) + ".");
            }
        }
    }

    /** Whether a spell selects enemies (matches {@link #resolveSpellEffect}'s offensive test, plus DAMAGE). */
    public boolean isOffensive(SpellEffect effect) {
        return effect.effectType() == SpellEffectType.DAMAGE
                || effect.targetType() == SpellTargetType.ENEMY
                || (effect.targetType() == SpellTargetType.AREA
                    && effect.effectType() != SpellEffectType.BUFF);
    }

    /**
     * For an area-of-effect spell with a grid cast point, the ids of every alive enemy whose
     * token falls inside the resolved template. Returns {@code null} when the spell is not an
     * AoE, no origin was supplied, or the encounter has no grid — signalling the caller to keep
     * the client-sent targetIds. A non-null (possibly empty) list overrides those targetIds.
     */
    public List<UUID> aoeEnemyIds(CombatEncounter enc, SpellEffect effect,
                                  Integer originX, Integer originY) {
        GridState grid = enc.getGridState();
        if (effect.aoeShape() == null || originX == null || originY == null
                || grid == null || grid.getTokens() == null) {
            return null;
        }
        List<GridService.Square> cells =
                gridService.aoeCells(originX, originY, effect.aoeShape(), effect.aoeSize());
        List<UUID> ids = new ArrayList<>();
        if (cells.isEmpty()) {
            return ids;
        }
        for (Enemy e : enemyRepository.findBySessionId(enc.getSessionId())) {
            if (!e.isAlive()) {
                continue;
            }
            Token t = grid.getTokens().get(e.getId().toString());
            if (t != null && cells.contains(new GridService.Square(t.getX(), t.getY()))) {
                ids.add(e.getId());
            }
        }
        return ids;
    }

    /* ── internals ───────────────────────────────────────────────── */

    private List<Enemy> enemyTargets(UUID sessionId, List<UUID> ids, SpellEffect effect) {
        List<Enemy> alive = enemyRepository.findBySessionId(sessionId).stream()
                .filter(Enemy::isAlive).toList();
        List<Enemy> chosen = new ArrayList<>();
        if (ids != null) {
            for (UUID id : ids) {
                alive.stream().filter(e -> e.getId().equals(id)).findFirst().ifPresent(chosen::add);
            }
        }
        // Fall back to the first enemy ONLY when no target list was given (null = legacy
        // "unspecified"). A non-null but EMPTY list is an authoritative result — e.g. an AoE that
        // caught nobody — and must fizzle rather than snap onto an enemy outside the template.
        if (ids == null && chosen.isEmpty() && !alive.isEmpty()) chosen.add(alive.get(0));
        Integer max = effect.maxTargets();
        int cap = max == null ? chosen.size() : Math.min(max, chosen.size());
        return new ArrayList<>(chosen.subList(0, Math.max(0, cap)));
    }

    private List<UUID> playerTargets(UUID sessionId, List<UUID> ids, SpellEffect effect, Player caster) {
        if (effect.targetType() == SpellTargetType.SELF) {
            return List.of(caster.getId());
        }
        // Any player in the session is a valid heal/buff target — INCLUDING downed (0 HP)
        // allies, since healing a creature at 0 HP is exactly how you revive it in 5e.
        Set<UUID> valid = playerStateService.getSessionStates(sessionId).stream()
                .map(PlayerRuntimeStateDto::playerId)
                .collect(Collectors.toSet());
        List<UUID> chosen = new ArrayList<>();
        if (ids != null) {
            for (UUID id : ids) if (valid.contains(id)) chosen.add(id);
        }
        if (chosen.isEmpty()) chosen.add(caster.getId());
        Integer max = effect.maxTargets();
        int cap = max == null ? chosen.size() : Math.min(max, chosen.size());
        return new ArrayList<>(chosen.subList(0, Math.max(0, cap)));
    }

    /**
     * Build and attach a structured condition to an enemy (replacing any same-named one).
     * Concentration spells stay until the caster's concentration drops; those resolved by a
     * save get a "save ends" clause re-rolled at the enemy's turn start. Non-concentration
     * conditions lapse after ~1 minute (10 rounds).
     */
    private void applyEnemyCondition(CombatEncounter enc, Enemy e, SpellEffect effect, Player caster, int dc) {
        String cond = effect.condition();
        if (cond == null) {
            return;
        }
        ActiveCondition c = ActiveCondition.fromSpell(cond, caster.getId(), effect.name(), effect.concentration());
        if (effect.concentration()) {
            if (effect.resolution() == SpellResolution.SAVE && effect.saveAbility() != null) {
                c = c.savingEnds(effect.saveAbility(), dc);
            }
        } else {
            c = c.expiringAt(enc.getRound() + 10);
        }
        e.getConditions().removeIf(x -> x.name() != null && x.name().equalsIgnoreCase(cond));
        e.getConditions().add(c);
        enemyRepository.save(e);
    }

    private void applyEnemyDamage(Enemy e, int dmg) {
        if (dmg <= 0) return;
        e.setCurrentHp(Math.max(0, e.getCurrentHp() - dmg));
        if (e.getCurrentHp() == 0) e.setAlive(false);
        enemyRepository.save(e);
    }
}
