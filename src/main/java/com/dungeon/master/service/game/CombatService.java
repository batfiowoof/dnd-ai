package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.kafka.event.CombatNarrationEvent;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.CombatActionEvent;
import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.CombatLifecycleEvent;
import com.dungeon.master.model.dto.CombatStateDto;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.EnemyDto;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.RollSummary;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.model.enums.SpellEffectType;
import com.dungeon.master.model.enums.SpellResolution;
import com.dungeon.master.model.enums.SpellTargetType;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.SpellCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Combat overlay state machine. While an encounter is ACTIVE the narrative turn
 * pointer is frozen; combat tracks its own initiative order + active index. The
 * backend resolves every roll and HP change; enemy turns auto-resolve when the
 * order advances onto them. Combat actions never call {@code TurnService.submitAction}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CombatService {

    private final EnemyRepository enemyRepository;
    private final CombatEncounterRepository encounterRepository;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final GameSessionRepository sessionRepository;
    private final PlayerStateService playerStateService;
    private final DiceService diceService;
    private final TurnService turnService;
    private final GameEventProducer eventProducer;
    private final SimpMessagingTemplate messagingTemplate;
    private final MonsterCatalog monsterCatalog;
    private final SpellCatalog spellCatalog;

    /** Monotonic per-action ordering for {@link CombatActionEvent} (client playback queue). */
    private final AtomicLong actionSeq = new AtomicLong();

    /* ── reads ───────────────────────────────────────────────────── */

    public Optional<CombatStateDto> getActiveCombat(UUID sessionId) {
        return encounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)
                .map(this::toStateDto);
    }

    /* ── start ───────────────────────────────────────────────────── */

    @Transactional
    public void startEncounter(UUID sessionId, List<String> enemyKeys) {
        if (encounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("An encounter is already in progress");
        }
        if (enemyKeys == null || enemyKeys.isEmpty()) {
            throw new IllegalArgumentException("Specify at least one enemy");
        }

        Difficulty difficulty = sessionRepository.findById(sessionId)
                .map(GameSession::getDifficulty)
                .orElse(Difficulty.NORMAL);

        // Create enemies, numbering duplicates (Goblin 1, Goblin 2, ...). Difficulty scales
        // HP and attack bonus so the same key is tougher on DEADLY and softer on EASY.
        List<Enemy> enemies = new ArrayList<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String key : enemyKeys) {
            long sameType = enemyKeys.stream().filter(k -> k.equalsIgnoreCase(key)).count();
            int n = counts.merge(key.toLowerCase(), 1, Integer::sum);
            enemies.add(buildEnemy(sessionId, key, sameType > 1 ? n : 0, difficulty));
        }
        enemyRepository.saveAll(enemies);

        // Ensure every player has runtime state BEFORE building initiative — a missing state
        // makes playerDown()/noLivingPlayers() treat the player as down (an instant TPK).
        List<Player> players = playerRepository.findBySessionId(sessionId);
        for (Player p : players) {
            if (p.getRole() == PlayerRole.PLAYER) {
                playerStateService.ensureSeeded(p, character(p));
            }
        }

        // Build initiative order: players (1d20 + DEX mod) + enemies.
        List<Combatant> order = new ArrayList<>();
        for (Player p : players) {
            if (p.getRole() != PlayerRole.PLAYER) continue;
            int dex = dexMod(p);
            int init = diceService.roll("1d20").total() + dex;
            order.add(new Combatant(CombatantKind.PLAYER, p.getId(), p.getCharacterName(), init, dex));
        }
        for (Enemy e : enemies) {
            order.add(new Combatant(CombatantKind.ENEMY, e.getId(), e.getName(),
                    e.getInitiative(), e.getDexMod()));
        }
        // D&D 5e: rank initiative high→low; ties broken by DEX mod (also high→low), then a
        // stable fallback (players before enemies, then name) so ordering is reproducible.
        order.sort(Comparator.comparingInt(Combatant::initiative).reversed()
                .thenComparing(Comparator.comparingInt(Combatant::dexMod).reversed())
                .thenComparingInt(c -> c.kind() == CombatantKind.PLAYER ? 0 : 1)
                .thenComparing(c -> c.name() == null ? "" : c.name()));

        CombatEncounter enc = CombatEncounter.builder()
                .sessionId(sessionId)
                .status(CombatStatus.ACTIVE)
                .initiativeOrder(order)
                .activeIndex(0)
                .round(1)
                .build();
        enc = encounterRepository.save(enc);

        broadcast(sessionId, CombatLifecycleEvent.start(sessionId, toStateDto(enc)));
        log.info("Combat started: session={}, enemies={}, combatants={}",
                sessionId, enemies.size(), order.size());

        List<String> beat = new ArrayList<>();
        beat.add("Combat begins! The party faces " + roster(enemies)
                + ". Initiative is rolled.");
        resolveUntilPlayerOrEnd(enc, beat);
        flushBeat(sessionId, anyPlayerId(sessionId), beat);
    }

    /* ── player actions ──────────────────────────────────────────── */

    @Transactional
    public void playerAttack(UUID sessionId, String username, UUID targetEnemyId) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        Enemy enemy = enemyRepository.findById(targetEnemyId)
                .filter(e -> e.getSessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Enemy not found"));
        if (!enemy.isAlive()) {
            throw new IllegalStateException(enemy.getName() + " is already defeated");
        }

        int attackBonus = attackBonus(player);
        DiceRollResult atk = diceService.roll(notation(attackBonus));
        boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= enemy.getArmorClass());

        RollSummary damageSummary = null;
        boolean defeated = false;
        if (hit) {
            DiceRollResult dmg = diceService.roll(damageDice(player));
            enemy.setCurrentHp(Math.max(0, enemy.getCurrentHp() - dmg.total()));
            if (enemy.getCurrentHp() == 0) {
                enemy.setAlive(false);
                defeated = true;
            }
            enemyRepository.save(enemy);
            damageSummary = RollSummary.of(dmg);
        }

        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK", "attacks",
                List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                        RollSummary.of(atk), enemy.getArmorClass(), hit, damageSummary,
                        enemy.getCurrentHp(), enemy.getMaxHp(), defeated)));

        List<String> beat = new ArrayList<>();
        beat.add(describeAttack(player.getCharacterName(), enemy.getName(), atk,
                enemy.getArmorClass(), hit, damageSummary,
                enemy.getCurrentHp(), enemy.getMaxHp(), defeated));
        advanceTurn(enc, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    @Transactional
    public void playerUseItem(UUID sessionId, String username, String itemName) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        PlayerStateService.ItemUseResult result = playerStateService.useItem(player.getId(), itemName);
        broadcast(sessionId, PlayerStateEvent.of(sessionId, result.state()));

        List<String> beat = new ArrayList<>();
        beat.add(describeItemUse(player.getCharacterName(), result));
        advanceTurn(enc, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /**
     * Cast a prepared spell on the player's combat turn. The spell's machine-readable
     * effect (from {@link SpellCatalog}) decides resolution: DAMAGE rolls a spell attack
     * vs AC or a save vs the caster's spell DC; HEAL restores ally HP; everything else is
     * applied as a (mostly narrative) condition/buff. Leveled spells spend a slot; cantrips
     * are free. {@code targetIds} are enemy ids (offensive) or player ids (heal/buff).
     */
    @Transactional
    public void playerCastSpell(UUID sessionId, String username, String spellName,
                                int spellLevel, List<UUID> targetIds) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        PlayerRuntimeStateDto state = playerStateService.getState(player.getId());
        if (!containsIgnoreCase(state.cantrips(), spellName)
                && !containsIgnoreCase(state.knownSpells(), spellName)) {
            throw new IllegalStateException("You don't have " + spellName + " prepared");
        }

        SpellEffect effect = spellCatalog.effect(spellName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown spell: " + spellName));

        // Spend a slot for leveled spells (cantrips are free). Throws if none remain.
        if (spellLevel >= 1) {
            PlayerRuntimeStateDto after = playerStateService.useSpellSlot(player.getId(), spellLevel);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, after));
        }

        Character caster = character(player);
        List<CombatActionEvent.Target> results = new ArrayList<>();
        List<String> beat = new ArrayList<>();
        String actionKind = switch (effect.effectType()) {
            case HEAL -> {
                resolveHeal(sessionId, player, caster, effect, spellLevel, targetIds, results, beat);
                yield "SPELL_HEAL";
            }
            case DAMAGE -> {
                resolveSpellDamage(sessionId, player, caster, effect, spellLevel, targetIds, results, beat);
                yield "SPELL_DAMAGE";
            }
            default -> {
                resolveSpellEffect(sessionId, player, caster, effect, targetIds, results, beat);
                yield "SPELL_EFFECT";
            }
        };

        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(),
                actionKind, "casts " + effect.name(), results);
        advanceTurn(enc, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    @Transactional
    public void playerEndTurn(UUID sessionId, String username) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        List<String> beat = new ArrayList<>();
        beat.add(player.getCharacterName() + " holds their action and ends their turn.");
        advanceTurn(enc, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    @Transactional
    public void endEncounterByHost(UUID sessionId) {
        CombatEncounter enc = activeEncounter(sessionId);
        List<String> beat = new ArrayList<>();
        endEncounter(enc, allEnemiesDead(sessionId), beat);
        flushBeat(sessionId, anyPlayerId(sessionId), beat);
    }

    /* ── turn engine ─────────────────────────────────────────────── */

    private void advanceTurn(CombatEncounter enc, List<String> beat) {
        advanceIndex(enc);
        resolveUntilPlayerOrEnd(enc, beat);
    }

    /**
     * Auto-resolve enemy turns (and skip dead/downed combatants) until it is a
     * living player's turn or the encounter ends. Three termination guards plus a
     * hard step cap make non-termination impossible.
     */
    private void resolveUntilPlayerOrEnd(CombatEncounter enc, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        int order = enc.getInitiativeOrder().size();
        int maxSteps = Math.max(1, order) * 4 + 8;

        for (int step = 0; step < maxSteps; step++) {
            if (allEnemiesDead(sessionId)) {           // guard (a): victory
                endEncounter(enc, true, beat);
                return;
            }
            if (noLivingPlayers(sessionId)) {          // guard (b): TPK
                endEncounter(enc, false, beat);
                return;
            }

            Combatant active = enc.getInitiativeOrder().get(enc.getActiveIndex());

            if (active.kind() == CombatantKind.ENEMY) {
                Enemy e = enemyRepository.findById(active.refId()).orElse(null);
                if (e == null || !e.isAlive()) {
                    advanceIndex(enc);
                    continue;
                }
                enemyAct(enc, e, beat);
                advanceIndex(enc);
                continue;
            }

            // PLAYER — skip if downed, otherwise it's their turn.
            if (playerDown(active.refId())) {
                advanceIndex(enc);
                continue;
            }
            encounterRepository.save(enc);
            broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
            return;
        }

        // Safety net — should be unreachable given the guards above.
        log.warn("Combat step cap hit for session={}, ending encounter", sessionId);
        endEncounter(enc, allEnemiesDead(sessionId), beat);
    }

    /**
     * Resolve one enemy's turn. Multiattack monsters swing {@code attacksPerTurn} times
     * (re-acquiring a living target between swings); all sub-attacks are reported in a
     * single {@link CombatActionEvent} so the client animates them together, in order.
     * Uses the stat block's primary attack ({@link Enemy#getAttacks()}), falling back to
     * the legacy {@code attackBonus}/{@code damageDice}.
     */
    private void enemyAct(CombatEncounter enc, Enemy enemy, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        TargetPlayer target = pickTarget(sessionId);
        if (target == null) {                          // guard (c): no valid target
            return;
        }

        List<MonsterAttack> atks = enemy.getAttacks();
        int attackBonus = atks.isEmpty() ? enemy.getAttackBonus() : atks.get(0).toHit();
        String damageDice = atks.isEmpty() ? enemy.getDamageDice() : atks.get(0).damageDice();
        int swings = Math.max(1, enemy.getAttacksPerTurn());

        List<CombatActionEvent.Target> results = new ArrayList<>();
        for (int s = 0; s < swings; s++) {
            if (target.state().currentHp() <= 0) {     // current target down — re-acquire
                target = pickTarget(sessionId);
                if (target == null) break;
            }
            Player victim = target.player();
            DiceRollResult atk = diceService.roll(notation(attackBonus));
            int targetAc = armorClass(victim);
            boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);

            RollSummary damageSummary = null;
            int targetHp = target.state().currentHp();
            int targetMax = target.state().maxHp();
            boolean defeated = false;
            if (hit) {
                DiceRollResult dmg = rollExpr(damageDice);
                PlayerRuntimeStateDto updated =
                        playerStateService.applyHpDelta(victim.getId(), -dmg.total());
                broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
                targetHp = updated.currentHp();
                targetMax = updated.maxHp();
                defeated = updated.currentHp() <= 0;
                damageSummary = RollSummary.of(dmg);
                target = new TargetPlayer(victim, updated);  // refresh for the next swing
            }

            results.add(CombatActionEvent.Target.attack(CombatantKind.PLAYER, victim.getCharacterName(),
                    RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated));
            beat.add(describeAttack(enemy.getName(), victim.getCharacterName(), atk,
                    targetAc, hit, damageSummary, targetHp, targetMax, defeated));
        }

        broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "ATTACK", "attacks", results);
    }

    private void advanceIndex(CombatEncounter enc) {
        int size = enc.getInitiativeOrder().size();
        if (size == 0) return;
        int next = enc.getActiveIndex() + 1;
        if (next >= size) {
            next = 0;
            enc.setRound(enc.getRound() + 1);
        }
        enc.setActiveIndex(next);
    }

    private void endEncounter(CombatEncounter enc, boolean victory, List<String> beat) {
        enc.setStatus(CombatStatus.ENDED);
        encounterRepository.save(enc);
        if (beat != null) {
            beat.add(victory
                    ? "The last foe falls — the party stands victorious."
                    : "The party is overwhelmed and falls...");
        }
        broadcast(enc.getSessionId(), CombatLifecycleEvent.end(enc.getSessionId(), victory, toStateDto(enc)));
        log.info("Combat ended: session={}, victory={}", enc.getSessionId(), victory);
    }

    /* ── spell resolution ────────────────────────────────────────── */

    private void resolveHeal(UUID sessionId, Player caster, Character casterChar, SpellEffect effect,
                             int spellLevel, List<UUID> targetIds,
                             List<CombatActionEvent.Target> results, List<String> beat) {
        String dice = scaledNotation(effect.healDice(), effect, spellLevel, casterChar);
        for (UUID pid : playerTargets(sessionId, targetIds, effect, caster)) {
            PlayerRuntimeStateDto before = playerStateService.getState(pid);
            int amount = rollExpr(dice).total();
            PlayerRuntimeStateDto updated = playerStateService.applyHpDelta(pid, amount);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            int healed = updated.currentHp() - before.currentHp();
            String name = playerName(pid);
            results.add(CombatActionEvent.Target.heal(CombatantKind.PLAYER, name, healed,
                    updated.currentHp(), updated.maxHp()));
            beat.add(caster.getCharacterName() + "'s " + effect.name() + " restores " + healed
                    + " HP to " + name + " (now " + updated.currentHp() + "/" + updated.maxHp() + ").");
        }
    }

    private void resolveSpellDamage(UUID sessionId, Player caster, Character casterChar, SpellEffect effect,
                                    int spellLevel, List<UUID> targetIds,
                                    List<CombatActionEvent.Target> results, List<String> beat) {
        List<Enemy> targets = enemyTargets(sessionId, targetIds, effect);
        String dice = scaledNotation(effect.damageDice(), effect, spellLevel, casterChar);
        int dc = SpellcastingRules.spellSaveDc(casterChar);
        int atkBonus = SpellcastingRules.spellAttackBonus(casterChar);
        int[] darts = distribute(Math.max(1, effect.projectiles()), targets.size());
        for (int i = 0; i < targets.size(); i++) {
            Enemy e = targets.get(i);
            String label = caster.getCharacterName() + "'s " + effect.name();
            switch (effect.resolution()) {
                case SPELL_ATTACK -> {
                    DiceRollResult atk = diceService.roll(notation(atkBonus));
                    boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= e.getArmorClass());
                    RollSummary dmg = null;
                    if (hit) {
                        DiceRollResult d = rollExpr(dice);
                        applyEnemyDamage(e, d.total());
                        dmg = RollSummary.of(d);
                    }
                    results.add(CombatActionEvent.Target.attack(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(atk), e.getArmorClass(), hit, dmg,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    beat.add(describeAttack(label, e.getName(), atk, e.getArmorClass(), hit, dmg,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                }
                case SAVE -> {
                    int saveMod = enemySaveMod(e, effect.saveAbility());
                    DiceRollResult save = diceService.roll(notation(saveMod));
                    boolean saved = save.total() >= dc;
                    DiceRollResult d = rollExpr(dice);
                    int dealt = saved ? (effect.halfOnSave() ? d.total() / 2 : 0) : d.total();
                    applyEnemyDamage(e, dealt);
                    results.add(CombatActionEvent.Target.save(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(save), dc, saved, RollSummary.of(d),
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    beat.add(label + (saved ? " — " + e.getName() + " saves (DC " + dc + ") for "
                            : " hits " + e.getName() + " (failed DC " + dc + ") for ") + dealt
                            + " damage (" + e.getName() + " " + e.getCurrentHp() + "/" + e.getMaxHp() + ").");
                }
                default -> { // AUTO — auto-hit; multiple darts may stack on one target
                    int hits = Math.max(1, darts[i]);
                    int total = 0;
                    List<Integer> faces = new ArrayList<>();
                    for (int h = 0; h < hits; h++) {
                        DiceRollResult d = rollExpr(dice);
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
    }

    /** BUFF / DEBUFF / CONTROL / UTILITY — tag a condition (save-gated for enemies) and narrate. */
    private void resolveSpellEffect(UUID sessionId, Player caster, Character casterChar, SpellEffect effect,
                                    List<UUID> targetIds,
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
                    DiceRollResult save = diceService.roll(notation(enemySaveMod(e, effect.saveAbility())));
                    applied = save.total() < dc;
                    saveSummary = RollSummary.of(save);
                }
                if (applied && cond != null) {
                    if (!e.getConditions().contains(cond)) e.getConditions().add(cond);
                    enemyRepository.save(e);
                }
                results.add(new CombatActionEvent.Target(CombatantKind.ENEMY, e.getName(),
                        null, null, null, saveSummary, saveSummary == null ? null : dc,
                        saveSummary == null ? null : !applied, null, null,
                        applied ? cond : null, e.getCurrentHp(), e.getMaxHp(), false));
                beat.add(caster.getCharacterName() + "'s " + effect.name()
                        + (applied && cond != null ? " leaves " + e.getName() + " " + cond + "."
                        : " has no effect on " + e.getName() + "."));
            }
        } else {
            for (UUID pid : playerTargets(sessionId, targetIds, effect, caster)) {
                PlayerRuntimeStateDto s = playerStateService.getState(pid);
                results.add(CombatActionEvent.Target.effect(CombatantKind.PLAYER, playerName(pid),
                        cond, s.currentHp(), s.maxHp()));
                beat.add(caster.getCharacterName() + "'s " + effect.name() + " bolsters "
                        + playerName(pid) + ".");
            }
        }
    }

    /* ── spell helpers ───────────────────────────────────────────── */

    private static final Pattern DICE = Pattern.compile("(\\d*)d(\\d+)([+-]\\d+)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Combine a base dice expression with the spell's slot/cantrip scaling and (when the
     * spell adds it) the caster's spellcasting modifier into a single rollable notation.
     * Scaling dice are folded into the count only when their faces match the base; an
     * unparseable expression is returned unchanged.
     */
    private String scaledNotation(String baseDice, SpellEffect effect, int spellLevel, Character caster) {
        if (baseDice == null || baseDice.isBlank()) return "0";
        Matcher m = DICE.matcher(baseDice.trim());
        if (!m.matches()) return baseDice;
        int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int mod = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));

        if (effect.perSlotAbove() != null && spellLevel > effect.level()) {
            Matcher s = DICE.matcher(effect.perSlotAbove());
            if (s.matches() && Integer.parseInt(s.group(2)) == sides) {
                int per = s.group(1).isEmpty() ? 1 : Integer.parseInt(s.group(1));
                count += per * (spellLevel - effect.level());
            }
        }
        if (effect.cantripDie() != null && effect.level() == 0) {
            int lvl = caster == null ? 1 : caster.getLevel();
            int tier = (lvl >= 5 ? 1 : 0) + (lvl >= 11 ? 1 : 0) + (lvl >= 17 ? 1 : 0);
            Matcher cnt = DICE.matcher(effect.cantripDie());
            if (cnt.matches() && Integer.parseInt(cnt.group(2)) == sides) {
                int per = cnt.group(1).isEmpty() ? 1 : Integer.parseInt(cnt.group(1));
                count += per * tier;
            }
        }
        if (effect.addCastingMod()) mod += SpellcastingRules.castingMod(caster);
        return count + "d" + sides + (mod > 0 ? "+" + mod : mod < 0 ? String.valueOf(mod) : "");
    }

    /** Roll a damage/heal expression that may be standard dice ("8d6+3") or a flat number ("1"). */
    private DiceRollResult rollExpr(String expr) {
        if (expr == null || expr.isBlank()) return constant(0);
        String e = expr.trim();
        if (e.matches("\\d+")) return constant(Integer.parseInt(e));
        if (DICE.matcher(e).matches()) return diceService.roll(e);
        return constant(0);
    }

    private static DiceRollResult constant(int n) {
        return new DiceRollResult(String.valueOf(n), 0, 0, n, RollMode.NORMAL,
                List.of(n), null, n, false, false);
    }

    private void applyEnemyDamage(Enemy e, int dmg) {
        if (dmg <= 0) return;
        e.setCurrentHp(Math.max(0, e.getCurrentHp() - dmg));
        if (e.getCurrentHp() == 0) e.setAlive(false);
        enemyRepository.save(e);
    }

    /** An enemy's saving-throw modifier for an ability (ability mod; DEX falls back to dexMod). */
    private int enemySaveMod(Enemy e, String ability) {
        if (ability == null) return e.getDexMod();
        Integer score = e.getAbilities() == null ? null : e.getAbilities().get(ability.toUpperCase());
        if (score != null) return Math.floorDiv(score - 10, 2);
        return "DEX".equalsIgnoreCase(ability) ? e.getDexMod() : 0;
    }

    /** Spread {@code n} darts/rays across {@code k} targets as evenly as possible. */
    private static int[] distribute(int n, int k) {
        if (k <= 0) return new int[0];
        int[] out = new int[k];
        for (int i = 0; i < k; i++) out[i] = n / k + (i < n % k ? 1 : 0);
        return out;
    }

    private List<Enemy> enemyTargets(UUID sessionId, List<UUID> ids, SpellEffect effect) {
        List<Enemy> alive = enemyRepository.findBySessionId(sessionId).stream()
                .filter(Enemy::isAlive).toList();
        List<Enemy> chosen = new ArrayList<>();
        if (ids != null) {
            for (UUID id : ids) {
                alive.stream().filter(e -> e.getId().equals(id)).findFirst().ifPresent(chosen::add);
            }
        }
        if (chosen.isEmpty() && !alive.isEmpty()) chosen.add(alive.get(0));
        Integer max = effect.maxTargets();
        int cap = max == null ? chosen.size() : Math.min(max, chosen.size());
        return new ArrayList<>(chosen.subList(0, Math.max(0, cap)));
    }

    private List<UUID> playerTargets(UUID sessionId, List<UUID> ids, SpellEffect effect, Player caster) {
        if (effect.targetType() == SpellTargetType.SELF) {
            return List.of(caster.getId());
        }
        Set<UUID> alive = playerStateService.getSessionStates(sessionId).stream()
                .filter(s -> s.currentHp() > 0).map(PlayerRuntimeStateDto::playerId)
                .collect(Collectors.toSet());
        List<UUID> chosen = new ArrayList<>();
        if (ids != null) {
            for (UUID id : ids) if (alive.contains(id)) chosen.add(id);
        }
        if (chosen.isEmpty()) chosen.add(caster.getId());
        Integer max = effect.maxTargets();
        int cap = max == null ? chosen.size() : Math.min(max, chosen.size());
        return new ArrayList<>(chosen.subList(0, Math.max(0, cap)));
    }

    private String playerName(UUID playerId) {
        return playerRepository.findById(playerId).map(Player::getCharacterName).orElse("an ally");
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        if (list == null || value == null) return false;
        return list.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(value));
    }

    private void broadcastAction(CombatEncounter enc, CombatantKind actorKind, String actorName,
                                 String actionKind, String label, List<CombatActionEvent.Target> targets) {
        broadcast(enc.getSessionId(), new CombatActionEvent(
                CombatActionEvent.TYPE, enc.getSessionId(), actionSeq.incrementAndGet(),
                actorKind, actorName, actionKind, label, targets, toStateDto(enc)));
    }

    /* ── target selection / predicates ───────────────────────────── */

    private record TargetPlayer(Player player, PlayerRuntimeStateDto state) {}

    /** Living player with the lowest current HP (simple but threatening AI). */
    private TargetPlayer pickTarget(UUID sessionId) {
        List<PlayerRuntimeStateDto> living = playerStateService.getSessionStates(sessionId).stream()
                .filter(s -> s.currentHp() > 0)
                .sorted(Comparator.comparingInt(PlayerRuntimeStateDto::currentHp))
                .toList();
        for (PlayerRuntimeStateDto s : living) {
            Optional<Player> p = playerRepository.findById(s.playerId());
            if (p.isPresent() && p.get().getRole() == PlayerRole.PLAYER) {
                return new TargetPlayer(p.get(), s);
            }
        }
        return null;
    }

    private boolean allEnemiesDead(UUID sessionId) {
        return enemyRepository.findBySessionId(sessionId).stream().noneMatch(Enemy::isAlive);
    }

    private boolean noLivingPlayers(UUID sessionId) {
        return playerStateService.getSessionStates(sessionId).stream()
                .noneMatch(s -> s.currentHp() > 0);
    }

    private boolean playerDown(UUID playerId) {
        try {
            return playerStateService.getState(playerId).currentHp() <= 0;
        } catch (RuntimeException e) {
            return true; // no runtime state — treat as unavailable
        }
    }

    /* ── validation ──────────────────────────────────────────────── */

    private CombatEncounter activeEncounter(UUID sessionId) {
        return encounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("No active encounter"));
    }

    private Player requireCombatTurn(CombatEncounter enc, UUID sessionId, String username) {
        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + username));
        Combatant active = enc.getInitiativeOrder().get(enc.getActiveIndex());
        if (active.kind() != CombatantKind.PLAYER || !active.refId().equals(player.getId())) {
            throw new IllegalStateException("It's not your combat turn");
        }
        return player;
    }

    /* ── enemy construction ──────────────────────────────────────── */

    /**
     * Build one enemy from the {@link MonsterCatalog} stat block (preferred), falling
     * back to the legacy hardcoded {@link Bestiary} when the key has no catalog entry.
     * {@code index} numbers duplicates ("Goblin 2"); 0 means it is the only one of its type.
     */
    private Enemy buildEnemy(UUID sessionId, String key, int index, Difficulty difficulty) {
        int atkDelta = attackBonusDelta(difficulty);
        int d20 = diceService.roll("1d20").total();

        Optional<MonsterTemplate> tmpl = monsterCatalog.get(key);
        if (tmpl.isPresent()) {
            MonsterTemplate t = tmpl.get();
            String name = index > 0 ? t.name() + " " + index : t.name();
            int hp = scaleHp(t.hp(), difficulty);
            List<MonsterAttack> scaled = new ArrayList<>();
            for (MonsterAttack a : t.attacks()) {
                scaled.add(new MonsterAttack(a.name(), a.kind(), a.toHit() + atkDelta,
                        a.reach(), a.range(), a.damageDice(), a.damageType()));
            }
            MonsterAttack primary = scaled.isEmpty() ? null : scaled.get(0);
            int perTurn = t.multiattack() != null ? Math.max(1, t.multiattack().count()) : 1;
            return Enemy.builder()
                    .id(UUID.randomUUID()).sessionId(sessionId).name(name)
                    .maxHp(hp).currentHp(hp).armorClass(t.ac())
                    .attackBonus(primary != null ? primary.toHit() : 2 + atkDelta)
                    .damageDice(primary != null ? primary.damageDice() : "1d6")
                    .attacks(scaled).attacksPerTurn(perTurn)
                    .abilities(t.abilities() != null ? new LinkedHashMap<>(t.abilities())
                            : new LinkedHashMap<>())
                    .initiative(d20 + t.dexMod()).dexMod(t.dexMod()).alive(true)
                    .build();
        }

        Bestiary.Template t = Bestiary.get(key);
        String name = index > 0 ? t.name() + " " + index : t.name();
        int hp = scaleHp(t.hp(), difficulty);
        return Enemy.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).name(name)
                .maxHp(hp).currentHp(hp).armorClass(t.armorClass())
                .attackBonus(t.attackBonus() + atkDelta).damageDice(t.damageDice())
                .attacksPerTurn(1)
                .initiative(d20 + t.dexMod()).dexMod(t.dexMod()).alive(true)
                .build();
    }

    /* ── stat helpers (load from the Character template) ─────────── */

    private Character character(Player player) {
        return player.getCharacterId() == null ? null
                : characterRepository.findById(player.getCharacterId()).orElse(null);
    }

    private int dexMod(Player player) {
        Character c = character(player);
        return c == null ? 0 : Math.floorDiv(c.getDexterity() - 10, 2);
    }

    /** Scale an enemy's base HP by difficulty (EASY 0.75×, NORMAL 1×, DEADLY 1.4×), min 1. */
    private int scaleHp(int baseHp, Difficulty difficulty) {
        double factor = switch (difficulty) {
            case EASY -> 0.75;
            case NORMAL -> 1.0;
            case DEADLY -> 1.4;
        };
        return Math.max(1, (int) Math.round(baseHp * factor));
    }

    /** Difficulty adjustment to enemy attack bonus (EASY −1, NORMAL 0, DEADLY +2). */
    private int attackBonusDelta(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> -1;
            case NORMAL -> 0;
            case DEADLY -> 2;
        };
    }

    private int armorClass(Player player) {
        Character c = character(player);
        return c == null ? 10 : c.getArmorClass();
    }

    /** Attack bonus = best of STR/DEX modifier + proficiency bonus. */
    private int attackBonus(Player player) {
        Character c = character(player);
        if (c == null) return 2;
        int str = Math.floorDiv(c.getStrength() - 10, 2);
        int dex = Math.floorDiv(c.getDexterity() - 10, 2);
        return Math.max(str, dex) + c.getProficiencyBonus();
    }

    /** Simplified weapon damage: 1d8 + best STR/DEX modifier. */
    private String damageDice(Player player) {
        Character c = character(player);
        int mod = 0;
        if (c != null) {
            mod = Math.max(
                    Math.floorDiv(c.getStrength() - 10, 2),
                    Math.floorDiv(c.getDexterity() - 10, 2));
        }
        return "1d8" + (mod > 0 ? "+" + mod : mod < 0 ? String.valueOf(mod) : "");
    }

    private String notation(int bonus) {
        return "1d20" + (bonus > 0 ? "+" + bonus : bonus < 0 ? String.valueOf(bonus) : "");
    }

    /* ── DTO + broadcast ─────────────────────────────────────────── */

    private CombatStateDto toStateDto(CombatEncounter enc) {
        List<EnemyDto> enemies = enemyRepository.findBySessionId(enc.getSessionId()).stream()
                .map(e -> new EnemyDto(e.getId(), e.getName(), e.getMaxHp(),
                        e.getCurrentHp(), e.getArmorClass(), e.isAlive()))
                .toList();
        List<Combatant> order = enc.getInitiativeOrder();
        Combatant active = (enc.getStatus() == CombatStatus.ACTIVE
                && enc.getActiveIndex() < order.size())
                ? order.get(enc.getActiveIndex()) : null;
        return new CombatStateDto(
                enc.getId(), enc.getStatus(), enc.getRound(),
                enc.getActiveIndex(), active, order, enemies);
    }

    private void broadcast(UUID sessionId, Object event) {
        messagingTemplate.convertAndSend("/topic/game/" + sessionId, event);
    }

    /* ── combat narration beats ──────────────────────────────────── */

    /**
     * Persist the accumulated mechanical summary of one combat beat as a TurnEvent (fast,
     * in this transaction) and fire a {@link CombatNarrationEvent} so the DM narration is
     * streamed off-thread. The mechanical results have already been broadcast as instant
     * modals; this never blocks combat on the LLM.
     */
    private void flushBeat(UUID sessionId, UUID playerId, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (playerId == null) {
            // No player to attribute the row to (turn_events.player_id is non-null). A real
            // encounter always has a player; skip narration rather than fail the transaction.
            log.warn("Skipping combat narration beat — no player in session={}", sessionId);
            return;
        }
        String summary = String.join(" ", lines);
        TurnEvent beat = turnService.createCombatBeat(sessionId, playerId, summary);
        eventProducer.sendCombatNarration(new CombatNarrationEvent(
                sessionId, beat.getId(), beat.getTurnNumber(), summary));
    }

    /** One mechanical attack line: "X hits Y (attack 18 vs AC 15) for 7 damage (Y now 4/11 HP)." */
    private String describeAttack(String attacker, String target, DiceRollResult atk,
                                  int targetAc, boolean hit, RollSummary dmg,
                                  int targetHp, int targetMax, boolean defeated) {
        if (!hit) {
            String why = atk.fumble() ? " (a fumble)" : "";
            return attacker + " attacks " + target + " but misses" + why
                    + " (attack " + atk.total() + " vs AC " + targetAc + ").";
        }
        String crit = atk.crit() ? "a critical hit — " : "";
        String head = attacker + " hits " + target + " (" + crit + "attack " + atk.total()
                + " vs AC " + targetAc + ") for " + dmg.total() + " damage";
        if (defeated) {
            return head + ", defeating " + target + ".";
        }
        return head + " (" + target + " now at " + targetHp + "/" + targetMax + " HP).";
    }

    private String describeItemUse(String actor, PlayerStateService.ItemUseResult r) {
        if (r.kind() == ItemKind.POTION_HEALING && r.healed() > 0) {
            return actor + " uses " + r.itemName() + ", recovering " + r.healed()
                    + " HP (now " + r.state().currentHp() + "/" + r.state().maxHp() + " HP).";
        }
        return actor + " uses " + r.itemName() + ".";
    }

    private String roster(List<Enemy> enemies) {
        return enemies.stream().map(Enemy::getName).collect(Collectors.joining(", "));
    }

    /** Any real player in the session — used to attribute start/end beats that have no actor. */
    private UUID anyPlayerId(UUID sessionId) {
        return playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .map(Player::getId)
                .findFirst()
                .orElse(null);
    }
}
