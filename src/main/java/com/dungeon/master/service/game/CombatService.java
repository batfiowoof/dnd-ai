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
import com.dungeon.master.model.dto.EnemyDto;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.HealthBand;
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
import com.dungeon.master.service.ai.EnemyTacticsService;
import com.dungeon.master.service.ai.EnemyTacticsService.EnemyIntent;
import com.dungeon.master.service.ai.EnemyTacticsService.EnemyTactic;
import com.dungeon.master.service.ai.EnemyTacticsService.TargetInfo;
import com.dungeon.master.service.ai.SceneGenerator;
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
    private final GridService gridService;
    private final CheckModifierService checkModifierService;
    private final SceneGenerator sceneGenerator;
    private final EnemyTacticsService enemyTacticsService;

    /** DC of the Wisdom (Medicine) check to stabilize a dying creature. */
    private static final int STABILIZE_DC = 10;

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

        markAction(enc, player);                 // spend the action (turn doesn't end yet)
        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(), "ATTACK", "attacks",
                List.of(CombatActionEvent.Target.attack(CombatantKind.ENEMY, enemy.getName(),
                        RollSummary.of(atk), targetAc, hit, damageSummary,
                        enemy.getCurrentHp(), enemy.getMaxHp(), defeated)));

        List<String> beat = new ArrayList<>();
        beat.add(describeAttack(player.getCharacterName(), enemy.getName(), atk,
                targetAc, hit, damageSummary,
                enemy.getCurrentHp(), enemy.getMaxHp(), defeated));
        maybeAutoEndTurn(enc, player, beat);
        flushBeat(sessionId, player.getId(), beat);
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
        beat.add(describeItemUse(player.getCharacterName(), result));
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
        List<UUID> aoeIds = isOffensive(effect) ? aoeEnemyIds(enc, effect, originX, originY) : null;
        List<UUID> effectiveTargets = aoeIds != null ? aoeIds : targetIds;

        Character caster = character(player);
        List<CombatActionEvent.Target> results = new ArrayList<>();
        List<String> beat = new ArrayList<>();

        // Casting a new concentration spell ends the caster's previous one (clearing its conditions).
        if (effect.concentration()) {
            breakConcentration(enc, player.getId(), beat);
        }

        String actionKind = switch (effect.effectType()) {
            case HEAL -> {
                resolveHeal(sessionId, player, caster, effect, spellLevel, targetIds, results, beat);
                yield "SPELL_HEAL";
            }
            case DAMAGE -> {
                resolveSpellDamage(enc, sessionId, player, caster, effect, spellLevel, effectiveTargets, results, beat);
                yield "SPELL_DAMAGE";
            }
            default -> {
                resolveSpellEffect(enc, sessionId, player, caster, effect, effectiveTargets, results, beat);
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

        broadcastAction(enc, CombatantKind.PLAYER, player.getCharacterName(),
                actionKind, "casts " + effect.name(), results);
        maybeAutoEndTurn(enc, player, beat);
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
        int speed = ConditionRules.effectiveSpeed(c != null ? c.getSpeed() : 30, playerConds(player.getId()));
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
                    beat.add(e.getName() + " is " + incapacitatingLabel(e.getConditions()) + " and can't act.");
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
                beat.add(playerName(active.refId()) + " is " + incapacitatingLabel(pConds) + " and can't act.");
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
        int meleeReach = enemyReachFeet(enemy);
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
            beat.add(enemyMoveLine(enemy, intent, target.player().getCharacterName()));
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
                DiceRollResult dmg = rollExpr(damageDice);
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
            beat.add(describeAttack(enemy.getName(), victim.getCharacterName(), atk,
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

    /** One narration line for an enemy's intent-driven reposition. */
    private String enemyMoveLine(Enemy enemy, EnemyIntent intent, String targetName) {
        return switch (intent) {
            case FLEE -> enemy.getName() + " retreats from the party.";
            case KITE_RANGED -> enemy.getName() + " repositions to keep " + targetName + " at range.";
            case HOLD -> enemy.getName() + " holds its ground.";
            default -> enemy.getName() + " advances toward " + targetName + ".";
        };
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

    private void resolveSpellDamage(CombatEncounter enc, UUID sessionId, Player caster, Character casterChar,
                                    SpellEffect effect, int spellLevel, List<UUID> targetIds,
                                    List<CombatActionEvent.Target> results, List<String> beat) {
        List<Enemy> targets = enemyTargets(sessionId, targetIds, effect);
        String dice = scaledNotation(effect.damageDice(), effect, spellLevel, casterChar);
        int dc = SpellcastingRules.spellSaveDc(casterChar);
        int atkBonus = SpellcastingRules.spellAttackBonus(casterChar)
                + ConditionRules.attackModifier(playerConds(caster.getId()));
        int[] darts = distribute(Math.max(1, effect.projectiles()), targets.size());
        Token casterTok = tokenFor(enc, caster.getId().toString());
        for (int i = 0; i < targets.size(); i++) {
            Enemy e = targets.get(i);
            String label = caster.getCharacterName() + "'s " + effect.name();
            switch (effect.resolution()) {
                case SPELL_ATTACK -> {
                    Token targetTok = tokenFor(enc, e.getId().toString());
                    // Touch spells are melee spell attacks (matters for advantage vs a prone target);
                    // ranged spell attacks are not. Auto-crit still never applies to spell attacks.
                    RollMode mode = RollMode.combine(
                            ConditionRules.attackMode(playerConds(caster.getId()), e.getConditions(),
                                    effect.isMeleeRange()),
                            targetTok != null && targetTok.isDodging()
                                    ? RollMode.DISADVANTAGE : RollMode.NORMAL);
                    DiceRollResult atk = rollAttack(atkBonus, mode);
                    int targetAc = effectiveAc(e.getArmorClass(), casterTok, targetTok, enc.getGridState());
                    boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);
                    RollSummary dmg = null;
                    if (hit) {
                        DiceRollResult d = rollExpr(dice);
                        applyEnemyDamage(e, d.total());
                        dmg = RollSummary.of(d);
                    }
                    results.add(CombatActionEvent.Target.attack(CombatantKind.ENEMY, e.getName(),
                            RollSummary.of(atk), targetAc, hit, dmg,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                    beat.add(describeAttack(label, e.getName(), atk, targetAc, hit, dmg,
                            e.getCurrentHp(), e.getMaxHp(), !e.isAlive()));
                }
                case SAVE -> {
                    boolean autoFail = ConditionRules.autoFailsSave(e.getConditions(), effect.saveAbility());
                    int saveMod = enemySaveMod(e, effect.saveAbility())
                            + ConditionRules.saveModifier(e.getConditions());
                    DiceRollResult save = diceService.roll(notation(saveMod),
                            ConditionRules.saveMode(e.getConditions(), effect.saveAbility()));
                    boolean saved = !autoFail && save.total() >= dc;
                    DiceRollResult d = rollExpr(dice);
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
                default -> { // AUTO — auto-hit; projectiles (darts) distributed across targets
                    int hits = darts[i];
                    if (hits <= 0) continue;  // this target got no darts
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

    /** BUFF / DEBUFF / CONTROL / UTILITY — tag a structured condition (save-gated for enemies) and narrate. */
    private void resolveSpellEffect(CombatEncounter enc, UUID sessionId, Player caster, Character casterChar,
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
                    boolean autoFail = ConditionRules.autoFailsSave(e.getConditions(), effect.saveAbility());
                    int saveMod = enemySaveMod(e, effect.saveAbility())
                            + ConditionRules.saveModifier(e.getConditions());
                    DiceRollResult save = diceService.roll(notation(saveMod),
                            ConditionRules.saveMode(e.getConditions(), effect.saveAbility()));
                    applied = autoFail || save.total() < dc;
                    saveSummary = RollSummary.of(save);
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
                    broadcast(sessionId, PlayerStateEvent.of(sessionId,
                            playerStateService.applyCondition(pid, c)));
                }
                PlayerRuntimeStateDto s = playerStateService.getState(pid);
                results.add(CombatActionEvent.Target.effect(CombatantKind.PLAYER, playerName(pid),
                        cond, s.currentHp(), s.maxHp()));
                beat.add(caster.getCharacterName() + "'s " + effect.name() + " bolsters "
                        + playerName(pid) + ".");
            }
        }
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
        int conMod = abilityMod(updated, "CON");
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

    /** Ability modifier from a player's runtime ability scores (defaults to 0 if absent). */
    private static int abilityMod(PlayerRuntimeStateDto state, String ability) {
        Integer score = state.abilities() == null ? null : state.abilities().get(ability);
        return score == null ? 0 : Math.floorDiv(score - 10, 2);
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
                int mod = enemySaveMod(e, c.saveAbility()) + ConditionRules.saveModifier(conds);
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

    /** First incapacitating condition name on the list, for the "X is <…> and can't act" beat. */
    private static String incapacitatingLabel(List<ActiveCondition> conds) {
        for (ActiveCondition c : conds) {
            if (ConditionRules.incapacitated(List.of(c))) {
                return c.name();
            }
        }
        return "incapacitated";
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
        beat.add(describeDeathSave(active.name(), roll, result));
        return result.outcome();
    }

    /** One narration line for a death save, e.g. "Aria steadies — death save 14: success (2/3)." */
    private String describeDeathSave(String name, DiceRollResult roll,
                                     PlayerStateService.DeathSaveResult result) {
        PlayerRuntimeStateDto s = result.state();
        return switch (result.outcome()) {
            case REVIVED -> name + " gasps back to life — death save " + roll.total()
                    + " (a natural 20!), conscious again at 1 HP.";
            case STABILIZED -> name + " stabilizes — death save " + roll.total()
                    + ": success (" + s.deathSaveSuccesses() + "/3), now holding on at 0 HP.";
            case DIED -> name + " slips away — death save " + roll.total()
                    + ": failure (" + s.deathSaveFailures() + "/3).";
            case SUCCESS -> name + " steadies — death save " + roll.total()
                    + ": success (" + s.deathSaveSuccesses() + "/3).";
            case FAILURE -> name + " falters — death save " + roll.total()
                    + ": failure (" + s.deathSaveFailures() + "/3).";
        };
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

    /** Whether a spell selects enemies (matches {@link #resolveSpellEffect}'s offensive test, plus DAMAGE). */
    private boolean isOffensive(SpellEffect effect) {
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
    private List<UUID> aoeEnemyIds(CombatEncounter enc, SpellEffect effect,
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

    /** A combatant's token by refId (player/enemy UUID string), or {@code null} on a legacy/no-grid encounter. */
    private Token tokenFor(CombatEncounter enc, String refId) {
        GridState g = enc.getGridState();
        if (g == null || g.getTokens() == null) {
            return null;
        }
        return g.getTokens().get(refId);
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
        int speed = ConditionRules.effectiveSpeed(c != null ? c.getSpeed() : 30,
                playerConds(player.getId()));
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
        if (attacker == null || defender == null || grid == null) {
            return baseAc;
        }
        return baseAc + gridService.coverBonus(grid,
                attacker.getX(), attacker.getY(), defender.getX(), defender.getY());
    }

    /**
     * Roll a 1d20 attack with the given bonus, applying disadvantage (e.g. against a
     * Dodging target). The NORMAL path uses the single-arg roll so existing callers and
     * tests see the same notation.
     */
    private DiceRollResult rollAttack(int bonus, boolean disadvantage) {
        return rollAttack(bonus, disadvantage ? RollMode.DISADVANTAGE : RollMode.NORMAL);
    }

    /** Roll a 1d20 attack with the given bonus and an explicit advantage/disadvantage mode. */
    private DiceRollResult rollAttack(int bonus, RollMode mode) {
        return mode == RollMode.NORMAL
                ? diceService.roll(notation(bonus))
                : diceService.roll(notation(bonus), mode);
    }

    /* ── condition-aware attack/melee helpers ────────────────────── */

    /** A player's structured conditions (empty when none). */
    private List<ActiveCondition> playerConds(UUID playerId) {
        try {
            return playerStateService.conditions(playerId);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** True when two tokens are within 5 ft (one square); assumes melee when there is no grid. */
    private boolean isMelee(Token a, Token b, GridState grid) {
        if (grid == null || a == null || b == null) {
            return true;
        }
        return gridService.distanceFeet(a.getX(), a.getY(), b.getX(), b.getY())
                <= GridService.FEET_PER_SQUARE;
    }

    /** Net attack roll mode for a player attacking an enemy (conditions + the target Dodging). */
    private RollMode playerVsEnemyMode(Player attacker, Enemy defender, Token defenderTok, boolean melee) {
        RollMode cond = ConditionRules.attackMode(playerConds(attacker.getId()),
                defender.getConditions(), melee);
        RollMode dodge = defenderTok != null && defenderTok.isDodging()
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        return RollMode.combine(cond, dodge);
    }

    /** Net attack roll mode for an enemy attacking a player (conditions + the target Dodging). */
    private RollMode enemyVsPlayerMode(Enemy attacker, UUID victimId, Token victimTok, boolean melee) {
        RollMode cond = ConditionRules.attackMode(attacker.getConditions(),
                playerConds(victimId), melee);
        RollMode dodge = victimTok != null && victimTok.isDodging()
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        return RollMode.combine(cond, dodge);
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
            int reach = enemyReachFeet(e);
            if (withinReach(et.getX(), et.getY(), moverX, moverY, reach)) {
                threats.add(new OaThreat(e.getId(), e.getId().toString(), et.getX(), et.getY(), reach));
            }
        }
        return threats;
    }

    /** Max melee reach (ft) across the enemy's attacks, default 5. */
    private int enemyReachFeet(Enemy e) {
        int reach = GridService.FEET_PER_SQUARE; // 5 ft default
        for (MonsterAttack a : e.getAttacks()) {
            if ("MELEE".equalsIgnoreCase(a.kind()) && a.reach() != null) {
                reach = Math.max(reach, a.reach());
            }
        }
        return reach;
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
        beat.add(describeAttack(enemy.getName(), victim.getCharacterName(), atk, targetAc, hit,
                damageSummary, targetHp, targetMax, defeated));
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
                    .speed(t.speed() != null ? t.speed() : 30)
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

    /** Keyword → normal weapon range (ft); mirrors {@code lib/combat.ts WEAPON_RANGE}. */
    private static final java.util.List<Map.Entry<String, Integer>> WEAPON_RANGE = java.util.List.of(
            Map.entry("longbow", 150), Map.entry("crossbow", 80), Map.entry("shortbow", 80),
            Map.entry("bow", 80), Map.entry("blowgun", 25), Map.entry("sling", 30),
            Map.entry("javelin", 30), Map.entry("dart", 20), Map.entry("handaxe", 20),
            Map.entry("spear", 20), Map.entry("trident", 20), Map.entry("halberd", 10),
            Map.entry("glaive", 10), Map.entry("pike", 10), Map.entry("lance", 10),
            Map.entry("whip", 10));

    /**
     * The player's basic-attack range in feet, inferred from their weapon items by name:
     * ranged/thrown weapons get their normal range, reach weapons 10 ft, otherwise 5 ft melee
     * (the floor, used for unarmed / no weapons). Uses the longest reach available so an archer
     * carrying a sidearm can still shoot.
     */
    private int attackRangeFeet(Player player) {
        int range = GridService.FEET_PER_SQUARE; // 5 ft melee floor
        List<InventoryItem> inv;
        try {
            inv = playerStateService.getState(player.getId()).inventory();
        } catch (RuntimeException ex) {
            return range;
        }
        if (inv == null) return range;
        for (InventoryItem item : inv) {
            // Only weapons set the range — avoids gear false matches ("Iron Spike" → "pike").
            if (item.name() == null || item.kind() != ItemKind.WEAPON) continue;
            String name = item.name().toLowerCase(java.util.Locale.ROOT);
            for (Map.Entry<String, Integer> e : WEAPON_RANGE) {
                if (name.contains(e.getKey())) range = Math.max(range, e.getValue());
            }
        }
        return range;
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
                .map(e -> new EnemyDto(e.getId(), e.getName(), e.getArmorClass(), e.isAlive(),
                        e.getConditions().stream().map(ActiveCondition::name).toList(),
                        HealthBand.of(e.getCurrentHp(), e.getMaxHp()), enemyReachFeet(e)))
                .toList();
        List<Combatant> order = enc.getInitiativeOrder();
        Combatant active = (enc.getStatus() == CombatStatus.ACTIVE
                && enc.getActiveIndex() < order.size())
                ? order.get(enc.getActiveIndex()) : null;
        return new CombatStateDto(
                enc.getId(), enc.getStatus(), enc.getRound(),
                enc.getActiveIndex(), active, order, enemies, enc.getGridState());
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
