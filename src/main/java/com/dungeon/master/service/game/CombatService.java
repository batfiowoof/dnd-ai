package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.kafka.event.CombatNarrationEvent;
import com.dungeon.master.kafka.producer.GameEventProducer;
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
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.ItemKind;
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
    private final PlayerStateService playerStateService;
    private final DiceService diceService;
    private final TurnService turnService;
    private final GameEventProducer eventProducer;
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

        broadcast(sessionId, new EnemyActionEvent(
                EnemyActionEvent.TYPE, sessionId,
                CombatantKind.PLAYER, player.getCharacterName(),
                CombatantKind.ENEMY, enemy.getName(),
                RollSummary.of(atk), enemy.getArmorClass(), hit, damageSummary,
                enemy.getCurrentHp(), enemy.getMaxHp(), defeated,
                toStateDto(enc)));

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

    private void enemyAct(CombatEncounter enc, Enemy enemy, List<String> beat) {
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

        beat.add(describeAttack(enemy.getName(), target.player().getCharacterName(), atk,
                targetAc, hit, damageSummary, targetHp, targetMax, defeated));
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
