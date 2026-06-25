package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.CombatLifecycleEvent;
import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CombatServiceTest {

    private EnemyRepository enemyRepo;
    private CombatEncounterRepository encounterRepo;
    private PlayerRepository playerRepo;
    private CharacterRepository characterRepo;
    private PlayerStateService playerStateService;
    private DiceService diceService;
    private TurnService turnService;
    private GameEventProducer eventProducer;
    private SimpMessagingTemplate messaging;
    private CombatService combat;

    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        enemyRepo = mock(EnemyRepository.class);
        encounterRepo = mock(CombatEncounterRepository.class);
        playerRepo = mock(PlayerRepository.class);
        characterRepo = mock(CharacterRepository.class);
        playerStateService = mock(PlayerStateService.class);
        diceService = mock(DiceService.class);
        turnService = mock(TurnService.class);
        eventProducer = mock(GameEventProducer.class);
        messaging = mock(SimpMessagingTemplate.class);
        combat = new CombatService(enemyRepo, encounterRepo, playerRepo, characterRepo,
                playerStateService, diceService, turnService, eventProducer, messaging);

        // Combat beats persist a TurnEvent then fire a narration event; return a stub event.
        when(turnService.createCombatBeat(any(UUID.class), any(UUID.class), anyString()))
                .thenAnswer(inv -> TurnEvent.builder()
                        .id(UUID.randomUUID())
                        .sessionId(inv.getArgument(0))
                        .playerId(inv.getArgument(1))
                        .action(inv.getArgument(2))
                        .turnNumber(1)
                        .build());

        // save returns the argument (assigning an id when absent).
        when(encounterRepo.save(any(CombatEncounter.class))).thenAnswer(inv -> {
            CombatEncounter e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        when(enemyRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enemyRepo.save(any(Enemy.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private DiceRollResult res(int total) {
        return new DiceRollResult("n", 1, 20, 0, RollMode.NORMAL,
                List.of(total), null, total, false, false);
    }

    private Player player(UUID id, String name) {
        return Player.builder()
                .id(id).username(name).characterName(name)
                .sessionId(sessionId).role(PlayerRole.PLAYER).turnIndex(0)
                .build();
    }

    private PlayerRuntimeStateDto stateFor(UUID playerId, int hp) {
        return new PlayerRuntimeStateDto(playerId, hp, 20, 0, 10, java.util.Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void startEncounterBuildsInitiativeOrderDescending() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(playerRepo.findBySessionId(sessionId))
                .thenReturn(List.of(player(p1, "Aria"), player(p2, "Borin")));
        // enemy init, then p1 init, then p2 init
        when(diceService.roll("1d20")).thenReturn(res(5), res(20), res(10));
        when(playerStateService.getSessionStates(sessionId))
                .thenReturn(List.of(stateFor(p1, 20), stateFor(p2, 20)));
        when(playerStateService.getState(p1)).thenReturn(stateFor(p1, 20));

        // enemies present + alive throughout
        when(enemyRepo.findBySessionId(eq(sessionId))).thenAnswer(inv ->
                List.of(Enemy.builder().id(UUID.randomUUID()).sessionId(sessionId)
                        .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                        .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build()));

        combat.startEncounter(sessionId, List.of("GOBLIN"));

        ArgumentCaptor<CombatEncounter> captor = ArgumentCaptor.forClass(CombatEncounter.class);
        verify(encounterRepo, atLeastOnce()).save(captor.capture());
        List<Combatant> order = captor.getValue().getInitiativeOrder();

        assertEquals(3, order.size());
        // strictly non-increasing initiative
        for (int i = 1; i < order.size(); i++) {
            assertTrue(order.get(i - 1).initiative() >= order.get(i).initiative());
        }
        assertEquals(20, order.get(0).initiative());
        assertEquals(CombatantKind.PLAYER, order.get(0).kind());
        assertEquals(CombatantKind.ENEMY, order.get(2).kind());
    }

    @Test
    void playerAttackKillingLastEnemyEndsInVictory() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        Player attacker = player(pid, "Aria");

        CombatEncounter enc = CombatEncounter.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(
                        new Combatant(CombatantKind.PLAYER, pid, "Aria", 18),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5)))
                .activeIndex(0)
                .round(1)
                .build();

        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(7).currentHp(5).armorClass(10)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE))
                .thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria"))
                .thenReturn(Optional.of(attacker));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        // null characterId → default attack bonus (+2) and damage "1d8"
        when(diceService.roll("1d20+2")).thenReturn(res(20)); // hits AC 10
        when(diceService.roll("1d8")).thenReturn(res(10));     // 10 dmg kills 5-HP goblin
        // after the kill the only enemy is dead → victory
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));

        combat.playerAttack(sessionId, "Aria", enemyId);

        assertEquals(0, goblin.getCurrentHp());
        assertTrue(!goblin.isAlive());

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(messaging, atLeastOnce()).convertAndSend(anyString(), events.capture());

        boolean victory = events.getAllValues().stream()
                .filter(e -> e instanceof CombatLifecycleEvent)
                .map(e -> (CombatLifecycleEvent) e)
                .anyMatch(e -> CombatLifecycleEvent.END.equals(e.type())
                        && Boolean.TRUE.equals(e.victory()));
        assertTrue(victory, "expected a COMBAT_END victory event");
    }
}
