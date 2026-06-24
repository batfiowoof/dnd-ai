package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.CombatLifecycleEvent;
import com.dungeon.master.model.dto.CombatStateDto;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.EnemyActionEvent;
import com.dungeon.master.model.dto.EnemyDto;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.dto.RollSummary;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final PlayerStateService playerStateService;
    private final DiceService diceService;
    private final SimpMessagingTemplate messagingTemplate;

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

        // Create enemies, numbering duplicates (Goblin 1, Goblin 2, ...).
        List<Enemy> enemies = new ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String key : enemyKeys) {
            Bestiary.Template t = Bestiary.get(key);
            long sameType = enemyKeys.stream().filter(k -> k.equalsIgnoreCase(key)).count();
            int n = counts.merge(key.toLowerCase(), 1, Integer::sum);
            String name = sameType > 1 ? t.name() + " " + n : t.name();
            enemies.add(Enemy.builder()
                    .id(UUID.randomUUID())
                    .sessionId(sessionId)
                    .name(name)
                    .maxHp(t.hp())
                    .currentHp(t.hp())
                    .armorClass(t.armorClass())
                    .attackBonus(t.attackBonus())
                    .damageDice(t.damageDice())
                    .initiative(diceService.roll("1d20").total())
                    .alive(true)
                    .build());
        }
        enemyRepository.saveAll(enemies);

        // Build initiative order: players (1d20 + DEX mod) + enemies.
        List<Combatant> order = new ArrayList<>();
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER) continue;
            int init = diceService.roll("1d20").total() + dexMod(p);
            order.add(new Combatant(CombatantKind.PLAYER, p.getId(), p.getCharacterName(), init));
        }
        for (Enemy e : enemies) {
            order.add(new Combatant(CombatantKind.ENEMY, e.getId(), e.getName(), e.getInitiative()));
        }
        order.sort(Comparator.comparingInt(Combatant::initiative).reversed());

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

        resolveUntilPlayerOrEnd(enc);
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

        broadcast(sessionId, new EnemyActionEvent(
                EnemyActionEvent.TYPE, sessionId,
                CombatantKind.PLAYER, player.getCharacterName(),
                CombatantKind.ENEMY, enemy.getName(),
                RollSummary.of(atk), enemy.getArmorClass(), hit, damageSummary,
                enemy.getCurrentHp(), enemy.getMaxHp(), defeated,
                toStateDto(enc)));

        advanceTurn(enc);
    }

    @Transactional
    public void playerUseItem(UUID sessionId, String username, String itemName) {
        CombatEncounter enc = activeEncounter(sessionId);
        Player player = requireCombatTurn(enc, sessionId, username);

        PlayerStateService.ItemUseResult result = playerStateService.useItem(player.getId(), itemName);
        broadcast(sessionId, PlayerStateEvent.of(sessionId, result.state()));

        advanceTurn(enc);
    }

    @Transactional
    public void playerEndTurn(UUID sessionId, String username) {
        CombatEncounter enc = activeEncounter(sessionId);
        requireCombatTurn(enc, sessionId, username);
        advanceTurn(enc);
    }

    @Transactional
    public void endEncounterByHost(UUID sessionId) {
        CombatEncounter enc = activeEncounter(sessionId);
        endEncounter(enc, allEnemiesDead(sessionId));
    }

    /* ── turn engine ─────────────────────────────────────────────── */

    private void advanceTurn(CombatEncounter enc) {
        advanceIndex(enc);
        resolveUntilPlayerOrEnd(enc);
    }

    /**
     * Auto-resolve enemy turns (and skip dead/downed combatants) until it is a
     * living player's turn or the encounter ends. Three termination guards plus a
     * hard step cap make non-termination impossible.
     */
    private void resolveUntilPlayerOrEnd(CombatEncounter enc) {
        UUID sessionId = enc.getSessionId();
        int order = enc.getInitiativeOrder().size();
        int maxSteps = Math.max(1, order) * 4 + 8;

        for (int step = 0; step < maxSteps; step++) {
            if (allEnemiesDead(sessionId)) {           // guard (a): victory
                endEncounter(enc, true);
                return;
            }
            if (noLivingPlayers(sessionId)) {          // guard (b): TPK
                endEncounter(enc, false);
                return;
            }

            Combatant active = enc.getInitiativeOrder().get(enc.getActiveIndex());

            if (active.kind() == CombatantKind.ENEMY) {
                Enemy e = enemyRepository.findById(active.refId()).orElse(null);
                if (e == null || !e.isAlive()) {
                    advanceIndex(enc);
                    continue;
                }
                enemyAct(enc, e);
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
        endEncounter(enc, allEnemiesDead(sessionId));
    }

    private void enemyAct(CombatEncounter enc, Enemy enemy) {
        UUID sessionId = enc.getSessionId();
        TargetPlayer target = pickTarget(sessionId);
        if (target == null) {                          // guard (c): no valid target
            return;
        }

        DiceRollResult atk = diceService.roll(notation(enemy.getAttackBonus()));
        int targetAc = armorClass(target.player());
        boolean hit = atk.crit() || (!atk.fumble() && atk.total() >= targetAc);

        RollSummary damageSummary = null;
        int targetHp = target.state().currentHp();
        int targetMax = target.state().maxHp();
        boolean defeated = false;

        if (hit) {
            DiceRollResult dmg = diceService.roll(enemy.getDamageDice());
            PlayerRuntimeStateDto updated =
                    playerStateService.applyHpDelta(target.player().getId(), -dmg.total());
            broadcast(sessionId, PlayerStateEvent.of(sessionId, updated));
            targetHp = updated.currentHp();
            targetMax = updated.maxHp();
            defeated = updated.currentHp() <= 0;
            damageSummary = RollSummary.of(dmg);
        }

        broadcast(sessionId, new EnemyActionEvent(
                EnemyActionEvent.TYPE, sessionId,
                CombatantKind.ENEMY, enemy.getName(),
                CombatantKind.PLAYER, target.player().getCharacterName(),
                RollSummary.of(atk), targetAc, hit, damageSummary,
                targetHp, targetMax, defeated,
                toStateDto(enc)));
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

    private void endEncounter(CombatEncounter enc, boolean victory) {
        enc.setStatus(CombatStatus.ENDED);
        encounterRepository.save(enc);
        broadcast(enc.getSessionId(), CombatLifecycleEvent.end(enc.getSessionId(), victory, toStateDto(enc)));
        log.info("Combat ended: session={}, victory={}", enc.getSessionId(), victory);
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

    /* ── stat helpers (load from the Character template) ─────────── */

    private Character character(Player player) {
        return player.getCharacterId() == null ? null
                : characterRepository.findById(player.getCharacterId()).orElse(null);
    }

    private int dexMod(Player player) {
        Character c = character(player);
        return c == null ? 0 : Math.floorDiv(c.getDexterity() - 10, 2);
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
}
