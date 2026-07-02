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
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** DC of the Wisdom (Medicine) check to stabilize a dying creature. */
    private static final int STABILIZE_DC = 10;

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
        // Overlay this session's homebrew stat blocks (from its world) on top of the SRD catalogue.
        List<MonsterTemplate> sessionCustom = monsterResolver.customTemplates(sessionId);
        List<Enemy> enemies = new ArrayList<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String key : enemyKeys) {
            long sameType = enemyKeys.stream().filter(k -> k.equalsIgnoreCase(key)).count();
            int n = counts.merge(key.toLowerCase(), 1, Integer::sum);
            enemies.add(EnemyFactory.buildEnemy(monsterCatalog, sessionCustom, diceService, sessionId,
                    key, sameType > 1 ? n : 0, difficulty));
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
        DiceRollResult dmg = diceService.roll(
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
                && !containsIgnoreCase(state.knownSpells(), spellName)) {
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
                        spellLevel, effectiveTargets, results);
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
        // Generous cap: besides enemy/player turns, a dying player rolls a death save each round and
        // resolves (3 successes/failures, or a nat-20 revive) within a handful of rounds.
        int maxSteps = Math.max(1, order) * 8 + 16;

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

            Combatant active = enc.getInitiativeOrder().get(enc.getActiveIndex());

            if (active.kind() == CombatantKind.ENEMY) {
                Enemy e = enemyRepository.findById(active.refId()).orElse(null);
                if (e == null || !e.isAlive()) {
                    advanceIndex(enc);
                    continue;
                }
                expireEnemyConditions(enc, e, beat);          // timer + "save ends" at turn start
                resetTurnFlags(enc, e.getId().toString());
                if (ConditionRules.incapacitated(e.getConditions())) {
                    beat.add(e.getName() + " is " + CombatNarrationFormatter.incapacitatingLabel(e.getConditions()) + " and can't act.");
                    broadcastAction(enc, CombatantKind.ENEMY, e.getName(), "HOLD", "is incapacitated", List.of());
                    advanceIndex(enc);
                    continue;
                }
                enemyAct(enc, e, beat);
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

        boolean moved = dest != null && (dest.x() != ox || dest.y() != oy);
        if (moved) {
            enemyTok.setX(dest.x());
            enemyTok.setY(dest.y());
            beat.add(CombatNarrationFormatter.enemyMoveLine(enemy, intent, target.player().getCharacterName()));
            // A standalone MOVE event so the client animates the reposition before any attack.
            broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "MOVE", "moves", List.of());
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
        UUID sessionId = enc.getSessionId();
        List<MonsterAttack> atks = enemy.getAttacks();
        int attackBonus = (atks.isEmpty() ? enemy.getAttackBonus() : atks.get(0).toHit())
                + ConditionRules.attackModifier(enemy.getConditions());
        String damageDice = atks.isEmpty() ? enemy.getDamageDice() : atks.get(0).damageDice();
        int swings = Math.max(1, enemy.getAttacksPerTurn());

        List<CombatActionEvent.Target> results = new ArrayList<>();
        for (int s = 0; s < swings; s++) {
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

            RollSummary damageSummary = null;
            int targetHp = target.state().currentHp();
            int targetMax = target.state().maxHp();
            boolean defeated = false;
            if (hit) {
                DiceRollResult dmg = rollExpr(crit ? CombatMath.critDouble(damageDice) : damageDice);
                PlayerRuntimeStateDto updated =
                        playerStateService.applyHpDelta(victim.getId(), -dmg.total(), crit);
                broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
                targetHp = updated.currentHp();
                targetMax = updated.maxHp();
                defeated = updated.currentHp() <= 0;
                damageSummary = RollSummary.of(dmg);
                concentrationCheckOnDamage(enc, victim.getId(), dmg.total(), updated, beat);
                target = new TargetPlayer(victim, updated);  // refresh for the next swing
            }

            results.add(CombatActionEvent.Target.attack(CombatantKind.PLAYER, victim.getCharacterName(),
                    RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated));
            beat.add(CombatNarrationFormatter.describeAttack(enemy.getName(), victim.getCharacterName(), atk,
                    targetAc, hit, damageSummary, targetHp, targetMax, defeated));
        }

        broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "ATTACK", "attacks", results);
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
            PlayerRuntimeStateDto updated =
                    playerStateService.applyHpDelta(victim.getId(), -dmg.total(), crit);
            broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            targetHp = updated.currentHp();
            targetMax = updated.maxHp();
            defeated = updated.currentHp() <= 0;
            damageSummary = RollSummary.of(dmg);
            concentrationCheckOnDamage(enc, victim.getId(), dmg.total(), updated, beat);
        }

        broadcastAction(enc, CombatantKind.ENEMY, enemy.getName(), "ATTACK", "makes an opportunity attack",
                List.of(CombatActionEvent.Target.attack(CombatantKind.PLAYER, victim.getCharacterName(),
                        RollSummary.of(atk), targetAc, hit, damageSummary, targetHp, targetMax, defeated)));
        beat.add(CombatNarrationFormatter.describeAttack(enemy.getName(), victim.getCharacterName(), atk, targetAc, hit,
                damageSummary, targetHp, targetMax, defeated));
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
        return CombatMath.armorClass(character(player), playerConds(player.getId()));
    }

    /** Attack bonus = best of STR/DEX modifier + proficiency bonus. */
    private int attackBonus(Player player) {
        return CombatMath.attackBonus(character(player));
    }

    /** The player's basic-attack range in feet, inferred from their weapon items by name. */
    private int attackRangeFeet(Player player) {
        try {
            return CombatMath.attackRangeFeet(playerStateService.getState(player.getId()).inventory());
        } catch (RuntimeException ex) {
            return GridService.FEET_PER_SQUARE;
        }
    }

    /** Weapon damage = the equipped weapon's die (by name) + best STR/DEX modifier; 1d4 unarmed. */
    private String damageDice(Player player) {
        List<InventoryItem> inv;
        try {
            inv = playerStateService.getState(player.getId()).inventory();
        } catch (RuntimeException ex) {
            inv = null;
        }
        return CombatMath.damageDice(character(player), inv);
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
