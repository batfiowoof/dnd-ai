package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.kafka.event.CombatNarrationEvent;
import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.CombatActionEvent;
import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.CombatLifecycleEvent;
import com.dungeon.master.model.dto.CombatStateDto;
import com.dungeon.master.model.dto.DiceRollEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.MonsterAction;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.ReactionPromptEvent;
import com.dungeon.master.model.dto.RollSummary;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.ReactionChoice;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.ai.EnemyTacticsService;
import com.dungeon.master.service.ai.EnemyTacticsService.EnemyIntent;
import com.dungeon.master.service.ai.EnemyTacticsService.EnemyTactic;
import com.dungeon.master.service.ai.EnemyTacticsService.TargetInfo;
import com.dungeon.master.service.ai.SceneGenerator;
import com.dungeon.master.service.ai.SpellCatalog;
import com.dungeon.master.service.game.combat.CombatBroadcaster;
import com.dungeon.master.service.game.combat.CombatLookups;
import com.dungeon.master.service.game.combat.CombatMapper;
import com.dungeon.master.service.game.combat.CombatMath;
import com.dungeon.master.service.game.combat.CombatSpellResolver;
import com.dungeon.master.service.game.combat.CombatNarrationFormatter;
import com.dungeon.master.service.game.combat.CombatTerrainService;
import com.dungeon.master.service.game.combat.EnemyFactory;
import com.dungeon.master.service.game.combat.MonsterActionRules;
import com.dungeon.master.service.game.combat.WeaponMasteryRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final MonsterCatalog monsterCatalog;
    private final MonsterResolver monsterResolver;
    private final SpellCatalog spellCatalog;
    private final GridService gridService;
    private final CheckModifierService checkModifierService;
    private final SceneGenerator sceneGenerator;
    private final EnemyTacticsService enemyTacticsService;
    private final CombatMapper combatMapper;
    private final CombatBroadcaster combatBroadcaster;
    private final CombatTerrainService combatTerrainService;
    private final CombatLookups combatLookups;
    private final CombatSpellResolver combatSpellResolver;
    private final WeaponMasteryRules weaponMasteryRules;
    private final MagicItemEffects magicItemEffects;
    private final ReactionWindow reactionWindow;
    private final FeatEffects featEffects;

    /** DC of the Wisdom (Medicine) check to stabilize a dying creature. */
    private static final int STABILIZE_DC = 10;

    /** Seconds a player has to answer a reaction prompt before it auto-declines. */
    private static final int REACTION_WINDOW_SECONDS = 15;

    /** Elemental damage types Absorb Elements can resist. */
    private static final Set<String> ABSORB_TYPES =
            Set.of("acid", "cold", "fire", "lightning", "thunder");

    /**
     * Enemy attacks paused mid-multiattack while the victim decides whether to cast a reaction spell.
     * Keyed by encounter id; the held swing is pre-rolled so {@code applyReaction} resolves it
     * deterministically and then finishes the enemy's remaining swings.
     */
    private final Map<UUID, PendingReaction> pendingReactions = new ConcurrentHashMap<>();

    private record PendingReaction(UUID enemyId, UUID victimId, int attackBonus, String damageDice,
                                   String damageType, int swings, int swingIndex, DiceRollResult atk,
                                   DiceRollResult pendingDamage, boolean crit, int targetAc,
                                   List<CombatActionEvent.Target> results, UUID promptId) {}

    /** Control-flow signal thrown to unwind the enemy turn engine when a reaction prompt opens. */
    private static final class ReactionPause extends RuntimeException {
        ReactionPause() {
            super(null, null, false, false); // lightweight — no message, no stack trace
        }
    }

    /**
     * Weapon hits whose damage the player hasn't rolled yet (two-phase attack). Keyed by
     * {@code encounterId:playerId}; the to-hit is resolved on the attack, the damage on the
     * player's "Roll damage" (phase 2) so DM narration only fires once the damage is known.
     */
    private final Map<String, PendingAttack> pendingAttacks = new ConcurrentHashMap<>();
    /** Damaging spells whose to-hit/saves are resolved but whose damage the player hasn't rolled. */
    private final Map<String, CombatSpellResolver.PendingSpell> pendingSpells = new ConcurrentHashMap<>();

    private record PendingAttack(UUID enemyId, DiceRollResult atk, int targetAc) {}

    private static String pendingKey(CombatEncounter enc, Player player) {
        return enc.getId() + ":" + player.getId();
    }

    /* ── reads ───────────────────────────────────────────────────── */

    public Optional<CombatStateDto> getActiveCombat(UUID sessionId) {
        return encounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)
                .map(this::toStateDto);
    }

    /* ── start ───────────────────────────────────────────────────── */

    /** Start an encounter that is not fought in any creature's lair. */
    @Transactional
    public void startEncounter(UUID sessionId, List<String> enemyKeys) {
        startEncounter(sessionId, enemyKeys, false);
    }

    /**
     * @param inLair the host fought this encounter in the monster's lair, so lair-capable enemies
     *               carry their lair actions and the engine fires one each round on initiative 20
     */
    @Transactional
    public void startEncounter(UUID sessionId, List<String> enemyKeys, boolean inLair) {
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
        // Overlay this session's homebrew stat blocks (from its world) on top of the SRD catalogue.
        List<MonsterTemplate> sessionCustom = monsterResolver.customTemplates(sessionId);
        List<Enemy> enemies = new ArrayList<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String key : enemyKeys) {
            long sameType = enemyKeys.stream().filter(k -> k.equalsIgnoreCase(key)).count();
            int n = counts.merge(key.toLowerCase(), 1, Integer::sum);
            enemies.add(EnemyFactory.buildEnemy(monsterCatalog, sessionCustom, diceService, sessionId,
                    key, sameType > 1 ? n : 0, difficulty, inLair));
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
            // Alert (2024): add the Proficiency Bonus to the initiative roll.
            int init = diceService.roll("1d20").total() + dex + featEffects.initiativeBonus(character(p));
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

        // Lay out the tactical grid. The SceneGenerator asks the LLM (when AI combat is enabled)
        // for a battlefield grounded in the world/recent events, and ALWAYS falls back to a default
        // open arena on any failure — so combat is guaranteed a grid with every token placed.
        List<String> playerRefIds = order.stream()
                .filter(c -> c.kind() == CombatantKind.PLAYER)
                .map(c -> c.refId().toString())
                .toList();
        List<String> enemyRefIds = enemies.stream().map(e -> e.getId().toString()).toList();
        GridState grid = sceneGenerator.generateScene(sessionId, playerRefIds, enemyRefIds);

        CombatEncounter enc = CombatEncounter.builder()
                .sessionId(sessionId)
                .status(CombatStatus.ACTIVE)
                .initiativeOrder(order)
                .activeIndex(0)
                .round(1)
                .gridState(grid)
                .build();
        enc = encounterRepository.save(enc);

        broadcast(sessionId, CombatLifecycleEvent.start(sessionId, toStateDto(enc)));
        log.info("Combat started: session={}, enemies={}, combatants={}",
                sessionId, enemies.size(), order.size());

        List<String> beat = new ArrayList<>();
        beat.add("Combat begins! The party faces " + CombatNarrationFormatter.roster(enemies)
                + ". Initiative is rolled.");
        resolveUntilPlayerOrEnd(enc, beat);
        flushBeat(sessionId, anyPlayerId(sessionId), beat);
    }

    /* ── player actions ──────────────────────────────────────────── */

    @Transactional
    public void playerAttack(UUID sessionId, String username, UUID targetEnemyId) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        requireActionAvailable(enc, player);

        Enemy enemy = enemyRepository.findById(targetEnemyId)
                .filter(e -> e.getSessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Enemy not found"));
        if (!enemy.isAlive()) {
            throw new IllegalStateException(enemy.getName() + " is already defeated");
        }

        Token attackerTok = tokenFor(enc, player.getId().toString());
        Token defenderTok = tokenFor(enc, enemy.getId().toString());
        // Enforce weapon range on a tactical grid: a melee weapon can't reach across the map.
        if (enc.getGridState() != null && attackerTok != null && defenderTok != null) {
            int dist = gridService.distanceFeet(attackerTok.getX(), attackerTok.getY(),
                    defenderTok.getX(), defenderTok.getY());
            int range = attackRangeFeet(player);
            if (dist > range) {
                throw new IllegalStateException(enemy.getName() + " is out of range ("
                        + dist + " ft away, your weapon reaches " + range + " ft)");
            }
        }
        boolean melee = isMelee(attackerTok, defenderTok, enc.getGridState());
        int attackBonus = attackBonus(player) + ConditionRules.attackModifier(playerConds(player.getId()));
        RollMode mode = playerVsEnemyMode(player, enemy, defenderTok, melee);
        DiceRollResult atk = rollAttack(attackBonus, mode);
        int targetAc = effectiveAc(enemy.getArmorClass(), attackerTok, defenderTok, enc.getGridState());
        // A natural 1 always misses; auto-crit (vs a paralyzed/unconscious enemy) only upgrades a
        // landed hit, and crit carries no extra weight against an enemy here, so it doesn't gate the hit.
        boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);

        markAction(enc, player);                 // spend the action (turn doesn't end yet)

        if (!hit) {
            // A miss resolves immediately — there's no damage to roll, so narrate now.
            broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK", "attacks",
                    List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                            RollSummary.of(atk), targetAc, false, null,
                            enemy.getCurrentHp(), enemy.getMaxHp(), false)));
            List<String> beat = new ArrayList<>();
            beat.add(CombatNarrationFormatter.describeAttack(player.getCharacterName(), enemy.getName(), atk,
                    targetAc, false, null, enemy.getCurrentHp(), enemy.getMaxHp(), false));
            // Graze mastery: a Greatsword/Glaive still deals its ability modifier in damage on a miss.
            beat.addAll(weaponMasteryRules.applyOnMiss(player, character(player), enemy, safeInventory(player)));
            maybeAutoEndTurn(enc, player, beat);
            flushBeat(sessionId, player.getId(), beat);
            return;
        }

        // HIT → phase 1: announce the hit but hold the damage for the player to roll (phase 2).
        // No narration yet — it fires from resolveAttackDamage once the damage is known.
        pendingAttacks.put(pendingKey(enc, player), new PendingAttack(enemy.getId(), atk, targetAc));
        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK", "attacks",
                List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                        RollSummary.of(atk), targetAc, true, null,
                        enemy.getCurrentHp(), enemy.getMaxHp(), false)),
                true);
    }

    /**
     * Phase 2 of an attack OR a damaging spell: the player rolled their damage, so resolve the
     * held hit — the server rolls the authoritative damage, applies it, broadcasts the reveal,
     * and fires the (now damage-aware) DM narration.
     */
    @Transactional
    public void resolvePlayerDamage(UUID sessionId, String username) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        List<String> beat = resolvePendingDamage(enc, sessionId, player);
        if (beat.isEmpty()) {
            throw new IllegalStateException("No attack is waiting for a damage roll");
        }
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /**
     * Resolve whichever pending damage the player holds (a weapon hit or a damaging spell),
     * broadcasting the reveal and returning the narration beat. Returns an empty list when the
     * player has no pending damage (so callers can distinguish "nothing to resolve").
     */
    private List<String> resolvePendingDamage(CombatEncounter enc, UUID sessionId, Player player) {
        String key = pendingKey(enc, player);
        PendingAttack weapon = pendingAttacks.remove(key);
        if (weapon != null) {
            return applyPendingDamage(enc, sessionId, player, weapon);
        }
        CombatSpellResolver.PendingSpell spell = pendingSpells.remove(key);
        if (spell != null) {
            log.info("[two-phase] SPELL DAMAGE (phase 2) — applying {}: session={}, player={}",
                    spell.effect().name(), sessionId, player.getCharacterName());
            List<CombatActionEvent.Target> reveal = new ArrayList<>();
            List<String> beat = combatSpellResolver.applySpellDamage(enc, player, spell, reveal);
            broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(),
                    "SPELL_DAMAGE", "casts " + spell.effect().name(), reveal);
            return beat;
        }
        return new ArrayList<>();
    }

    /**
     * Roll + apply a pending weapon hit's damage and broadcast the reveal (no attack roll —
     * the d20 was already shown in phase 1). Returns the mechanical beat line(s) for narration;
     * the caller decides whether to advance the turn / flush.
     */
    private List<String> applyPendingDamage(CombatEncounter enc, UUID sessionId, Player player,
                                            PendingAttack pending) {
        Enemy enemy = enemyRepository.findById(pending.enemyId())
                .filter(e -> e.getSessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Enemy not found"));

        String dmgDice = damageDice(player);
        DiceRollResult dmg = rollWeaponDamage(enc, player,
                pending.atk().crit() ? CombatMath.critDouble(dmgDice) : dmgDice);
        enemy.setCurrentHp(Math.max(0, enemy.getCurrentHp() - dmg.total()));
        boolean defeated = false;
        if (enemy.getCurrentHp() == 0) {
            enemy.setAlive(false);
            defeated = true;
        }
        enemyRepository.save(enemy);
        RollSummary damageSummary = RollSummary.of(dmg);

        // Damage reveal — attackRoll null so the client skips a second d20 and rolls the damage.
        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK", "attacks",
                List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                        null, pending.targetAc(), true, damageSummary,
                        enemy.getCurrentHp(), enemy.getMaxHp(), defeated)));

        List<String> beat = new ArrayList<>();
        beat.add(CombatNarrationFormatter.describeAttack(player.getCharacterName(), enemy.getName(),
                pending.atk(), pending.targetAc(), true, damageSummary,
                enemy.getCurrentHp(), enemy.getMaxHp(), defeated));

        // 2024 weapon mastery: a landed martial hit applies its weapon's mastery effect (prone,
        // forced movement, advantage/disadvantage, cleave, etc.) using the equipped weapon.
        beat.addAll(weaponMasteryRules.applyOnHit(enc, player, character(player), enemy,
                safeInventory(player), dmg.total()));
        return beat;
    }

    @Transactional
    public void playerUseItem(UUID sessionId, String username, String itemName) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        requireActionAvailable(enc, player);

        PlayerStateService.ItemUseResult result = playerStateService.useItem(player.getId(), itemName);
        broadcast(sessionId, PlayerStateEvent.of(sessionId, result.state()));

        markAction(enc, player);
        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ITEM",
                "uses " + result.itemName(),
                List.of(CombatActionEvent.Target.heal(CombatantKind.PLAYER, player.getCharacterName(),
                        result.healed(), result.state().currentHp(), result.state().maxHp())));

        List<String> beat = new ArrayList<>();
        beat.add(CombatNarrationFormatter.describeItemUse(player.getCharacterName(), result));
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /**
     * Cast a prepared spell on the player's combat turn. The spell's machine-readable
     * effect (from {@link SpellCatalog}) decides resolution: DAMAGE rolls a spell attack
     * vs AC or a save vs the caster's spell DC; HEAL restores ally HP; everything else is
     * applied as a (mostly narrative) condition/buff. Leveled spells spend a slot; cantrips
     * are free. {@code targetIds} are enemy ids (offensive) or player ids (heal/buff).
     *
     * <p>{@code originX}/{@code originY} are the optional grid cast point for an area-of-effect
     * spell. When the spell has an AoE template AND an origin AND a grid are present, the
     * affected enemy targets are derived authoritatively from the template (overriding any
     * client-sent {@code targetIds}); otherwise the {@code targetIds} are used as before.</p>
     */
    @Transactional
    public void playerCastSpell(UUID sessionId, String username, String spellName,
                                int spellLevel, List<UUID> targetIds,
                                Integer originX, Integer originY) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        PlayerRuntimeStateDto state = playerStateService.getState(player.getId());
        if (!containsIgnoreCase(state.cantrips(), spellName)
                && !containsIgnoreCase(state.preparedSpells(), spellName)) {
            throw new IllegalStateException("You don't have " + spellName + " prepared");
        }

        SpellEffect effect = spellCatalog.effect(spellName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown spell: " + spellName));

        // A Bonus-Action spell spends the bonus action; everything else spends the action.
        if (effect.isBonusAction()) {
            requireBonusActionAvailable(enc, player);
        } else {
            requireActionAvailable(enc, player);
        }

        // Spend a slot for leveled spells (cantrips are free). Throws if none remain.
        if (spellLevel >= 1) {
            PlayerRuntimeStateDto after = playerStateService.useSpellSlot(player.getId(), spellLevel);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, after));
        }

        // For an offensive AoE with a cast point on the grid, the server resolves which enemies
        // are caught in the template — those replace any client-sent targetIds. Falls back to
        // targetIds for single-target spells, no origin, or a no-grid encounter.
        List<UUID> aoeIds = combatSpellResolver.isOffensive(effect)
                ? combatSpellResolver.aoeEnemyIds(enc, effect, originX, originY) : null;
        List<UUID> effectiveTargets = aoeIds != null ? aoeIds : targetIds;

        Character caster = character(player);
        List<CombatActionEvent.Target> results = new ArrayList<>();
        List<String> beat = new ArrayList<>();

        // Casting a new concentration spell ends the caster's previous one (clearing its conditions).
        if (effect.concentration()) {
            breakConcentration(enc, player.getId(), beat);
        }

        // For a DAMAGE spell, phase 1 only rolls to-hit/saves; the damage is held for the player
        // to roll (phase 2), mirroring weapon attacks. HEAL / EFFECT resolve in one shot.
        CombatSpellResolver.PendingSpell pendingSpell = null;
        String actionKind = switch (effect.effectType()) {
            case HEAL -> {
                combatSpellResolver.resolveHeal(sessionId, player, caster, effect, spellLevel,
                        targetIds, results, beat);
                yield "SPELL_HEAL";
            }
            case DAMAGE -> {
                pendingSpell = combatSpellResolver.resolveSpellToHit(enc, sessionId, player, caster, effect,
                        spellLevel, effectiveTargets, results, beat);
                yield "SPELL_DAMAGE";
            }
            default -> {
                combatSpellResolver.resolveSpellEffect(enc, sessionId, player, caster, effect,
                        effectiveTargets, results, beat);
                yield "SPELL_EFFECT";
            }
        };

        // Now that the spell resolved (and may have applied conditions), record the new concentration.
        if (effect.concentration()) {
            broadcast(sessionId, PlayerStateEvent.of(sessionId,
                    playerStateService.setConcentratingSpell(player.getId(), effect.name())));
        }

        // Spend the matching economy slot before broadcasting so the event carries the update.
        if (effect.isBonusAction()) {
            markBonusAction(enc, player);
        } else {
            markAction(enc, player);
        }

        // Terrain spells (Entangle, Web, …) stamp difficult terrain over their area for the duration.
        if (effect.hasTerrain()
                && combatTerrainService.stampTerrainZone(enc, effect, player, originX, originY)) {
            encounterRepository.save(enc);
        }

        String label = "casts " + effect.name();
        if (pendingSpell != null && pendingSpell.anyDamage()) {
            // Hold the spell's damage for the player to roll (phase 2) — no narration / turn
            // advance yet, mirroring a weapon hit.
            pendingSpells.put(pendingKey(enc, player), pendingSpell);
            log.info("[two-phase] SPELL HIT (phase 1) — holding damage, enemy HP unchanged: session={}, player={}, spell={}",
                    sessionId, player.getCharacterName(), effect.name());
            broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), actionKind, label, results, true);
            return;
        }
        if (pendingSpell != null) {
            // A damaging spell that hit no one (missed attack / fizzled AoE) — resolve now,
            // mirroring a weapon miss.
            List<CombatActionEvent.Target> reveal = new ArrayList<>();
            beat.addAll(combatSpellResolver.applySpellDamage(enc, player, pendingSpell, reveal));
            broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), actionKind, label, reveal);
        } else {
            broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), actionKind, label, results);
        }
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    @Transactional
    public void playerEndTurn(UUID sessionId, String username) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        List<String> beat = new ArrayList<>();
        // Safety: if they end the turn with unrolled damage (a weapon hit or a damaging spell),
        // resolve it first so the enemy isn't spared and the beat is still narrated.
        List<String> pendingBeat = resolvePendingDamage(enc, sessionId, player);
        if (!pendingBeat.isEmpty()) {
            beat.addAll(pendingBeat);
            beat.add(player.getCharacterName() + " ends their turn.");
        } else {
            beat.add(player.getCharacterName() + " holds their action and ends their turn.");
        }
        advanceTurn(enc, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /* ── tactical movement + action economy ──────────────────────── */

    /**
     * Move the active player's token to (x,y). Validates the path against the grid
     * (cost ≤ remaining movement budget = speed + dash − already used), provokes
     * opportunity attacks from any hostile that the mover leaves the reach of (unless
     * Disengaged), then broadcasts the refreshed combat state. Does NOT end the turn.
     */
    @Transactional
    public void playerMove(UUID sessionId, String username, int x, int y) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        GridState grid = enc.getGridState();
        if (grid == null) {
            throw new IllegalStateException("This encounter has no tactical grid");
        }
        String refId = player.getId().toString();
        Token token = grid.getTokens() == null ? null : grid.getTokens().get(refId);
        if (token == null) {
            throw new IllegalStateException("You have no position on the grid");
        }

        Character c = character(player);
        int speed = ExhaustionRules.effectiveSpeed(
                ConditionRules.effectiveSpeed(c != null ? c.getSpeed() : 30, playerConds(player.getId())),
                exhaustionLevel(player.getId()));
        int budget = speed + (token.isDashed() ? speed : 0) - token.getMovementUsedFeet();

        Integer cost = gridService.pathCostFeet(grid, token.getX(), token.getY(), x, y, refId);
        if (cost == null) {
            throw new IllegalArgumentException("That square is unreachable or blocked");
        }
        if (cost > budget) {
            throw new IllegalArgumentException(
                    "Not enough movement: needs " + cost + " ft but only " + budget + " ft remain");
        }

        // Snapshot hostiles currently threatening the mover (for opportunity attacks).
        List<OaThreat> threats = collectOaThreats(enc, player, token.getX(), token.getY());

        token.setX(x);
        token.setY(y);
        token.setMovementUsedFeet(token.getMovementUsedFeet() + cost);

        // Opportunity attacks: a threatening hostile that no longer has the mover in reach
        // (and still has its reaction) gets one swing — unless the mover Disengaged.
        List<String> beat = new ArrayList<>();
        if (!token.isDisengaged()) {
            for (OaThreat threat : threats) {
                if (withinReach(threat.attackerX(), threat.attackerY(), x, y, threat.reachFeet())) {
                    continue; // mover is still adjacent — no opportunity
                }
                Token atkTok = grid.getTokens().get(threat.refId());
                if (atkTok == null || !atkTok.isReactionAvailable()) {
                    continue; // reaction already spent this round
                }
                // If an earlier OA this move already dropped the mover, there is no longer a
                // valid target — don't waste the reaction (5e: a reaction needs its trigger).
                if (playerDown(player.getId())) {
                    continue;
                }
                atkTok.setReactionAvailable(false);
                resolveOpportunityAttack(enc, threat, player, beat);
            }
        }

        encounterRepository.save(enc);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /** Dash: doubles this turn's movement budget. */
    @Transactional
    public void playerDash(UUID sessionId, String username) {
        actionEconomy(sessionId, username, "dash", " takes the Dash action, doubling their movement.");
    }

    /** Disengage: movement this turn no longer provokes opportunity attacks. */
    @Transactional
    public void playerDisengage(UUID sessionId, String username) {
        actionEconomy(sessionId, username, "disengage", " takes the Disengage action.");
    }

    /** Dodge: attackers have disadvantage until the start of the dodger's next turn. */
    @Transactional
    public void playerDodge(UUID sessionId, String username) {
        actionEconomy(sessionId, username, "dodge", " takes the Dodge action.");
    }

    /** Shared body for Dash/Disengage/Dodge: flip the token flag, persist, refresh, narrate. */
    private void actionEconomy(UUID sessionId, String username, String which, String narration) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        Token token = requireToken(enc, player.getId().toString());
        if (token.isActionUsed()) {
            throw new IllegalStateException("You have already taken your action this turn");
        }
        switch (which) {
            case "dash" -> token.setDashed(true);
            case "disengage" -> token.setDisengaged(true);
            case "dodge" -> token.setDodging(true);
            default -> throw new IllegalArgumentException("Unknown action: " + which);
        }
        token.setActionUsed(true);
        encounterRepository.save(enc);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
        List<String> beat = new ArrayList<>();
        beat.add(player.getCharacterName() + narration);
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /* ── reactions (hold / ready) ────────────────────────────────── */

    /**
     * Toggle whether the active player holds their reaction for a spell. While held, the player will
     * NOT auto-take opportunity attacks, preserving the reaction for Shield / Absorb Elements. Set on
     * your turn; it carries through the following enemy turns and clears at your next turn start.
     */
    @Transactional
    public void playerHoldReaction(UUID sessionId, String username, boolean hold) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        Token token = requireToken(enc, player.getId().toString());
        token.setHoldingReaction(hold);
        encounterRepository.save(enc);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
    }

    /**
     * Ready an attack against an enemy: spends the action; the readied swing auto-fires as a
     * reaction when that enemy first comes within the player's reach/range this round. Basic Ready
     * only — no readied-spell targeting engine.
     */
    @Transactional
    public void playerReadyAction(UUID sessionId, String username, UUID targetEnemyId) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        requireActionAvailable(enc, player);
        Token token = requireToken(enc, player.getId().toString());
        Enemy enemy = enemyRepository.findById(targetEnemyId)
                .filter(e -> e.getSessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Enemy not found"));
        if (!enemy.isAlive()) {
            throw new IllegalStateException(enemy.getName() + " is already defeated");
        }
        token.setReadiedTargetEnemyId(targetEnemyId);
        markAction(enc, player);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
        List<String> beat = new ArrayList<>();
        beat.add(player.getCharacterName() + " readies an attack against " + enemy.getName() + ".");
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /* ── bonus actions ───────────────────────────────────────────── */

    /**
     * Off-hand (two-weapon) attack: swing the weapon equipped in the OFF_HAND slot at an enemy,
     * spending the <em>bonus</em> action instead of the action. Resolves to-hit and damage in one
     * shot; two-weapon damage adds no ability modifier (5e default).
     */
    @Transactional
    public void playerOffHandAttack(UUID sessionId, String username, UUID targetEnemyId) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        requireBonusActionAvailable(enc, player);

        List<InventoryItem> inv = playerStateService.getState(player.getId()).inventory();
        InventoryItem offHand = CombatMath.offHandWeapon(inv);
        if (offHand == null) {
            throw new IllegalStateException(
                    "You need a weapon equipped in your off hand to make an off-hand attack.");
        }

        Enemy enemy = enemyRepository.findById(targetEnemyId)
                .filter(e -> e.getSessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Enemy not found"));
        if (!enemy.isAlive()) {
            throw new IllegalStateException(enemy.getName() + " is already defeated");
        }

        Token attackerTok = tokenFor(enc, player.getId().toString());
        Token defenderTok = tokenFor(enc, enemy.getId().toString());
        if (enc.getGridState() != null && attackerTok != null && defenderTok != null) {
            int dist = gridService.distanceFeet(attackerTok.getX(), attackerTok.getY(),
                    defenderTok.getX(), defenderTok.getY());
            int range = CombatMath.attackRangeFeet(List.of(offHand));
            if (dist > range) {
                throw new IllegalStateException(enemy.getName() + " is out of range ("
                        + dist + " ft away, your off-hand weapon reaches " + range + " ft)");
            }
        }

        boolean melee = isMelee(attackerTok, defenderTok, enc.getGridState());
        int attackBonus = attackBonus(player) + ConditionRules.attackModifier(playerConds(player.getId()));
        RollMode mode = playerVsEnemyMode(player, enemy, defenderTok, melee);
        DiceRollResult atk = rollAttack(attackBonus, mode);
        int targetAc = effectiveAc(enemy.getArmorClass(), attackerTok, defenderTok, enc.getGridState());
        boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);

        markBonusAction(enc, player);

        RollSummary damageSummary = null;
        int targetHp = enemy.getCurrentHp();
        int targetMax = enemy.getMaxHp();
        boolean defeated = false;
        if (hit) {
            String dice = CombatMath.offHandDamageDice(offHand);
            DiceRollResult dmg = diceService.roll(atk.crit() ? CombatMath.critDouble(dice) : dice);
            enemy.setCurrentHp(Math.max(0, enemy.getCurrentHp() - dmg.total()));
            if (enemy.getCurrentHp() == 0) {
                enemy.setAlive(false);
                defeated = true;
            }
            enemyRepository.save(enemy);
            targetHp = enemy.getCurrentHp();
            damageSummary = RollSummary.of(dmg);
        }

        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK",
                "makes an off-hand attack",
                List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                        RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated)));
        List<String> beat = new ArrayList<>();
        beat.add(CombatNarrationFormatter.describeAttack(player.getCharacterName(), enemy.getName(), atk,
                targetAc, hit, damageSummary, targetHp, targetMax, defeated));
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /**
     * Second Wind (Fighter): as a bonus action, regain 1d10 + level hit points. Not yet limited to
     * once per rest — the mechanical heal lands now; the per-rest gate can be tightened later.
     */
    @Transactional
    public void playerSecondWind(UUID sessionId, String username) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        requireBonusActionAvailable(enc, player);

        Character c = character(player);
        if (c == null || !"fighter".equalsIgnoreCase(c.getCharacterClass())) {
            throw new IllegalStateException("Only Fighters can use Second Wind.");
        }

        int heal = diceService.roll("1d10").total() + Math.max(1, c.getLevel());
        PlayerRuntimeStateDto updated = playerStateService.applyHpDelta(player.getId(), heal, false);
        broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));

        markBonusAction(enc, player);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
        List<String> beat = new ArrayList<>();
        beat.add(player.getCharacterName() + " uses Second Wind, recovering " + heal + " hit points.");
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /**
     * Cunning Action (Rogue): Dash, Disengage, or Hide as a bonus action. Dash/Disengage set the
     * matching token flag; Hide is DM-narrated (no hidden state is modelled yet).
     */
    @Transactional
    public void playerCunningAction(UUID sessionId, String username, String which) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);
        requireBonusActionAvailable(enc, player);

        Character c = character(player);
        if (c == null || !"rogue".equalsIgnoreCase(c.getCharacterClass())) {
            throw new IllegalStateException("Only Rogues can use Cunning Action.");
        }

        Token token = requireToken(enc, player.getId().toString());
        String narration;
        switch (which == null ? "" : which.toLowerCase(Locale.ROOT)) {
            case "dash" -> {
                token.setDashed(true);
                narration = " uses Cunning Action to Dash.";
            }
            case "disengage" -> {
                token.setDisengaged(true);
                narration = " uses Cunning Action to Disengage.";
            }
            case "hide" -> narration = " uses Cunning Action to Hide.";
            default -> throw new IllegalArgumentException("Unknown cunning action: " + which);
        }
        token.setBonusActionUsed(true);
        encounterRepository.save(enc);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
        List<String> beat = new ArrayList<>();
        beat.add(player.getCharacterName() + narration);
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
    }

    /**
     * Spend the active player's action attempting to stabilize a dying ally with a DC 10
     * Wisdom (Medicine) check. Rolls authoritatively, broadcasts the dice + a narration beat, and on
     * success marks the target stable (no more death saves). Consumes the actor's action and turn.
     */
    @Transactional
    public void playerStabilize(UUID sessionId, String username, UUID targetPlayerId) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player actor = requireCombatTurn(enc, sessionId, username);
        requireActionAvailable(enc, actor);

        PlayerRuntimeStateDto targetState = playerStateService.getState(targetPlayerId);
        String targetName = playerName(targetPlayerId);
        if (targetState.dead() || targetState.currentHp() > 0 || targetState.stable()) {
            throw new IllegalStateException(targetName + " doesn't need stabilizing");
        }

        int mod = checkModifierService.computeModifier(actor, "WIS", "Medicine");
        DiceRollResult roll = diceService.roll(notation(mod));
        broadcast(sessionId, DiceRollEvent.of(sessionId, actor.getId(), actor.getCharacterName(),
                "WIS (Medicine) check", roll));
        boolean success = roll.total() >= STABILIZE_DC;

        List<String> beat = new ArrayList<>();
        if (success) {
            PlayerRuntimeStateDto updated = playerStateService.stabilize(targetPlayerId);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            beat.add(actor.getCharacterName() + " stabilizes " + targetName + " (Medicine "
                    + roll.total() + " vs DC " + STABILIZE_DC + ").");
        } else {
            beat.add(actor.getCharacterName() + " fails to stabilize " + targetName + " (Medicine "
                    + roll.total() + " vs DC " + STABILIZE_DC + ").");
        }

        // The attempt is the actor's action (grid-gated where a token exists).
        markAction(enc, actor);
        maybeAutoEndTurn(enc, actor, beat);
        flushBeat(sessionId, actor.getId(), beat);
    }

    @Transactional
    public void endEncounterByHost(UUID sessionId) {
        CombatEncounter enc = activeEncounter(sessionId);
        List<String> beat = new ArrayList<>();
        endEncounter(enc, allEnemiesDead(sessionId), beat);
        flushBeat(sessionId, anyPlayerId(sessionId), beat);
    }

    /**
     * Set (or replace) the battle-map background image on the active encounter's grid and
     * broadcast the refreshed state so every client re-renders. Throws if there is no active
     * encounter (a map can only be set during combat) or the encounter has no tactical grid.
     */
    @Transactional
    public void setMapBackground(UUID sessionId, String url) {
        CombatEncounter enc = activeEncounter(sessionId);
        GridState grid = enc.getGridState();
        if (grid == null) {
            throw new IllegalStateException("This encounter has no tactical grid");
        }
        grid.setBackgroundImageUrl(url);
        encounterRepository.save(enc);
        broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
    }

    /* ── turn engine ─────────────────────────────────────────────── */

    /**
     * End the active player's turn. Legendary creatures spend legendary actions here — at the end of
     * a hero's turn, before the initiative pointer moves — which is the only place a monster acts
     * outside its own slot. Every caller of this method is downstream of {@link #requireCombatTurn},
     * so the combatant at {@code activeIndex} is always the player whose turn is ending.
     */
    private void advanceTurn(CombatEncounter enc, List<String> beat) {
        runLegendaryActions(enc, beat);
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
        // Generous cap: besides enemy/player turns, a dying player rolls a death save each round and
        // resolves (3 successes/failures, or a nat-20 revive) within a handful of rounds. The lair
        // action costs at most one extra step per round.
        int maxSteps = Math.max(1, order) * 8 + 24;

        // Where a lair action resolves in the order (initiative count 20), and whether this fight has
        // a lair at all — enemies only carry lair actions when the host started the encounter in one.
        int lairSlot = MonsterActionRules.lairSlot(enc.getInitiativeOrder());
        boolean lairPossible = lairSlot >= 0
                && enemyRepository.findBySessionId(sessionId).stream().anyMatch(Enemy::hasLair);

        for (int step = 0; step < maxSteps; step++) {
            expireTerrainZones(enc);                    // drop timed spell terrain whose duration lapsed
            if (allEnemiesDead(sessionId)) {           // guard (a): victory
                endEncounter(enc, true, beat);
                return;
            }
            if (partyFullyDown(sessionId)) {           // guard (b): defeat — no one left to save
                endEncounter(enc, false, beat);
                return;
            }

            // Lair action on initiative count 20, once per round. Stamping the round BEFORE resolving
            // makes this idempotent across the reaction-window pause/resume cycle (which re-enters
            // this loop), and `continue` re-checks the guards in case the lair just wiped the party.
            if (lairPossible && enc.getRound() > enc.getLairActionRound()
                    && enc.getActiveIndex() >= lairSlot) {
                enc.setLairActionRound(enc.getRound());
                runLairAction(enc, beat);
                continue;
            }

            Combatant active = enc.getInitiativeOrder().get(enc.getActiveIndex());

            if (active.kind() == CombatantKind.ENEMY) {
                Enemy e = enemyRepository.findById(active.refId()).orElse(null);
                if (e == null || !e.isAlive()) {
                    advanceIndex(enc);
                    continue;
                }
                expireEnemyConditions(enc, e, beat);          // timer + "save ends" at turn start
                resetTurnFlags(enc, e.getId().toString());
                refillLegendaryActions(e);                    // a legendary creature regains its budget
                if (ConditionRules.incapacitated(e.getConditions())) {
                    beat.add(e.getName() + " is " + CombatNarrationFormatter.incapacitatingLabel(e.getConditions()) + " and can't act.");
                    broadcastAction(enc, CombatantKind.ENEMY, e.getName(), "HOLD", "is incapacitated", List.of());
                    advanceIndex(enc);
                    continue;
                }
                try {
                    enemyAct(enc, e, beat);
                } catch (ReactionPause pause) {
                    // A player is deciding whether to react to this enemy's attack. Persist the
                    // paused state and stop here WITHOUT advancing — applyReaction resumes the turn.
                    encounterRepository.save(enc);
                    broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
                    return;
                }
                advanceIndex(enc);
                continue;
            }

            // PLAYER turn.
            PlayerRuntimeStateDto state = safeState(active.refId());
            if (state == null || state.dead() || state.stable()) {
                advanceIndex(enc);                     // out of the fight — skip
                continue;
            }
            if (state.currentHp() <= 0) {
                // Dying: their turn is spent rolling a death save. A nat-20 revive (REVIVED) puts
                // them back on their feet at 1 HP, so they take a normal turn; otherwise advance.
                PlayerStateService.DeathSaveOutcome outcome = rollDeathSave(enc, active, beat);
                if (outcome != PlayerStateService.DeathSaveOutcome.REVIVED) {
                    advanceIndex(enc);
                    continue;
                }
            }
            // Expire timer-based conditions at this player's turn start, then skip if incapacitated.
            PlayerRuntimeStateDto expired = playerStateService.expireConditions(active.refId(), enc.getRound());
            if (expired != null) {
                broadcast(sessionId, PlayerStateEvent.of(sessionId, expired));
            }
            List<ActiveCondition> pConds = playerConds(active.refId());
            if (ConditionRules.incapacitated(pConds)) {
                beat.add(playerName(active.refId()) + " is " + CombatNarrationFormatter.incapacitatingLabel(pConds) + " and can't act.");
                broadcastAction(enc, CombatantKind.PLAYER, playerName(active.refId()),
                        "HOLD", "is incapacitated", List.of());
                advanceIndex(enc);
                continue;
            }
            resetTurnFlags(enc, active.refId().toString());
            encounterRepository.save(enc);
            broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
            return;
        }

        // Safety net — should be unreachable given the guards above.
        log.warn("Combat step cap hit for session={}, ending encounter", sessionId);
        endEncounter(enc, allEnemiesDead(sessionId), beat);
    }

    /**
     * Resolve one enemy's turn. Dispatches on whether the encounter is a tactical grid fight:
     * with a grid (and a token for this enemy) the enemy moves with intent before attacking
     * ({@link #enemyActOnGrid}); without one (legacy / unit tests) it keeps the classic
     * pick-target-and-swing behaviour with no movement.
     */
    private void enemyAct(CombatEncounter enc, Enemy enemy, List<String> beat) {
        Token enemyTok = tokenFor(enc, enemy.getId().toString());
        if (enc.getGridState() != null && enemyTok != null) {
            enemyActOnGrid(enc, enemy, enemyTok, beat);
            return;
        }
        TargetPlayer target = pickTarget(enc.getSessionId());
        if (target == null) {                          // guard (c): no valid target
            return;
        }
        resolveMultiattack(enc, enemy, target, beat);
    }

    /**
     * Grid-aware enemy turn: pick a target + intent (LLM-directed when AI combat is enabled,
     * else deterministic), move the enemy token toward an intent-appropriate square within its
     * speed, then attack ONLY if a valid target ended up within the chosen attack's reach/range.
     *
     * <p><b>Known limitation:</b> a moving enemy does NOT provoke player opportunity attacks
     * (player→enemy OAs are out of scope this phase); only enemy→player OAs (Phase B) exist.</p>
     */
    private void enemyActOnGrid(CombatEncounter enc, Enemy enemy, Token enemyTok, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        GameSession session = sessionRepository.findById(sessionId).orElse(null);

        List<TargetInfo> infos = consciousTargets(enc);
        if (infos.isEmpty()) {                         // no one left to fight
            return;
        }

        // 1. Decide target + intent. decide() falls back to a deterministic default internally
        //    when AI combat is disabled or the LLM call fails — it never throws.
        EnemyTactic tactic = enemyTacticsService.decide(session, enc, enemy, infos);
        EnemyIntent intent = tactic.intent() == null ? EnemyIntent.ENGAGE_MELEE : tactic.intent();
        TargetPlayer target = resolveTarget(tactic.targetName(), infos);
        if (target == null) {
            target = pickTarget(sessionId);            // hardened fallback
            if (target == null) {
                return;
            }
        }

        // 2. Move toward an intent-appropriate square within the enemy's speed budget.
        Token targetTok = tokenFor(enc, target.player().getId().toString());
        GridState grid = enc.getGridState();
        String refId = enemy.getId().toString();
        int budget = Math.max(0, ConditionRules.effectiveSpeed(enemy.getSpeed(), enemy.getConditions()));
        int meleeReach = CombatMath.enemyReachFeet(enemy);
        int bestRange = enemyBestRange(enemy);
        int ox = enemyTok.getX();
        int oy = enemyTok.getY();

        GridService.Square dest = switch (intent) {
            case ENGAGE_MELEE, FOCUS_CASTER -> targetTok == null ? null
                    : gridService.approachSquare(grid, refId, targetTok.getX(), targetTok.getY(), budget, meleeReach);
            case KITE_RANGED -> targetTok == null ? null
                    : gridService.kiteSquare(grid, refId, targetTok.getX(), targetTok.getY(), budget,
                            bestRange > 0 ? bestRange : meleeReach);
            case FLEE -> gridService.fleeSquare(grid, refId, threatSquares(enc), budget);
            case HOLD -> null;
        };

        // Snapshot which players threaten the enemy from its CURRENT square, so that stepping out
        // of their reach can provoke a (auto-resolved) player opportunity attack — hybrid model.
        List<PlayerOaThreat> playerThreats = collectPlayerOaThreats(enc, ox, oy);

        boolean moved = dest != null && (dest.x() != ox || dest.y() != oy);
        if (moved) {
            enemyTok.setX(dest.x());
            enemyTok.setY(dest.y());
            beat.add(CombatNarrationFormatter.enemyMoveLine(enemy, intent, target.player().getCharacterName()));
            // A standalone MOVE event so the client animates the reposition before any attack.
            broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "MOVE", "moves", List.of());

            // Opportunity attacks: any snapshotted player who no longer has the moved enemy within
            // reach and still holds its reaction gets one swing.
            for (PlayerOaThreat threat : playerThreats) {
                if (!enemy.isAlive()) {
                    break;
                }
                if (withinReach(threat.playerX(), threat.playerY(),
                        enemyTok.getX(), enemyTok.getY(), threat.reachFeet())) {
                    continue; // enemy still adjacent — no opportunity
                }
                Token pt = tokenFor(enc, threat.refId());
                if (pt == null || !pt.isReactionAvailable()) {
                    continue;
                }
                pt.setReactionAvailable(false);
                resolvePlayerOpportunityAttack(enc, threat, enemy, beat);
            }
        }

        // A player who readied an attack against THIS enemy strikes as it comes within reach/range.
        fireReadiedAttacks(enc, enemy, enemyTok, beat);

        if (!enemy.isAlive()) {
            return; // dropped by an opportunity or readied attack — no turn left to take
        }

        // 3. Attack only if the (possibly moved) enemy is within the chosen attack's reach/range.
        int effRange = switch (intent) {
            case KITE_RANGED -> bestRange > 0 ? bestRange : meleeReach;
            case ENGAGE_MELEE, FOCUS_CASTER -> meleeReach;
            case HOLD -> Math.max(meleeReach, bestRange);
            case FLEE -> 0;                            // fleeing — no attack this turn
        };
        int dist = targetTok == null ? Integer.MAX_VALUE
                : gridService.distanceFeet(enemyTok.getX(), enemyTok.getY(), targetTok.getX(), targetTok.getY());

        if (intent != EnemyIntent.FLEE && targetTok != null && dist <= effRange) {
            resolveMultiattack(enc, enemy, target, beat);
        } else if (!moved) {
            // Didn't move and can't reach anyone — register the held turn so state still syncs.
            beat.add(enemy.getName() + (intent == EnemyIntent.FLEE
                    ? " hangs back, wary." : " holds, unable to reach a target."));
            broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "HOLD", "holds", List.of());
        }
    }

    /**
     * Multiattack swing loop shared by the grid and legacy paths: swing {@code attacksPerTurn}
     * times (re-acquiring a living target between swings), reporting all sub-attacks in a single
     * {@link CombatActionEvent} so the client animates them together. Uses the stat block's
     * primary attack, falling back to the legacy {@code attackBonus}/{@code damageDice}.
     */
    private void resolveMultiattack(CombatEncounter enc, Enemy enemy, TargetPlayer target, List<String> beat) {
        List<MonsterAttack> atks = enemy.getAttacks();
        int attackBonus = (atks.isEmpty() ? enemy.getAttackBonus() : atks.get(0).toHit())
                + ConditionRules.attackModifier(enemy.getConditions());
        String damageDice = atks.isEmpty() ? enemy.getDamageDice() : atks.get(0).damageDice();
        String damageType = atks.isEmpty() ? null : atks.get(0).damageType();
        int swings = Math.max(1, enemy.getAttacksPerTurn());
        runMultiattack(enc, enemy, target, attackBonus, damageDice, damageType, swings, 0,
                new ArrayList<>(), true, beat);
    }

    /**
     * The resumable multiattack swing loop. Runs swings {@code [startIndex, swings)} against
     * {@code target} (re-acquiring a living target between swings) into the shared {@code results}
     * list, then broadcasts one combined {@link CombatActionEvent}. When {@code allowReaction} is
     * true, the first hit that a reaction spell could affect pauses the whole engine (via
     * {@link ReactionPause}) after stashing a {@link PendingReaction}; {@code applyReaction} later
     * re-enters this method from {@code startIndex = swingIndex + 1} with {@code allowReaction=false}.
     */
    private void runMultiattack(CombatEncounter enc, Enemy enemy, TargetPlayer target,
                                int attackBonus, String damageDice, String damageType, int swings,
                                int startIndex, List<CombatActionEvent.Target> results,
                                boolean allowReaction, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        for (int s = startIndex; s < swings; s++) {
            if (target.state().currentHp() <= 0) {     // current target down — re-acquire
                target = pickTarget(sessionId);
                if (target == null) break;
            }
            Player victim = target.player();
            Token enemyTok = tokenFor(enc, enemy.getId().toString());
            Token victimTok = tokenFor(enc, victim.getId().toString());
            boolean melee = isMelee(enemyTok, victimTok, enc.getGridState());
            RollMode mode = enemyVsPlayerMode(enemy, victim.getId(), victimTok, melee);
            DiceRollResult atk = rollAttack(attackBonus, mode);
            int targetAc = effectiveAc(armorClass(victim), enemyTok, victimTok, enc.getGridState());
            boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);
            // A melee hit on a paralyzed/unconscious victim is an automatic critical (two death-save failures).
            boolean crit = atk.crit() || (hit && ConditionRules.autoCritMelee(playerConds(victim.getId()), melee));

            // Reaction interrupt: on the first eligible hit, pre-roll the damage, stash the paused
            // multiattack, prompt the victim, and unwind the engine until they answer.
            if (hit && allowReaction) {
                List<String> options = reactionSpellOptions(enc, victim, atk, targetAc, crit, damageType);
                if (!options.isEmpty()) {
                    DiceRollResult dmg = rollExpr(crit ? CombatMath.critDouble(damageDice) : damageDice);
                    UUID promptId = UUID.randomUUID();
                    pendingReactions.put(enc.getId(), new PendingReaction(enemy.getId(), victim.getId(),
                            attackBonus, damageDice, damageType, swings, s, atk, dmg, crit, targetAc,
                            results, promptId));
                    openReactionPrompt(enc, victim, enemy, damageType, options, promptId);
                    throw new ReactionPause();
                }
            }

            PlayerRuntimeStateDto post = applySwing(enc, enemy, victim, target.state(), atk, targetAc,
                    hit, crit, damageDice, damageType, null, results, beat);
            target = new TargetPlayer(victim, post);  // refresh for the next swing
        }

        broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "ATTACK", "attacks", results);
    }

    /**
     * Apply one resolved enemy swing to the victim: roll (or use pre-rolled) damage, halve it if
     * resisted, apply HP, run the concentration check, and append the swing to {@code results} +
     * {@code beat}. Does NOT broadcast — the caller emits the combined action once the loop ends.
     * Returns the victim's post-swing state (updated on a hit, else {@code preState} unchanged).
     */
    private PlayerRuntimeStateDto applySwing(CombatEncounter enc, Enemy enemy, Player victim,
                                             PlayerRuntimeStateDto preState, DiceRollResult atk,
                                             int targetAc, boolean hit, boolean crit, String damageDice,
                                             String damageType, DiceRollResult preRolledDamage,
                                             List<CombatActionEvent.Target> results, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        RollSummary damageSummary = null;
        int targetHp = preState.currentHp();
        int targetMax = preState.maxHp();
        boolean defeated = false;
        PlayerRuntimeStateDto post = preState;
        if (hit) {
            DiceRollResult dmg = preRolledDamage != null ? preRolledDamage
                    : rollExpr(crit ? CombatMath.critDouble(damageDice) : damageDice);
            int applied = resisted(dmg.total(), damageType, preState);
            if (applied < dmg.total()) {
                beat.add(victim.getCharacterName() + " resists " + damageType
                        + " damage (halved to " + applied + ").");
            }
            PlayerRuntimeStateDto updated = playerStateService.applyHpDelta(victim.getId(), -applied, crit);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            targetHp = updated.currentHp();
            targetMax = updated.maxHp();
            defeated = updated.currentHp() <= 0;
            damageSummary = RollSummary.of(dmg);
            concentrationCheckOnDamage(enc, victim.getId(), applied, updated, beat);
            post = updated;
        }
        results.add(CombatActionEvent.Target.attack(CombatantKind.PLAYER, victim.getCharacterName(),
                RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated));
        beat.add(CombatNarrationFormatter.describeAttack(enemy.getName(), victim.getCharacterName(), atk,
                targetAc, hit, damageSummary, targetHp, targetMax, defeated));
        return post;
    }

    /* ── reaction spells (Shield / Absorb Elements) ──────────────── */

    /** Which reaction spells the victim could cast against this hit (empty → resolve normally). */
    private List<String> reactionSpellOptions(CombatEncounter enc, Player victim, DiceRollResult atk,
                                              int targetAc, boolean crit, String damageType) {
        Token vt = tokenFor(enc, victim.getId().toString());
        if (vt == null || !vt.isReactionAvailable()) {
            return List.of();
        }
        if (ConditionRules.incapacitated(playerConds(victim.getId()))) {
            return List.of();
        }
        PlayerRuntimeStateDto st = safeState(victim);
        if (st == null) {
            return List.of();
        }
        List<String> opts = new ArrayList<>();
        // Shield: +5 AC could still turn this (non-crit) hit into a miss — only worth offering then.
        if (!crit && atk.total() >= targetAc && atk.total() < targetAc + 5
                && containsIgnoreCase(st.knownSpells(), "Shield") && hasSpellSlot(st, 1)) {
            opts.add("SHIELD");
        }
        // Absorb Elements: resist the triggering elemental damage type.
        if (damageType != null && ABSORB_TYPES.contains(damageType.toLowerCase(Locale.ROOT))
                && containsIgnoreCase(st.knownSpells(), "Absorb Elements") && hasSpellSlot(st, 1)) {
            opts.add("ABSORB");
        }
        return opts;
    }

    /** True when the player has an unspent spell slot of at least {@code minLevel}. */
    private boolean hasSpellSlot(PlayerRuntimeStateDto st, int minLevel) {
        return st.spellSlots() != null
                && st.spellSlots().stream().anyMatch(sl -> sl.level() >= minLevel && sl.used() < sl.max());
    }

    /** Persist the paused encounter, push the prompt to the victim, and arm the auto-decline timer. */
    private void openReactionPrompt(CombatEncounter enc, Player victim, Enemy enemy, String damageType,
                                    List<String> options, UUID promptId) {
        encounterRepository.save(enc);
        UUID sessionId = enc.getSessionId();
        ReactionPromptEvent event = ReactionPromptEvent.of(sessionId, promptId, enemy.getName(),
                damageType, options, REACTION_WINDOW_SECONDS);
        reactionWindow.open(sessionId, victim.getUsername(), event,
                () -> applyReaction(sessionId, promptId, ReactionChoice.DECLINE));
    }

    /**
     * A player's answer to a reaction prompt: validate ownership, cancel the auto-decline, and
     * resume the paused enemy turn under their choice.
     */
    @Transactional
    public void resolvePlayerReaction(UUID sessionId, String username, String choice) {
        CombatEncounter enc = activeEncounter(sessionId);
        PendingReaction pending = pendingReactions.get(enc.getId());
        if (pending == null) {
            throw new IllegalStateException("There is no reaction to make right now");
        }
        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException("You are not in this session"));
        if (!player.getId().equals(pending.victimId())) {
            throw new IllegalStateException("This reaction is not yours to make");
        }
        reactionWindow.cancel(sessionId);
        applyReaction(sessionId, pending.promptId(), parseChoice(choice));
    }

    private ReactionChoice parseChoice(String choice) {
        try {
            return choice == null ? ReactionChoice.DECLINE
                    : ReactionChoice.valueOf(choice.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ReactionChoice.DECLINE;
        }
    }

    /**
     * Resolve the held swing under {@code choice}, finish the enemy's remaining swings, then resume
     * the turn engine. Runs inside a transaction (the WS handler's, or the timer's own). Idempotent:
     * the {@link ConcurrentHashMap#remove} gate means a late auto-decline no-ops once answered.
     */
    private void applyReaction(UUID sessionId, UUID promptId, ReactionChoice choice) {
        CombatEncounter enc = activeEncounter(sessionId);
        PendingReaction pending = pendingReactions.remove(enc.getId());
        if (pending == null) {
            return; // already resolved (answer/timeout race)
        }
        if (!pending.promptId().equals(promptId)) {
            pendingReactions.put(enc.getId(), pending); // a newer prompt — leave it for its own answer
            return;
        }
        reactionWindow.cancel(sessionId);

        Enemy enemy = enemyRepository.findById(pending.enemyId()).orElse(null);
        Player victim = playerRepository.findById(pending.victimId()).orElse(null);
        List<String> beat = new ArrayList<>();
        if (enemy == null || victim == null) {
            advanceIndex(enc);                       // combatant vanished — just resume
            resolveUntilPlayerOrEnd(enc, beat);
            encounterRepository.save(enc);
            flushBeat(sessionId, anyPlayerId(sessionId), beat);
            return;
        }

        // Fall back to DECLINE if the chosen spell can no longer be paid for (slot spent meanwhile).
        PlayerRuntimeStateDto st = safeState(victim);
        ReactionChoice eff = choice;
        if ((eff == ReactionChoice.SHIELD || eff == ReactionChoice.ABSORB)
                && (st == null || !hasSpellSlot(st, 1))) {
            eff = ReactionChoice.DECLINE;
        }

        Token vt = tokenFor(enc, victim.getId().toString());
        DiceRollResult atk = pending.atk();
        int targetAc = pending.targetAc();
        boolean hit;
        switch (eff) {
            case SHIELD -> {
                spendReactionSpell(enc, victim);
                playerStateService.applyCondition(victim.getId(),
                        ActiveCondition.of(ConditionRules.SHIELDED).expiringAt(enc.getRound()));
                broadcast(sessionId, PlayerStateEvent.of(sessionId, playerStateService.getState(victim.getId())));
                if (vt != null) vt.setReactionAvailable(false);
                hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc + 5);
                beat.add(victim.getCharacterName() + " casts Shield (+5 AC)"
                        + (hit ? "." : ", turning the blow aside!"));
            }
            case ABSORB -> {
                spendReactionSpell(enc, victim);
                String type = pending.damageType() == null ? ""
                        : pending.damageType().toLowerCase(Locale.ROOT);
                playerStateService.applyCondition(victim.getId(),
                        ActiveCondition.of(ConditionRules.ABSORBING_PREFIX + type).expiringAt(enc.getRound()));
                broadcast(sessionId, PlayerStateEvent.of(sessionId, playerStateService.getState(victim.getId())));
                if (vt != null) vt.setReactionAvailable(false);
                hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);
                beat.add(victim.getCharacterName() + " casts Absorb Elements, resisting the " + type + " damage.");
            }
            default -> hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);
        }

        // Apply the held swing (pre-rolled damage; resisted() now sees any Absorb condition).
        PlayerRuntimeStateDto post = applySwing(enc, enemy, victim, playerStateService.getState(victim.getId()),
                atk, targetAc, hit, pending.crit(), pending.damageDice(), pending.damageType(),
                pending.pendingDamage(), pending.results(), beat);

        // Finish the enemy's remaining swings (no further prompts) and resume the engine.
        if (enemy.isAlive()) {
            TargetPlayer target = new TargetPlayer(victim, post);
            runMultiattack(enc, enemy, target, pending.attackBonus(), pending.damageDice(),
                    pending.damageType(), pending.swings(), pending.swingIndex() + 1,
                    pending.results(), false, beat);
        } else {
            broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "ATTACK", "attacks", pending.results());
        }
        advanceIndex(enc);
        resolveUntilPlayerOrEnd(enc, beat);
        encounterRepository.save(enc);
        flushBeat(sessionId, victim.getId(), beat);
    }

    /** Spend a 1st-level slot for a reaction spell and broadcast the updated slots. */
    private void spendReactionSpell(CombatEncounter enc, Player victim) {
        PlayerRuntimeStateDto after = playerStateService.useSpellSlot(victim.getId(), 1);
        broadcast(enc.getSessionId(), PlayerStateEvent.of(enc.getSessionId(), after));
    }

    /* ── grid-aware enemy AI helpers ─────────────────────────────── */

    /** Conscious player targets (HP > 0) with their grid positions, for tactics + movement. */
    private List<TargetInfo> consciousTargets(CombatEncounter enc) {
        UUID sessionId = enc.getSessionId();
        GridState grid = enc.getGridState();
        List<TargetInfo> out = new ArrayList<>();
        for (PlayerRuntimeStateDto s : playerStateService.getSessionStates(sessionId)) {
            if (s.currentHp() <= 0) {
                continue;
            }
            Optional<Player> p = playerRepository.findById(s.playerId());
            if (p.isEmpty() || p.get().getRole() != PlayerRole.PLAYER) {
                continue;
            }
            Token t = grid == null || grid.getTokens() == null ? null
                    : grid.getTokens().get(s.playerId().toString());
            int x = t != null ? t.getX() : -1;
            int y = t != null ? t.getY() : -1;
            out.add(new TargetInfo(p.get().getCharacterName(), s.playerId(),
                    s.currentHp(), s.maxHp(), x, y));
        }
        return out;
    }

    /** Resolve a tactic's chosen target name back to a live {@link TargetPlayer}, or null. */
    private TargetPlayer resolveTarget(String name, List<TargetInfo> infos) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (TargetInfo info : infos) {
            String tn = info.name() == null ? "" : info.name().toLowerCase(java.util.Locale.ROOT);
            if (tn.equals(n) || tn.startsWith(n + " ") || n.startsWith(tn + " ")) {
                Optional<Player> p = playerRepository.findById(info.playerId());
                if (p.isPresent()) {
                    return new TargetPlayer(p.get(), playerStateService.getState(info.playerId()));
                }
            }
        }
        return null;
    }

    /** Max ranged range (ft) across the enemy's attacks, or 0 if it has none. */
    private int enemyBestRange(Enemy e) {
        int range = 0;
        for (MonsterAttack a : e.getAttacks()) {
            if ("RANGED".equalsIgnoreCase(a.kind()) && a.range() != null) {
                range = Math.max(range, a.range());
            }
        }
        return range;
    }

    /** Grid squares of every conscious player — the threats a fleeing enemy runs from. */
    private List<GridService.Square> threatSquares(CombatEncounter enc) {
        List<GridService.Square> out = new ArrayList<>();
        GridState grid = enc.getGridState();
        if (grid == null || grid.getTokens() == null) {
            return out;
        }
        for (TargetInfo info : consciousTargets(enc)) {
            Token t = grid.getTokens().get(info.playerId().toString());
            if (t != null) {
                out.add(new GridService.Square(t.getX(), t.getY()));
            }
        }
        return out;
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
        // Combat over: drop every lingering condition + concentration so nothing leaks past the fight.
        for (PlayerRuntimeStateDto dto : playerStateService.clearConditionsAndConcentration(enc.getSessionId())) {
            broadcast(enc.getSessionId(), PlayerStateEvent.of(enc.getSessionId(), dto));
        }
        if (beat != null) {
            beat.add(victory
                    ? "The last foe falls — the party stands victorious."
                    : "The party is overwhelmed and falls...");
        }
        broadcast(enc.getSessionId(), CombatLifecycleEvent.end(enc.getSessionId(), victory, toStateDto(enc)));
        log.info("Combat ended: session={}, victory={}", enc.getSessionId(), victory);
    }

    /**
     * End a caster's concentration: clear their concentration flag and drop every
     * concentration-flagged condition they applied to enemies and players, broadcasting the
     * refreshed state for each affected player.
     */
    private void breakConcentration(CombatEncounter enc, UUID casterId, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        boolean any = false;
        for (Enemy e : enemyRepository.findBySessionId(sessionId)) {
            if (e.getConditions().removeIf(c -> c.concentration() && casterId.equals(c.sourceCasterId()))) {
                enemyRepository.save(e);
                any = true;
            }
        }
        List<PlayerRuntimeStateDto> changed = playerStateService.breakConcentration(sessionId, casterId);
        for (PlayerRuntimeStateDto dto : changed) {
            broadcast(sessionId, PlayerStateEvent.of(sessionId, dto));
        }
        // Clear the caster's concentration terrain (e.g. Entangle) and refresh the grid.
        if (combatTerrainService.removeTerrainZones(enc,
                z -> z.concentration() && casterId.equals(z.sourceCasterId()))) {
            encounterRepository.save(enc);
            broadcast(sessionId, CombatLifecycleEvent.turn(sessionId, toStateDto(enc)));
        }
        if ((any || !changed.isEmpty()) && beat != null) {
            beat.add(playerName(casterId) + "'s concentration ends.");
        }
    }

    /**
     * 5E concentration check after a concentrating player takes damage: dropping to 0 HP ends it
     * outright; otherwise roll a Constitution save (DC = max(10, ⌊damage/2⌋)) and break it on a
     * failure. No-op when the player wasn't concentrating or took no damage.
     */
    private void concentrationCheckOnDamage(CombatEncounter enc, UUID victimId, int damage,
                                            PlayerRuntimeStateDto updated, List<String> beat) {
        if (damage <= 0 || updated.concentratingSpell() == null) {
            return;
        }
        if (updated.currentHp() <= 0) {
            breakConcentration(enc, victimId, beat);             // unconscious → concentration ends
            return;
        }
        int dc = Math.max(10, damage / 2);
        int conMod = CombatMath.abilityMod(updated, "CON");
        DiceRollResult save = diceService.roll(notation(conMod));
        broadcast(enc.getSessionId(), DiceRollEvent.of(enc.getSessionId(), victimId,
                playerName(victimId), "Concentration save (DC " + dc + ")", save));
        if (save.total() < dc) {
            if (beat != null) {
                beat.add(playerName(victimId) + " loses concentration on " + updated.concentratingSpell()
                        + " (CON save " + save.total() + " vs DC " + dc + ").");
            }
            breakConcentration(enc, victimId, beat);
        }
    }

    /**
     * At an enemy's turn start, drop its timer-expired conditions and re-roll any "save ends"
     * conditions (success removes them). Persists when anything changed; the following enemy
     * action (or skip) broadcasts the refreshed state.
     */
    private void expireEnemyConditions(CombatEncounter enc, Enemy e, List<String> beat) {
        List<ActiveCondition> conds = e.getConditions();
        if (conds == null || conds.isEmpty()) {
            return;
        }
        int round = enc.getRound();
        boolean changed = conds.removeIf(c -> {
            if (c.expiresAtRound() != null && round > c.expiresAtRound()) {
                if (beat != null) beat.add(e.getName() + " is no longer " + c.name() + ".");
                return true;
            }
            return false;
        });
        List<ActiveCondition> shaken = new ArrayList<>();
        for (ActiveCondition c : conds) {
            if (c.saveAbility() != null && c.saveDc() != null) {
                int mod = CombatMath.enemySaveMod(e, c.saveAbility()) + ConditionRules.saveModifier(conds);
                DiceRollResult save = diceService.roll(notation(mod),
                        ConditionRules.saveMode(conds, c.saveAbility()));
                if (save.total() >= c.saveDc()) {
                    shaken.add(c);
                    if (beat != null) beat.add(e.getName() + " shakes off " + c.name() + ".");
                }
            }
        }
        if (!shaken.isEmpty()) {
            conds.removeAll(shaken);
            changed = true;
        }
        if (changed) {
            enemyRepository.save(e);
        }
    }

    /** At turn start, clear timer-expired terrain zones (persist + broadcast the refreshed grid). */
    private void expireTerrainZones(CombatEncounter enc) {
        int round = enc.getRound();
        if (combatTerrainService.removeTerrainZones(enc,
                z -> z.expiresAtRound() != null && round > z.expiresAtRound())) {
            encounterRepository.save(enc);
            broadcast(enc.getSessionId(), CombatLifecycleEvent.turn(enc.getSessionId(), toStateDto(enc)));
        }
    }

    /* ── spell helpers ───────────────────────────────────────────── */

    /** Roll a damage/heal expression that may be standard dice ("8d6+3") or a flat number ("1"). */
    private DiceRollResult rollExpr(String expr) {
        return CombatMath.rollExpr(diceService, expr);
    }

    private String playerName(UUID playerId) {
        return combatLookups.playerName(playerId);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        if (list == null || value == null) return false;
        return list.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(value));
    }

    private void broadcastAction(CombatEncounter enc, CombatantKind actorKind, String actorName,
                                 String actionKind, String label, List<CombatActionEvent.Target> targets) {
        combatBroadcaster.broadcastAction(enc, actorKind, actorName, actionKind, label, targets);
    }

    private void broadcastAction(CombatEncounter enc, CombatantKind actorKind, String actorName,
                                 String actionKind, String label, List<CombatActionEvent.Target> targets,
                                 boolean awaitingDamage) {
        combatBroadcaster.broadcastAction(enc, actorKind, actorName, actionKind, label, targets, awaitingDamage);
    }

    /* ── legendary & lair actions ────────────────────────────────── */

    /**
     * At the end of a hero's turn, every conscious legendary creature spends one legendary action.
     *
     * <p><b>Must never throw {@link ReactionPause}.</b> This runs from {@link #advanceTurn}, outside
     * the try/catch in {@link #resolveUntilPlayerOrEnd} that unwinds a paused enemy turn — so
     * legendary attacks resolve straight through {@link #applySwing} rather than
     * {@link #runMultiattack}, and never open a reaction window.
     */
    private void runLegendaryActions(CombatEncounter enc, List<String> beat) {
        List<Combatant> order = enc.getInitiativeOrder();
        if (order.isEmpty() || order.get(enc.getActiveIndex()).kind() != CombatantKind.PLAYER) {
            return;                                    // only at the end of a hero's turn
        }
        // Find the bosses first: the overwhelming majority of fights have none, and this spares
        // every ordinary turn-end the cost of a target lookup.
        List<Enemy> bosses = enemyRepository.findBySessionId(enc.getSessionId()).stream()
                .filter(e -> e.isAlive() && e.isLegendary() && e.getLegendaryActionsRemaining() > 0)
                .filter(e -> !ConditionRules.incapacitated(e.getConditions()))
                .toList();
        if (bosses.isEmpty() || pickTarget(enc.getSessionId()) == null) {
            return;                                    // no boss to act, or no conscious hero left
        }
        for (Enemy e : bosses) {
            MonsterAction action = MonsterActionRules.chooseLegendary(
                    e.getLegendaryActions(), e.getLegendaryActionsRemaining());
            if (action == null) {
                continue;                              // nothing affordable — the points go unspent
            }
            e.setLegendaryActionsRemaining(e.getLegendaryActionsRemaining() - action.pointCost());
            enemyRepository.save(e);
            resolveMonsterAction(enc, e, action, "LEGENDARY_ACTION", "uses " + action.name(), beat);
            if (partyFullyDown(enc.getSessionId())) {
                return;                                // resolveUntilPlayerOrEnd will end the fight
            }
        }
    }

    /** A legendary creature regains its full budget at the start of its own turn. */
    private void refillLegendaryActions(Enemy e) {
        if (e.isLegendary() && e.getLegendaryActionsRemaining() != e.getLegendaryActionMax()) {
            e.setLegendaryActionsRemaining(e.getLegendaryActionMax());
            enemyRepository.save(e);
        }
    }

    /**
     * Resolve this round's lair action on initiative count 20. Attributed to the toughest living
     * lair-holder, so the client sees the boss as the actor. Like {@link #runLegendaryActions}, this
     * must never throw {@link ReactionPause}.
     */
    private void runLairAction(CombatEncounter enc, List<String> beat) {
        Enemy owner = enemyRepository.findBySessionId(enc.getSessionId()).stream()
                .filter(Enemy::isAlive)
                .filter(Enemy::hasLair)
                .max(Comparator.comparingInt(Enemy::getMaxHp))
                .orElse(null);
        if (owner == null || pickTarget(enc.getSessionId()) == null) {
            return;                                    // the lair-holder fell, or nobody is left
        }
        MonsterAction action = MonsterActionRules.chooseLair(owner.getLairActions(), enc.getRound());
        if (action != null) {
            resolveMonsterAction(enc, owner, action, "LAIR_ACTION", "lair action: " + action.name(), beat);
        }
    }

    /** Resolve one legendary/lair action and broadcast it as a single combat action. */
    private void resolveMonsterAction(CombatEncounter enc, Enemy enemy, MonsterAction action,
                                      String actionKind, String label, List<String> beat) {
        List<CombatActionEvent.Target> results = new ArrayList<>();
        switch (action.kind()) {
            case ATTACK -> resolveMonsterAttackAction(enc, enemy, action, results, beat);
            case SAVE -> resolveMonsterSaveAction(enc, enemy, action, results, beat);
            case NARRATIVE -> {
                // The client builds one feed row per target, so a flavour beat still needs a row.
                results.add(CombatActionEvent.Target.effect(CombatantKind.ENEMY, enemy.getName(),
                        action.name(), enemy.getCurrentHp(), enemy.getMaxHp()));
                beat.add(monsterActionFlavour(enemy, action));
            }
        }
        if (!results.isEmpty()) {
            broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), actionKind, label, results);
        }
    }

    /**
     * An ATTACK-kind action swings one of the monster's own stat-block attacks at the most wounded
     * hero. Resolved through {@link #applySwing} directly — no reaction window (see
     * {@link #runLegendaryActions}).
     */
    private void resolveMonsterAttackAction(CombatEncounter enc, Enemy enemy, MonsterAction action,
                                            List<CombatActionEvent.Target> results, List<String> beat) {
        MonsterAttack atk = enemy.getAttacks().stream()
                .filter(a -> a.name() != null && a.name().equalsIgnoreCase(action.attackName()))
                .findFirst()
                .orElse(null);
        TargetPlayer target = pickTarget(enc.getSessionId());
        if (atk == null || target == null) {
            if (atk == null) {
                log.warn("Monster action {} on {} references unknown attack {}",
                        action.name(), enemy.getName(), action.attackName());
            }
            return;
        }
        Player victim = target.player();
        Token enemyTok = tokenFor(enc, enemy.getId().toString());
        Token victimTok = tokenFor(enc, victim.getId().toString());
        boolean melee = isMelee(enemyTok, victimTok, enc.getGridState());
        RollMode mode = enemyVsPlayerMode(enemy, victim.getId(), victimTok, melee);
        int attackBonus = atk.toHit() + ConditionRules.attackModifier(enemy.getConditions());
        DiceRollResult roll = rollAttack(attackBonus, mode);
        int targetAc = effectiveAc(armorClass(victim), enemyTok, victimTok, enc.getGridState());
        boolean hit = roll.crit() || (!roll.fumble() && roll.total() >= targetAc);
        boolean crit = roll.crit() || (hit && ConditionRules.autoCritMelee(playerConds(victim.getId()), melee));
        applySwing(enc, enemy, victim, target.state(), roll, targetAc, hit, crit,
                atk.damageDice(), atk.damageType(), null, results, beat);
    }

    /**
     * A SAVE-kind action forces every hero in the emanation (or the most wounded hero when the action
     * has no radius) to roll a saving throw: a failure takes full damage and gains the condition, a
     * success takes half when {@code halfOnSave} and nothing otherwise. The mirror image of
     * {@code CombatSpellResolver}'s player→enemy save path.
     */
    private void resolveMonsterSaveAction(CombatEncounter enc, Enemy enemy, MonsterAction action,
                                          List<CombatActionEvent.Target> results, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        String ability = action.saveAbility();
        int dc = action.saveDc() != null ? action.saveDc() : 13;

        for (TargetPlayer target : monsterSaveTargets(enc, enemy, action)) {
            Player victim = target.player();
            List<ActiveCondition> conds = playerConds(victim.getId());
            boolean autoFail = ConditionRules.autoFailsSave(conds, ability);
            int saveMod = checkModifierService.computeSaveModifier(victim, ability)
                    + ConditionRules.saveModifier(conds);
            DiceRollResult save = diceService.roll(notation(saveMod),
                    ConditionRules.saveMode(conds, ability));
            boolean saved = !autoFail && save.total() >= dc;

            PlayerRuntimeStateDto post = target.state();
            RollSummary damageSummary = null;
            int applied = 0;
            if (action.damageDice() != null) {
                DiceRollResult dmg = rollExpr(action.damageDice());
                int raw = saved ? (action.halfOnSave() ? dmg.total() / 2 : 0) : dmg.total();
                if (raw > 0) {
                    // The event carries the full roll (as save spells do); the beat states what landed.
                    damageSummary = RollSummary.of(dmg);
                    applied = resisted(raw, action.damageType(), post);
                    if (applied < raw) {
                        beat.add(victim.getCharacterName() + " resists " + action.damageType()
                                + " damage (halved to " + applied + ").");
                    }
                    post = playerStateService.applyHpDelta(victim.getId(), -applied, false);
                    broadcast(sessionId, PlayerStateEvent.of(sessionId, post));
                    concentrationCheckOnDamage(enc, victim.getId(), applied, post, beat);
                }
            }
            // A downed hero is already unconscious — don't stack a condition on top.
            if (!saved && action.condition() != null && post.currentHp() > 0) {
                ActiveCondition c = ActiveCondition.fromSpell(
                        action.condition(), null, action.name(), false);
                if (action.conditionRounds() != null) {
                    // expireConditions drops it once the round passes, so it lapses on their next turn.
                    c = c.expiringAt(enc.getRound() + Math.max(0, action.conditionRounds() - 1));
                }
                post = playerStateService.applyCondition(victim.getId(), c);
                broadcast(sessionId, PlayerStateEvent.of(sessionId, post));
            }

            results.add(CombatActionEvent.Target.save(CombatantKind.PLAYER, victim.getCharacterName(),
                    RollSummary.of(save), dc, saved, damageSummary,
                    post.currentHp(), post.maxHp(), post.currentHp() <= 0));
            beat.add(monsterSaveBeat(enemy, action, victim, save, dc, saved, applied, post));
        }
    }

    /** Heroes a SAVE-kind action reaches: everyone inside the emanation, else the most wounded one. */
    private List<TargetPlayer> monsterSaveTargets(CombatEncounter enc, Enemy enemy, MonsterAction action) {
        if (action.radiusFeet() == null) {
            TargetPlayer single = pickTarget(enc.getSessionId());
            return single == null ? List.of() : List.of(single);
        }
        Token source = tokenFor(enc, enemy.getId().toString());
        List<TargetPlayer> out = new ArrayList<>();
        for (TargetInfo info : consciousTargets(enc)) {
            // Off-grid (legacy) encounters have no geometry, so an emanation reaches the whole party.
            if (source != null && enc.getGridState() != null) {
                Token t = tokenFor(enc, info.playerId().toString());
                if (t == null || gridService.distanceFeet(source.getX(), source.getY(),
                        t.getX(), t.getY()) > action.radiusFeet()) {
                    continue;
                }
            }
            Player p = playerRepository.findById(info.playerId()).orElse(null);
            PlayerRuntimeStateDto st = safeState(info.playerId());
            if (p != null && st != null) {
                out.add(new TargetPlayer(p, st));
            }
        }
        return out;
    }

    private String monsterActionFlavour(Enemy enemy, MonsterAction action) {
        return action.description() != null && !action.description().isBlank()
                ? action.description()
                : enemy.getName() + " uses " + action.name() + ".";
    }

    private String monsterSaveBeat(Enemy enemy, MonsterAction action, Player victim,
                                   DiceRollResult save, int dc, boolean saved,
                                   int damageTaken, PlayerRuntimeStateDto post) {
        StringBuilder sb = new StringBuilder(enemy.getName())
                .append(" — ").append(action.name()).append(": ")
                .append(victim.getCharacterName()).append(" rolls ").append(save.total())
                .append(" vs DC ").append(dc)
                .append(saved ? " and resists" : " and is caught");
        if (damageTaken > 0) {
            sb.append(", taking ").append(damageTaken).append(' ')
                    .append(action.damageType() == null ? "damage"
                            : action.damageType().toLowerCase(Locale.ROOT) + " damage");
        }
        if (!saved && action.condition() != null && post.currentHp() > 0) {
            sb.append(" and is ").append(action.condition());
        }
        if (post.currentHp() <= 0) {
            sb.append(" and goes down");
        }
        return sb.append('.').toString();
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

    /**
     * The party is fully down — end in defeat — only when no player is conscious AND none is still
     * dying (every player is dead or stable). While at least one player is dying we keep the loop
     * going so their death saves can resolve.
     */
    private boolean partyFullyDown(UUID sessionId) {
        boolean anyConscious = false;
        boolean anyDying = false;
        for (PlayerRuntimeStateDto s : playerStateService.getSessionStates(sessionId)) {
            if (s.currentHp() > 0) {
                anyConscious = true;
            } else if (!s.dead() && !s.stable()) {
                anyDying = true;
            }
        }
        return !anyConscious && !anyDying;
    }

    private boolean playerDown(UUID playerId) {
        try {
            return playerStateService.getState(playerId).currentHp() <= 0;
        } catch (RuntimeException e) {
            return true; // no runtime state — treat as unavailable
        }
    }

    /** Current runtime state, or {@code null} when the player has no state (treat as out of the fight). */
    private PlayerRuntimeStateDto safeState(UUID playerId) {
        try {
            return playerStateService.getState(playerId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Roll one death saving throw for a dying player on their turn: broadcast the d20 (so the client
     * animates it like any other roll), record the result authoritatively, broadcast the new player
     * state, and append a narration beat. Returns the outcome so the caller can grant a turn on a
     * nat-20 revive.
     */
    private PlayerStateService.DeathSaveOutcome rollDeathSave(CombatEncounter enc, Combatant active,
                                                             List<String> beat) {
        UUID sessionId = enc.getSessionId();
        UUID pid = active.refId();
        DiceRollResult roll = diceService.roll("1d20");
        broadcast(sessionId, DiceRollEvent.of(sessionId, pid, active.name(), "Death Saving Throw", roll));
        PlayerStateService.DeathSaveResult result = playerStateService.recordDeathSave(pid, roll);
        broadcast(sessionId, PlayerStateEvent.of(sessionId, result.state()));
        beat.add(CombatNarrationFormatter.describeDeathSave(active.name(), roll, result));
        return result.outcome();
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

    /* ── grid / spatial helpers ──────────────────────────────────── */

    /** A combatant's token by refId (player/enemy UUID string), or {@code null} on a legacy/no-grid encounter. */
    private Token tokenFor(CombatEncounter enc, String refId) {
        return CombatMath.tokenFor(enc.getGridState(), refId);
    }

    /** Like {@link #tokenFor} but throws when the combatant has no grid position. */
    private Token requireToken(CombatEncounter enc, String refId) {
        Token t = tokenFor(enc, refId);
        if (t == null) {
            throw new IllegalStateException("No grid position for this combatant");
        }
        return t;
    }

    /**
     * Guard the action economy: an attack/cast/item is the player's action, so reject it if
     * they already spent their action this turn on Dash/Disengage/Dodge. No-ops on legacy
     * (no-grid) encounters where the player has no token.
     */
    private void requireActionAvailable(CombatEncounter enc, Player player) {
        Token t = tokenFor(enc, player.getId().toString());
        if (t != null && t.isActionUsed()) {
            throw new IllegalStateException("You have already taken your action this turn");
        }
    }

    /** Guard the bonus action (e.g. a Bonus-Action spell). No-op on legacy/no-token encounters. */
    private void requireBonusActionAvailable(CombatEncounter enc, Player player) {
        Token t = tokenFor(enc, player.getId().toString());
        if (t != null && t.isBonusActionUsed()) {
            throw new IllegalStateException("You have already used your bonus action this turn");
        }
    }

    /** Spend the player's action for the turn (persists). */
    private void markAction(CombatEncounter enc, Player player) {
        Token t = tokenFor(enc, player.getId().toString());
        if (t != null) {
            t.setActionUsed(true);
            encounterRepository.save(enc);
        }
    }

    /** Spend the player's bonus action for the turn (persists). */
    private void markBonusAction(CombatEncounter enc, Player player) {
        Token t = tokenFor(enc, player.getId().toString());
        if (t != null) {
            t.setBonusActionUsed(true);
            encounterRepository.save(enc);
        }
    }

    /**
     * After a non-turn-ending action, auto-advance ONLY when the player has nothing left to do —
     * action AND bonus action spent AND no movement remaining. Otherwise the turn stays theirs
     * (they end it explicitly with End Turn). On legacy/no-token encounters there is no economy to
     * track, so an action ends the turn as before.
     */
    private void maybeAutoEndTurn(CombatEncounter enc, Player player, List<String> beat) {
        Token t = tokenFor(enc, player.getId().toString());
        if (t == null) {
            advanceTurn(enc, beat);                       // legacy encounter — preserve old behaviour
            return;
        }
        Character c = character(player);
        int speed = ExhaustionRules.effectiveSpeed(
                ConditionRules.effectiveSpeed(c != null ? c.getSpeed() : 30, playerConds(player.getId())),
                exhaustionLevel(player.getId()));
        int budget = speed + (t.isDashed() ? speed : 0);
        boolean movementLeft = t.getMovementUsedFeet() < budget;
        if (t.isActionUsed() && t.isBonusActionUsed() && !movementLeft) {
            advanceTurn(enc, beat);
        }
    }

    /**
     * Defender AC including cover from intervening walls (base + 0/+2/+5). Returns the
     * base AC unchanged when either token or the grid is absent (legacy encounters).
     */
    private int effectiveAc(int baseAc, Token attacker, Token defender, GridState grid) {
        return CombatMath.effectiveAc(gridService, baseAc, attacker, defender, grid);
    }

    /** Roll a 1d20 attack with the given bonus and an explicit advantage/disadvantage mode. */
    private DiceRollResult rollAttack(int bonus, RollMode mode) {
        return CombatMath.rollAttack(diceService, bonus, mode);
    }

    /* ── condition-aware attack/melee helpers ────────────────────── */

    /** A player's structured conditions (empty when none). */
    private List<ActiveCondition> playerConds(UUID playerId) {
        return combatLookups.playerConds(playerId);
    }

    /** A player's current exhaustion level (0 when no runtime state), for enforcing 5e exhaustion effects. */
    private int exhaustionLevel(UUID playerId) {
        try {
            return playerStateService.getState(playerId).exhaustionLevel();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** True when two tokens are within 5 ft (one square); assumes melee when there is no grid. */
    private boolean isMelee(Token a, Token b, GridState grid) {
        return CombatMath.isMelee(gridService, a, b, grid);
    }

    /** Net attack roll mode for a player attacking an enemy (conditions + the target Dodging + exhaustion 3+). */
    private RollMode playerVsEnemyMode(Player attacker, Enemy defender, Token defenderTok, boolean melee) {
        return RollMode.combine(
                CombatMath.playerVsEnemyMode(playerConds(attacker.getId()), defender, defenderTok, melee),
                ExhaustionRules.attackAndSaveMode(exhaustionLevel(attacker.getId())));
    }

    /** Net attack roll mode for an enemy attacking a player (conditions + the target Dodging). */
    private RollMode enemyVsPlayerMode(Enemy attacker, UUID victimId, Token victimTok, boolean melee) {
        return CombatMath.enemyVsPlayerMode(attacker, playerConds(victimId), victimTok, melee);
    }

    /** Reset a combatant's per-turn action economy as their turn begins (Dodge from last round expires here). */
    private void resetTurnFlags(CombatEncounter enc, String refId) {
        Token t = tokenFor(enc, refId);
        if (t == null) {
            return;
        }
        t.setMovementUsedFeet(0);
        t.setDashed(false);
        t.setDisengaged(false);
        t.setReactionAvailable(true);
        t.setDodging(false);
        t.setActionUsed(false);
        t.setBonusActionUsed(false);
        t.setHoldingReaction(false);
        t.setReadiedTargetEnemyId(null);
        t.setSavageAttackerUsed(false);
    }

    /**
     * Roll a weapon-damage expression, applying the Savage Attacker feat: once per turn a wielder
     * with the feat rolls the damage dice twice and keeps the higher total. The once-per-turn gate
     * lives on the attacker's {@link Token} (reset each turn in {@link #resetTurnFlags}); a null token
     * (no grid position) simply skips the reroll.
     */
    private DiceRollResult rollWeaponDamage(CombatEncounter enc, Player player, String expr) {
        DiceRollResult first = rollExpr(expr);
        Token tok = tokenFor(enc, player.getId().toString());
        if (tok == null || tok.isSavageAttackerUsed() || !featEffects.hasSavageAttacker(character(player))) {
            return first;
        }
        tok.setSavageAttackerUsed(true);
        DiceRollResult second = rollExpr(expr);
        return second.total() > first.total() ? second : first;
    }

    /** A hostile that currently threatens the mover, captured before a move resolves opportunity attacks. */
    private record OaThreat(UUID enemyId, String refId, int attackerX, int attackerY, int reachFeet) {}

    /** Living, positioned enemies that have the mover within melee reach right now. */
    private List<OaThreat> collectOaThreats(CombatEncounter enc, Player mover, int moverX, int moverY) {
        List<OaThreat> threats = new ArrayList<>();
        GridState grid = enc.getGridState();
        if (grid == null || grid.getTokens() == null) {
            return threats;
        }
        for (Enemy e : enemyRepository.findBySessionId(enc.getSessionId())) {
            if (!e.isAlive()) {
                continue;
            }
            Token et = grid.getTokens().get(e.getId().toString());
            if (et == null) {
                continue;
            }
            int reach = CombatMath.enemyReachFeet(e);
            if (withinReach(et.getX(), et.getY(), moverX, moverY, reach)) {
                threats.add(new OaThreat(e.getId(), e.getId().toString(), et.getX(), et.getY(), reach));
            }
        }
        return threats;
    }

    private boolean withinReach(int ax, int ay, int bx, int by, int reachFeet) {
        return gridService.distanceFeet(ax, ay, bx, by) <= reachFeet;
    }

    /** One enemy opportunity-attack swing at a moving player (rolls vs AC w/ cover & dodge, applies HP). */
    private void resolveOpportunityAttack(CombatEncounter enc, OaThreat threat, Player victim, List<String> beat) {
        UUID sessionId = enc.getSessionId();
        Enemy enemy = enemyRepository.findById(threat.enemyId()).filter(Enemy::isAlive).orElse(null);
        if (enemy == null) {
            return;
        }
        PlayerRuntimeStateDto state = playerStateService.getState(victim.getId());
        if (state.currentHp() <= 0) {
            return; // already down — nothing to swing at
        }

        List<MonsterAttack> atks = enemy.getAttacks();
        int attackBonus = (atks.isEmpty() ? enemy.getAttackBonus() : atks.get(0).toHit())
                + ConditionRules.attackModifier(enemy.getConditions());
        String damageDice = atks.isEmpty() ? enemy.getDamageDice() : atks.get(0).damageDice();
        String damageType = atks.isEmpty() ? null : atks.get(0).damageType();

        Token enemyTok = tokenFor(enc, enemy.getId().toString());
        Token victimTok = tokenFor(enc, victim.getId().toString());
        // An opportunity attack is by definition a melee swing at an adjacent creature.
        RollMode mode = enemyVsPlayerMode(enemy, victim.getId(), victimTok, true);
        DiceRollResult atk = rollAttack(attackBonus, mode);
        int targetAc = effectiveAc(armorClass(victim), enemyTok, victimTok, enc.getGridState());
        boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);
        boolean crit = atk.crit() || (hit && ConditionRules.autoCritMelee(playerConds(victim.getId()), true));

        RollSummary damageSummary = null;
        int targetHp = state.currentHp();
        int targetMax = state.maxHp();
        boolean defeated = false;
        if (hit) {
            DiceRollResult dmg = rollExpr(damageDice);
            int applied = resisted(dmg.total(), damageType, state);
            if (applied < dmg.total()) {
                beat.add(victim.getCharacterName() + " resists " + damageType
                        + " damage (halved to " + applied + ").");
            }
            PlayerRuntimeStateDto updated =
                    playerStateService.applyHpDelta(victim.getId(), -applied, crit);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            targetHp = updated.currentHp();
            targetMax = updated.maxHp();
            defeated = updated.currentHp() <= 0;
            damageSummary = RollSummary.of(dmg);
            concentrationCheckOnDamage(enc, victim.getId(), applied, updated, beat);
        }

        broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "ATTACK", "makes an opportunity attack",
                List.of(CombatActionEvent.Target.attack(CombatantKind.PLAYER, victim.getCharacterName(),
                        RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated)));
        beat.add(CombatNarrationFormatter.describeAttack(enemy.getName(), victim.getCharacterName(), atk, targetAc, hit,
                damageSummary, targetHp, targetMax, defeated));
    }

    /* ── player→enemy opportunity attacks (auto-resolved) ────────── */

    /** A player who currently threatens an enemy, captured before the enemy moves. */
    private record PlayerOaThreat(UUID playerId, String refId, int playerX, int playerY, int reachFeet) {}

    /**
     * Living, positioned, conscious players who have the enemy within their melee reach right now
     * AND can still react — reaction available, not incapacitated, and not deliberately holding
     * their reaction for a spell. Snapshotted before an enemy moves so leaving reach can provoke.
     */
    private List<PlayerOaThreat> collectPlayerOaThreats(CombatEncounter enc, int enemyX, int enemyY) {
        List<PlayerOaThreat> threats = new ArrayList<>();
        GridState grid = enc.getGridState();
        if (grid == null || grid.getTokens() == null) {
            return threats;
        }
        for (PlayerRuntimeStateDto s : playerStateService.getSessionStates(enc.getSessionId())) {
            if (s.currentHp() <= 0) {
                continue;
            }
            Optional<Player> p = playerRepository.findById(s.playerId());
            if (p.isEmpty() || p.get().getRole() != PlayerRole.PLAYER) {
                continue;
            }
            Token pt = grid.getTokens().get(s.playerId().toString());
            if (pt == null || !pt.isReactionAvailable() || pt.isHoldingReaction()) {
                continue; // no token, reaction spent, or held for a spell
            }
            if (ConditionRules.incapacitated(playerConds(s.playerId()))) {
                continue; // can't take reactions
            }
            int reach = CombatMath.playerReachFeet(s.inventory());
            if (withinReach(pt.getX(), pt.getY(), enemyX, enemyY, reach)) {
                threats.add(new PlayerOaThreat(s.playerId(), s.playerId().toString(),
                        pt.getX(), pt.getY(), reach));
            }
        }
        return threats;
    }

    /**
     * One player opportunity-attack (or readied) swing at an enemy: rolls a main-hand attack vs the
     * enemy's AC (cover + dodge aware), applies enemy HP, broadcasts an ATTACK beat. Mirrors
     * {@link #playerOffHandAttack}'s enemy-HP path and {@link #resolveOpportunityAttack}'s broadcast.
     */
    private void resolvePlayerOpportunityAttack(CombatEncounter enc, PlayerOaThreat threat, Enemy enemy,
                                                List<String> beat) {
        if (!enemy.isAlive()) {
            return;
        }
        Player player = playerRepository.findById(threat.playerId()).orElse(null);
        if (player == null) {
            return;
        }
        Token attackerTok = tokenFor(enc, threat.refId());
        Token defenderTok = tokenFor(enc, enemy.getId().toString());
        boolean melee = isMelee(attackerTok, defenderTok, enc.getGridState());
        int attackBonus = attackBonus(player) + ConditionRules.attackModifier(playerConds(player.getId()));
        RollMode mode = playerVsEnemyMode(player, enemy, defenderTok, melee);
        DiceRollResult atk = rollAttack(attackBonus, mode);
        int targetAc = effectiveAc(enemy.getArmorClass(), attackerTok, defenderTok, enc.getGridState());
        boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);

        RollSummary damageSummary = null;
        int targetHp = enemy.getCurrentHp();
        int targetMax = enemy.getMaxHp();
        boolean defeated = false;
        if (hit) {
            String dice = damageDice(player);
            DiceRollResult dmg = rollWeaponDamage(enc, player, atk.crit() ? CombatMath.critDouble(dice) : dice);
            enemy.setCurrentHp(Math.max(0, enemy.getCurrentHp() - dmg.total()));
            if (enemy.getCurrentHp() == 0) {
                enemy.setAlive(false);
                defeated = true;
            }
            enemyRepository.save(enemy);
            targetHp = enemy.getCurrentHp();
            damageSummary = RollSummary.of(dmg);
        }

        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK",
                "makes an opportunity attack",
                List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                        RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated)));
        beat.add(CombatNarrationFormatter.describeAttack(player.getCharacterName(), enemy.getName(), atk,
                targetAc, hit, damageSummary, targetHp, targetMax, defeated));
    }

    /**
     * Fire any readied player attacks whose trigger — "this enemy comes within my reach/range" — is
     * now met after the enemy's movement. Reuses the single-shot opportunity-attack swing.
     */
    private void fireReadiedAttacks(CombatEncounter enc, Enemy enemy, Token enemyTok, List<String> beat) {
        GridState grid = enc.getGridState();
        if (grid == null || grid.getTokens() == null || enemyTok == null) {
            return;
        }
        for (PlayerRuntimeStateDto s : playerStateService.getSessionStates(enc.getSessionId())) {
            if (!enemy.isAlive()) {
                break;
            }
            if (s.currentHp() <= 0) {
                continue;
            }
            Token pt = grid.getTokens().get(s.playerId().toString());
            if (pt == null || !pt.isReactionAvailable()
                    || !enemy.getId().equals(pt.getReadiedTargetEnemyId())) {
                continue; // no token / reaction spent / not readied against this enemy
            }
            if (ConditionRules.incapacitated(playerConds(s.playerId()))) {
                continue;
            }
            int range = CombatMath.attackRangeFeet(s.inventory());
            if (gridService.distanceFeet(pt.getX(), pt.getY(),
                    enemyTok.getX(), enemyTok.getY()) > range) {
                continue; // enemy not yet within reach/range — keep the readied action waiting
            }
            pt.setReactionAvailable(false);
            pt.setReadiedTargetEnemyId(null);
            PlayerOaThreat threat = new PlayerOaThreat(s.playerId(), s.playerId().toString(),
                    pt.getX(), pt.getY(), range);
            resolvePlayerOpportunityAttack(enc, threat, enemy, beat);
        }
    }

    /* ── stat helpers (load from the Character template) ─────────── */

    private Character character(Player player) {
        return player.getCharacterId() == null ? null
                : characterRepository.findById(player.getCharacterId()).orElse(null);
    }

    private int dexMod(Player player) {
        return CombatMath.dexMod(character(player));
    }

    private int armorClass(Player player) {
        PlayerRuntimeStateDto st = safeState(player);
        List<InventoryItem> inv = st == null ? null : st.inventory();
        List<String> attuned = st == null ? List.of() : st.attunedItems();
        return CombatMath.armorClass(character(player), inv, playerConds(player.getId()))
                + magicItemEffects.acBonus(inv, attuned);
    }

    /**
     * Halve incoming damage of a type the victim resists (round down) — from magic items OR from a
     * condition (Absorb Elements' {@code absorbing-<type>}). Returns the (possibly reduced) amount to
     * apply. No-op when the damage type is unknown or unresisted.
     */
    private int resisted(int total, String damageType, PlayerRuntimeStateDto victimState) {
        if (total <= 0 || damageType == null || victimState == null) return total;
        Set<String> res = magicItemEffects.resistances(victimState.inventory(), victimState.attunedItems());
        if (res.stream().anyMatch(r -> r.equalsIgnoreCase(damageType))) {
            return total / 2;
        }
        Set<String> condRes = ConditionRules.resistances(playerConds(victimState.playerId()));
        return condRes.stream().anyMatch(r -> r.equalsIgnoreCase(damageType)) ? total / 2 : total;
    }

    /** The player's full runtime state, or {@code null} when none exists (e.g. tests). */
    private PlayerRuntimeStateDto safeState(Player player) {
        try {
            return playerStateService.getState(player.getId());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The attack/damage bonus from a set-ability magic item (Gauntlets of Ogre Power etc.): the
     * increase in the best-of-STR/DEX modifier once the item raises the relevant score. Based on the
     * character's own scores (the same source {@link CombatMath#attackBonus} uses) so it is purely
     * the item's contribution. Zero when no set-ability item is live.
     */
    private int statSetAttackDelta(Character c, List<InventoryItem> inv, List<String> attuned) {
        if (c == null) return 0;
        Map<String, Integer> base = new java.util.LinkedHashMap<>();
        base.put("STR", c.getStrength());
        base.put("DEX", c.getDexterity());
        Map<String, Integer> eff = magicItemEffects.effectiveAbilities(base, inv, attuned);
        int baseBest = Math.max(Math.floorDiv(base.get("STR") - 10, 2), Math.floorDiv(base.get("DEX") - 10, 2));
        int effBest = Math.max(Math.floorDiv(eff.get("STR") - 10, 2), Math.floorDiv(eff.get("DEX") - 10, 2));
        return effBest - baseBest;
    }

    /** The player's current inventory, or an empty list when no runtime state exists (e.g. tests). */
    private List<InventoryItem> safeInventory(Player player) {
        try {
            PlayerRuntimeStateDto st = playerStateService.getState(player.getId());
            return st == null || st.inventory() == null ? List.of() : st.inventory();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** Attack bonus = best of STR/DEX modifier + proficiency bonus, plus magic weapon/stat bonuses. */
    private int attackBonus(Player player) {
        PlayerRuntimeStateDto st = safeState(player);
        List<InventoryItem> inv = st == null ? null : st.inventory();
        List<String> attuned = st == null ? List.of() : st.attunedItems();
        Character c = character(player);
        return CombatMath.attackBonus(c)
                + magicItemEffects.attackBonus(inv, attuned)
                + statSetAttackDelta(c, inv, attuned);
    }

    /** The player's basic-attack range in feet, inferred from their weapon items by name. */
    private int attackRangeFeet(Player player) {
        try {
            return CombatMath.attackRangeFeet(playerStateService.getState(player.getId()).inventory());
        } catch (RuntimeException ex) {
            return GridService.FEET_PER_SQUARE;
        }
    }

    /**
     * Weapon damage = the equipped weapon's die (by name) + best STR/DEX modifier; 1d4 unarmed.
     * Folds a magic weapon's +N and any set-ability modifier delta into the flat term.
     */
    private String damageDice(Player player) {
        PlayerRuntimeStateDto st = safeState(player);
        List<InventoryItem> inv = st == null ? null : st.inventory();
        List<String> attuned = st == null ? List.of() : st.attunedItems();
        Character c = character(player);
        String base = CombatMath.damageDice(c, inv);
        int extra = magicItemEffects.damageBonus(inv, attuned) + statSetAttackDelta(c, inv, attuned);
        return CombatMath.addFlat(base, extra);
    }

    private String notation(int bonus) {
        return CombatMath.notation(bonus);
    }

    /* ── DTO + broadcast ─────────────────────────────────────────── */

    private CombatStateDto toStateDto(CombatEncounter enc) {
        return combatMapper.toStateDto(enc);
    }

    private void broadcast(UUID sessionId, Object event) {
        combatBroadcaster.broadcast(sessionId, event);
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

    /** Any real player in the session — used to attribute start/end beats that have no actor. */
    private UUID anyPlayerId(UUID sessionId) {
        return playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .map(Player::getId)
                .findFirst()
                .orElse(null);
    }
}
