package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.CombatActionEvent;
import com.dungeon.master.model.dto.CombatLifecycleEvent;
import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.enums.TerrainType;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.CombatantKind;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CombatServiceTest {

    private EnemyRepository enemyRepo;
    private CombatEncounterRepository encounterRepo;
    private PlayerRepository playerRepo;
    private CharacterRepository characterRepo;
    private GameSessionRepository sessionRepo;
    private PlayerStateService playerStateService;
    private DiceService diceService;
    private TurnService turnService;
    private GameEventProducer eventProducer;
    private SimpMessagingTemplate messaging;
    private MonsterCatalog monsterCatalog;
    private MonsterResolver monsterResolver;
    private com.dungeon.master.service.ai.SpellCatalog spellCatalog;
    private CheckModifierService checkModifierService;
    private com.dungeon.master.service.ai.SceneGenerator sceneGenerator;
    private com.dungeon.master.service.ai.EnemyTacticsService enemyTacticsService;
    private CombatService combat;

    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        enemyRepo = mock(EnemyRepository.class);
        encounterRepo = mock(CombatEncounterRepository.class);
        playerRepo = mock(PlayerRepository.class);
        characterRepo = mock(CharacterRepository.class);
        sessionRepo = mock(GameSessionRepository.class);
        playerStateService = mock(PlayerStateService.class);
        diceService = mock(DiceService.class);
        turnService = mock(TurnService.class);
        eventProducer = mock(GameEventProducer.class);
        messaging = mock(SimpMessagingTemplate.class);
        monsterCatalog = mock(MonsterCatalog.class);
        monsterResolver = mock(MonsterResolver.class);
        spellCatalog = mock(com.dungeon.master.service.ai.SpellCatalog.class);
        // Default: no catalog entry and no session custom monsters → buildEnemy falls back to Bestiary.
        when(monsterCatalog.get(anyString())).thenReturn(Optional.empty());
        when(monsterResolver.customTemplates(any())).thenReturn(java.util.List.of());
        checkModifierService = mock(CheckModifierService.class);
        sceneGenerator = mock(com.dungeon.master.service.ai.SceneGenerator.class);
        enemyTacticsService = mock(com.dungeon.master.service.ai.EnemyTacticsService.class);
        // startEncounter delegates grid construction to the SceneGenerator; mirror its default
        // (open arena + token placement) so the produced encounter looks like production. The
        // enemy-acting tests below build their own no-grid encounters, so the LLM-backed
        // SceneGenerator / EnemyTacticsService are never actually exercised here.
        when(sceneGenerator.generateScene(any(UUID.class), any(), any())).thenAnswer(inv -> {
            GridService gs = new GridService();
            List<String> prefs = inv.getArgument(1);
            List<String> erefs = inv.getArgument(2);
            GridState g = gs.defaultArena(prefs.size(), erefs.size());
            gs.placeTokens(g, prefs, erefs);
            return g;
        });
        // CombatMapper + CombatBroadcaster are wired with REAL instances (not mocks): the
        // assertions below verify the websocket sends that land on the mock SimpMessagingTemplate,
        // so the broadcaster must actually route through it.
        com.dungeon.master.service.game.combat.CombatMapper combatMapper =
                new com.dungeon.master.service.game.combat.CombatMapper(enemyRepo);
        com.dungeon.master.service.game.combat.CombatBroadcaster combatBroadcaster =
                new com.dungeon.master.service.game.combat.CombatBroadcaster(messaging, combatMapper);
        // Real terrain service: the terrain test exercises the actual AoE-cell stamping.
        com.dungeon.master.service.game.combat.CombatTerrainService combatTerrainService =
                new com.dungeon.master.service.game.combat.CombatTerrainService(new GridService());
        // Real lookups + spell resolver: the spell tests exercise the actual resolution math and
        // the broadcasts that land on the mock SimpMessagingTemplate.
        com.dungeon.master.service.game.combat.CombatLookups combatLookups =
                new com.dungeon.master.service.game.combat.CombatLookups(playerRepo, playerStateService);
        com.dungeon.master.service.game.combat.CombatSpellResolver combatSpellResolver =
                new com.dungeon.master.service.game.combat.CombatSpellResolver(
                        playerStateService, enemyRepo, diceService, new GridService(),
                        combatBroadcaster, combatLookups);
        combat = new CombatService(enemyRepo, encounterRepo, playerRepo, characterRepo,
                sessionRepo, playerStateService, diceService, turnService, eventProducer,
                monsterCatalog, monsterResolver, spellCatalog, new GridService(), checkModifierService,
                sceneGenerator, enemyTacticsService, combatMapper, combatBroadcaster,
                combatTerrainService, combatLookups, combatSpellResolver);

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

    private Player playerWithChar(UUID id, String name, UUID characterId) {
        return Player.builder()
                .id(id).username(name).characterName(name).characterId(characterId)
                .sessionId(sessionId).role(PlayerRole.PLAYER).turnIndex(0)
                .build();
    }

    private Character charWithDex(int dex) {
        return Character.builder()
                .id(UUID.randomUUID()).ownerUsername("u").name("C")
                .race("Human").characterClass("Fighter").dexterity(dex)
                .build();
    }

    private PlayerRuntimeStateDto stateFor(UUID playerId, int hp) {
        return new PlayerRuntimeStateDto(playerId, hp, 20, 0, 10, java.util.Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, 0, 0, false, false, null);
    }

    /** 0 HP, not dead, not stable — actively dying (rolling death saves). */
    private PlayerRuntimeStateDto dyingState(UUID playerId) {
        return new PlayerRuntimeStateDto(playerId, 0, 20, 0, 10, java.util.Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, 0, 0, false, false, null);
    }

    /** 0 HP, dead (three failures). */
    private PlayerRuntimeStateDto deadState(UUID playerId) {
        return new PlayerRuntimeStateDto(playerId, 0, 20, 0, 10, java.util.Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, 0, 3, false, true, null);
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
                        new Combatant(CombatantKind.PLAYER, pid, "Aria", 18, 0),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5, 2)))
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
        // null characterId → default attack bonus (+2); no weapon in inventory → 1d4 (improvised)
        when(diceService.roll("1d20+2")).thenReturn(res(20)); // hits AC 10
        when(diceService.roll("1d4")).thenReturn(res(10));     // 10 dmg kills 5-HP goblin
        // after the kill the only enemy is dead → victory
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));

        combat.playerAttack(sessionId, "Aria", enemyId);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: the player rolls the damage

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

    @Test
    void equalInitiativeRollsBreakDeterministicallyByDexMod() {
        UUID p1 = UUID.randomUUID();   // Aria — DEX 14 (+2)
        UUID p2 = UUID.randomUUID();   // Borin — DEX 10 (0)
        UUID cA = UUID.randomUUID();
        UUID cB = UUID.randomUUID();
        when(playerRepo.findBySessionId(sessionId))
                .thenReturn(List.of(playerWithChar(p1, "Aria", cA), playerWithChar(p2, "Borin", cB)));
        when(characterRepo.findById(cA)).thenReturn(Optional.of(charWithDex(14)));
        when(characterRepo.findById(cB)).thenReturn(Optional.of(charWithDex(10)));
        // enemy roll (3 + goblin dex 2 = 5), Aria (8 + 2 = 10), Borin (10 + 0 = 10) → tie at 10.
        when(diceService.roll("1d20")).thenReturn(res(3), res(8), res(10));
        when(playerStateService.getSessionStates(sessionId))
                .thenReturn(List.of(stateFor(p1, 20), stateFor(p2, 20)));
        when(playerStateService.getState(p1)).thenReturn(stateFor(p1, 20));
        when(enemyRepo.findBySessionId(eq(sessionId))).thenAnswer(inv ->
                List.of(Enemy.builder().id(UUID.randomUUID()).sessionId(sessionId)
                        .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                        .attackBonus(4).damageDice("1d6+2").initiative(5).dexMod(2).alive(true).build()));

        combat.startEncounter(sessionId, List.of("GOBLIN"));

        ArgumentCaptor<CombatEncounter> captor = ArgumentCaptor.forClass(CombatEncounter.class);
        verify(encounterRepo, atLeastOnce()).save(captor.capture());
        List<Combatant> order = captor.getValue().getInitiativeOrder();

        // Tie at initiative 10 is broken by DEX mod (Aria +2 before Borin +0).
        assertEquals(10, order.get(0).initiative());
        assertEquals(10, order.get(1).initiative());
        assertEquals("Aria", order.get(0).name());
        assertEquals("Borin", order.get(1).name());
        assertEquals(2, order.get(0).dexMod());
    }

    @Test
    void fullRoundAdvancesThroughEveryCombatantAndWraps() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        Player aria = player(pid, "Aria");

        CombatEncounter enc = CombatEncounter.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(
                        new Combatant(CombatantKind.PLAYER, pid, "Aria", 20, 0),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5, 2)))
                .activeIndex(0)
                .round(1)
                .build();

        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                .attackBonus(4).damageDice("1d6+2").initiative(5).dexMod(2).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE))
                .thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(aria));
        when(playerRepo.findById(pid)).thenReturn(Optional.of(aria));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin)); // alive → fight continues
        when(playerStateService.getSessionStates(sessionId)).thenReturn(List.of(stateFor(pid, 20)));
        when(playerStateService.getState(pid)).thenReturn(stateFor(pid, 20));
        // Goblin's attack (1d20+4) totals 5 vs Aria's AC 10 → a miss, so no one drops.
        when(diceService.roll("1d20+4")).thenReturn(res(5));

        combat.playerEndTurn(sessionId, "Aria");

        // Aria ends turn → goblin auto-acts → order wraps back to Aria at the top of round 2.
        assertEquals(0, enc.getActiveIndex(), "active index should wrap back to the first combatant");
        assertEquals(2, enc.getRound(), "round should increment when the order wraps");
    }

    /* ── Phase B: tactical movement, action economy, opportunity attacks ── */

    /** Build a single-player + single-enemy ACTIVE encounter with a grid, the player active. */
    private CombatEncounter gridEncounter(UUID pid, int px, int py, UUID enemyId, int ex, int ey) {
        GridState grid = GridState.builder().width(14).height(10).build();
        grid.getTokens().put(pid.toString(), new Token(px, py, 0, true, false, false, false, false, false));
        grid.getTokens().put(enemyId.toString(), new Token(ex, ey, 0, true, false, false, false, false, false));
        return CombatEncounter.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(
                        new Combatant(CombatantKind.PLAYER, pid, "Aria", 18, 0),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5, 2)))
                .activeIndex(0).round(1).gridState(grid).build();
    }

    @Test
    void playerMoveWithinBudgetUpdatesTokenAndKeepsTurn() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = gridEncounter(pid, 0, 0, enemyId, 12, 8); // enemy far away → no OA
        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of()); // no living threats

        combat.playerMove(sessionId, "Aria", 2, 0); // two squares east = 10 ft

        Token t = enc.getGridState().getTokens().get(pid.toString());
        assertEquals(2, t.getX());
        assertEquals(10, t.getMovementUsedFeet());
        assertEquals(0, enc.getActiveIndex(), "moving must not advance the turn");
    }

    @Test
    void playerMoveBeyondBudgetIsRejected() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = gridEncounter(pid, 0, 0, enemyId, 12, 8);
        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of());

        // No character → speed 30 (6 squares). (8,0) is 40 ft away → over budget.
        assertThrows(IllegalArgumentException.class, () -> combat.playerMove(sessionId, "Aria", 8, 0));
    }

    @Test
    void dodgeThenAttackIsRejected() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = gridEncounter(pid, 1, 0, enemyId, 12, 8);
        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of());

        combat.playerDodge(sessionId, "Aria");
        assertTrue(enc.getGridState().getTokens().get(pid.toString()).isActionUsed());

        // Dodge was the action — a same-turn attack must be refused.
        assertThrows(IllegalStateException.class, () -> combat.playerAttack(sessionId, "Aria", enemyId));
    }

    @Test
    void leavingEnemyReachProvokesOpportunityAttack() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = gridEncounter(pid, 1, 0, enemyId, 0, 0); // adjacent enemy
        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(playerStateService.getState(pid)).thenReturn(stateFor(pid, 20));
        when(diceService.roll("1d20+4")).thenReturn(res(25)); // OA hits AC 10 (no char)
        when(diceService.roll("1d6+2")).thenReturn(res(5));
        when(playerStateService.applyHpDelta(pid, -5, false)).thenReturn(stateFor(pid, 15));

        combat.playerMove(sessionId, "Aria", 3, 0); // leaves the goblin's 5-ft reach

        verify(playerStateService).applyHpDelta(pid, -5, false);
        assertFalse(enc.getGridState().getTokens().get(enemyId.toString()).isReactionAvailable(),
                "the goblin's reaction should be spent");
    }

    @Test
    void disengageSuppressesOpportunityAttack() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = gridEncounter(pid, 1, 0, enemyId, 0, 0);
        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));

        combat.playerDisengage(sessionId, "Aria");
        combat.playerMove(sessionId, "Aria", 3, 0); // would normally provoke, but Disengaged

        verify(playerStateService, never()).applyHpDelta(any(UUID.class), anyInt());
    }

    /* ── Phase C: death saving throws ──────────────────────────────── */

    @Test
    void dyingPlayerAutoRollsDeathSaveOnTheirTurn() {
        UUID p1 = UUID.randomUUID();   // conscious driver
        UUID p2 = UUID.randomUUID();   // dying
        UUID enemyId = UUID.randomUUID();

        CombatEncounter enc = CombatEncounter.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(
                        new Combatant(CombatantKind.PLAYER, p1, "Aria", 20, 0),
                        new Combatant(CombatantKind.PLAYER, p2, "Borin", 15, 0),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5, 2)))
                .activeIndex(0).round(1).build();

        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(p1, "Aria")));
        when(playerRepo.findById(p1)).thenReturn(Optional.of(player(p1, "Aria")));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin)); // alive → fight continues
        when(playerStateService.getSessionStates(sessionId))
                .thenReturn(List.of(stateFor(p1, 20), dyingState(p2)));
        when(playerStateService.getState(p1)).thenReturn(stateFor(p1, 20));
        when(playerStateService.getState(p2)).thenReturn(dyingState(p2));
        when(playerStateService.recordDeathSave(eq(p2), any(DiceRollResult.class)))
                .thenReturn(new PlayerStateService.DeathSaveResult(
                        dyingState(p2), PlayerStateService.DeathSaveOutcome.SUCCESS));
        when(diceService.roll("1d20")).thenReturn(res(14));    // Borin's death save (success)
        when(diceService.roll("1d20+4")).thenReturn(res(3));   // goblin attack vs Aria — a miss

        combat.playerEndTurn(sessionId, "Aria");

        // Borin's dying turn rolled a death save...
        verify(playerStateService).recordDeathSave(eq(p2), any(DiceRollResult.class));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(messaging, atLeastOnce()).convertAndSend(anyString(), events.capture());
        boolean deathSaveBroadcast = events.getAllValues().stream()
                .filter(e -> e instanceof com.dungeon.master.model.dto.DiceRollEvent)
                .map(e -> (com.dungeon.master.model.dto.DiceRollEvent) e)
                .anyMatch(e -> "Death Saving Throw".equals(e.label()));
        assertTrue(deathSaveBroadcast, "the death save should surface as a DiceRollEvent");

        // ...and combat did NOT end (Aria is still up).
        boolean ended = events.getAllValues().stream()
                .filter(e -> e instanceof CombatLifecycleEvent)
                .map(e -> (CombatLifecycleEvent) e)
                .anyMatch(e -> CombatLifecycleEvent.END.equals(e.type()));
        assertFalse(ended, "combat must not end while a player is conscious");
    }

    @Test
    void combatEndsInDefeatOnlyWhenPartyFullyDown() {
        UUID p1 = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();

        CombatEncounter enc = CombatEncounter.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(
                        new Combatant(CombatantKind.PLAYER, p1, "Aria", 20, 0),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5, 2)))
                .activeIndex(0).round(1).build();

        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(7).currentHp(7).armorClass(15)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(p1, "Aria")));
        when(playerRepo.findById(p1)).thenReturn(Optional.of(player(p1, "Aria")));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin)); // enemy never dies here
        // Aria is conscious, then the goblin drops her (dying), then her death save kills her (dead).
        when(playerStateService.getSessionStates(sessionId)).thenReturn(
                List.of(stateFor(p1, 20)),   // guard before the enemy acts
                List.of(stateFor(p1, 20)),   // pickTarget — still up
                List.of(dyingState(p1)),     // after the hit — dying, so NO defeat yet
                List.of(deadState(p1)));     // after the fatal death save — fully down
        when(playerStateService.getState(p1)).thenReturn(dyingState(p1));
        when(playerStateService.applyHpDelta(p1, -5, false)).thenReturn(dyingState(p1));
        when(playerStateService.recordDeathSave(eq(p1), any(DiceRollResult.class)))
                .thenReturn(new PlayerStateService.DeathSaveResult(
                        deadState(p1), PlayerStateService.DeathSaveOutcome.DIED));
        when(diceService.roll("1d20+4")).thenReturn(res(20)); // goblin hits AC 10
        when(diceService.roll("1d6+2")).thenReturn(res(5));    // 5 damage drops Aria to 0
        when(diceService.roll("1d20")).thenReturn(res(8));     // Aria's fatal death save

        combat.playerEndTurn(sessionId, "Aria");

        // The death save was rolled — proving we did NOT declare defeat while she was merely dying.
        verify(playerStateService).recordDeathSave(eq(p1), any(DiceRollResult.class));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(messaging, atLeastOnce()).convertAndSend(anyString(), events.capture());
        boolean defeat = events.getAllValues().stream()
                .filter(e -> e instanceof CombatLifecycleEvent)
                .map(e -> (CombatLifecycleEvent) e)
                .anyMatch(e -> CombatLifecycleEvent.END.equals(e.type())
                        && Boolean.FALSE.equals(e.victory()));
        assertTrue(defeat, "expected a COMBAT_END defeat once the whole party is down");
    }

    /* ── Phase E: grid-resolved AoE spell targeting ────────────────── */

    /** Runtime state granting the player a cantrip (for the cast guard); conscious at 20 HP. */
    private PlayerRuntimeStateDto stateWithCantrip(UUID playerId, String spell) {
        return new PlayerRuntimeStateDto(playerId, 20, 20, 0, 10, java.util.Map.of(),
                List.of(), List.of(), List.of(), List.of(spell), List.of(), false, 0, 0, false, false, null);
    }

    /** An AUTO-resolution DAMAGE spell — optionally an AoE template (shape != null). */
    private SpellEffect damageSpell(String name, String aoeShape, int aoeSize) {
        SpellTargetType target = aoeShape != null ? SpellTargetType.AREA : SpellTargetType.ENEMY;
        return new SpellEffect(name, 0, SpellEffectType.DAMAGE, target, SpellResolution.AUTO,
                null, "2d6", "Fire", null, false, false, null, null,
                aoeShape, aoeSize, null, 1, null, false, true, "Action", "120 feet", null, null);
    }

    private Enemy enemyAt(UUID id, String name) {
        return Enemy.builder().id(id).sessionId(sessionId).name(name)
                .maxHp(20).currentHp(20).armorClass(12).attackBonus(3)
                .damageDice("1d6").initiative(5).alive(true).build();
    }

    /**
     * Single-player (active) encounter with two enemies on a grid. Initiative holds only the
     * player so casting never auto-runs an enemy turn — keeping the assertion on AoE targeting.
     */
    private CombatEncounter aoeEncounter(UUID pid, UUID aId, int ax, int ay, UUID bId, int bx, int by) {
        GridState grid = GridState.builder().width(16).height(12).build();
        grid.getTokens().put(pid.toString(), new Token(5, 5, 0, true, false, false, false, false, false));
        grid.getTokens().put(aId.toString(), new Token(ax, ay, 0, true, false, false, false, false, false));
        grid.getTokens().put(bId.toString(), new Token(bx, by, 0, true, false, false, false, false, false));
        return CombatEncounter.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(new Combatant(CombatantKind.PLAYER, pid, "Aria", 18, 0)))
                .activeIndex(0).round(1).gridState(grid).build();
    }

    @Test
    void aoeSpellHitsOnlyEnemiesInsideTheTemplate() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();   // inside the sphere
        UUID bId = UUID.randomUUID();   // far outside
        CombatEncounter enc = aoeEncounter(pid, aId, 10, 5, bId, 0, 0);
        Enemy inside = enemyAt(aId, "Goblin A");
        Enemy outside = enemyAt(bId, "Goblin B");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(playerStateService.getState(pid)).thenReturn(stateWithCantrip(pid, "Fireball"));
        when(playerStateService.getSessionStates(sessionId)).thenReturn(List.of(stateFor(pid, 20)));
        when(spellCatalog.effect("Fireball")).thenReturn(Optional.of(damageSpell("Fireball", "sphere", 20)));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(inside, outside));
        when(enemyRepo.findById(aId)).thenReturn(Optional.of(inside)); // phase-2 re-fetch
        when(diceService.roll("2d6")).thenReturn(res(8));

        // Origin on the inside enemy; client targetIds deliberately wrong (names the outside enemy)
        // to prove the server overrides them for an AoE.
        combat.playerCastSpell(sessionId, "Aria", "Fireball", 0, List.of(bId), 10, 5);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: roll the spell damage

        assertEquals(12, inside.getCurrentHp(), "enemy inside the template takes the AoE damage");
        assertEquals(20, outside.getCurrentHp(), "enemy outside the template is untouched");
        assertTrue(outside.isAlive());
    }

    @Test
    void nonAoeSpellWithoutOriginUsesClientTargetIds() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CombatEncounter enc = aoeEncounter(pid, aId, 10, 5, bId, 0, 0);
        Enemy enemyA = enemyAt(aId, "Goblin A");
        Enemy enemyB = enemyAt(bId, "Goblin B");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(playerStateService.getState(pid)).thenReturn(stateWithCantrip(pid, "Firebolt"));
        when(playerStateService.getSessionStates(sessionId)).thenReturn(List.of(stateFor(pid, 20)));
        when(spellCatalog.effect("Firebolt")).thenReturn(Optional.of(damageSpell("Firebolt", null, 0)));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(enemyA, enemyB));
        when(enemyRepo.findById(bId)).thenReturn(Optional.of(enemyB)); // phase-2 re-fetch
        when(diceService.roll("2d6")).thenReturn(res(8));

        // No origin → single-target: targetIds drive selection (enemy B), enemy A untouched.
        combat.playerCastSpell(sessionId, "Aria", "Firebolt", 0, List.of(bId), null, null);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: roll the spell damage

        assertEquals(20, enemyA.getCurrentHp(), "non-targeted enemy is untouched");
        assertEquals(12, enemyB.getCurrentHp(), "the client-selected target takes the damage");
    }

    @Test
    void aoeSpellCatchingNoEnemiesFizzles() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        // Both enemy tokens sit far from the cast point — nobody is inside the template.
        CombatEncounter enc = aoeEncounter(pid, aId, 0, 0, bId, 1, 1);
        Enemy enemyA = enemyAt(aId, "Goblin A");
        Enemy enemyB = enemyAt(bId, "Goblin B");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(playerStateService.getState(pid)).thenReturn(stateWithCantrip(pid, "Fireball"));
        when(playerStateService.getSessionStates(sessionId)).thenReturn(List.of(stateFor(pid, 20)));
        when(spellCatalog.effect("Fireball")).thenReturn(Optional.of(damageSpell("Fireball", "sphere", 20)));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(enemyA, enemyB));

        // Origin far from both enemies; client targetIds names one — the AoE must still fizzle.
        combat.playerCastSpell(sessionId, "Aria", "Fireball", 0, List.of(aId), 15, 10);

        assertEquals(20, enemyA.getCurrentHp(), "an AoE that catches no one deals no damage");
        assertEquals(20, enemyB.getCurrentHp(), "client targetIds must not revive a fizzled AoE");
    }

    /** A save-gated CONTROL spell (concentration). */
    private SpellEffect controlSpell(String name, String saveAbility, String condition) {
        return new SpellEffect(name, 1, SpellEffectType.CONTROL, SpellTargetType.ENEMY,
                SpellResolution.SAVE, saveAbility, null, null, null, false, false, null, null,
                null, 0, null, 1, condition, true, true, "Action", "60 feet", null, null);
    }

    @Test
    void controlSpellTagsEnemyWithStructuredConditionOnFailedSave() {
        UUID pid = UUID.randomUUID();
        UUID charId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CombatEncounter enc = aoeEncounter(pid, aId, 10, 5, bId, 0, 0);
        Enemy target = enemyAt(aId, "Goblin A");
        Enemy bystander = enemyAt(bId, "Goblin B");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria"))
                .thenReturn(Optional.of(playerWithChar(pid, "Aria", charId)));
        when(characterRepo.findById(charId)).thenReturn(Optional.of(charWithDex(10)));
        when(playerStateService.getState(pid)).thenReturn(stateWithCantrip(pid, "Entangle"));
        when(playerStateService.getSessionStates(sessionId)).thenReturn(List.of(stateFor(pid, 20)));
        when(playerStateService.breakConcentration(eq(sessionId), any())).thenReturn(List.of());
        when(playerStateService.setConcentratingSpell(eq(pid), anyString())).thenReturn(stateFor(pid, 20));
        when(spellCatalog.effect("Entangle")).thenReturn(Optional.of(controlSpell("Entangle", "STR", "restrained")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(target, bystander));
        // Failed save (low roll) → the condition lands.
        when(diceService.roll("1d20", RollMode.NORMAL)).thenReturn(res(2));

        // Cast as a cantrip-level invocation (spellLevel 0) to avoid the slot-spend mock.
        combat.playerCastSpell(sessionId, "Aria", "Entangle", 0, List.of(aId), null, null);

        assertTrue(target.getConditions().stream().anyMatch(c -> "restrained".equals(c.name())),
                "the failed-save target is restrained");
        assertEquals(pid, target.getConditions().get(0).sourceCasterId(),
                "the condition records its caster (player id) for concentration tracking");
        assertTrue(target.getConditions().get(0).concentration(),
                "Entangle is a concentration effect");
        assertTrue(bystander.getConditions().isEmpty(), "the untargeted enemy is unaffected");
    }

    /* ── Action economy: attack/cast spend the economy but no longer auto-end the turn ── */

    /** A concentration AREA spell that also stamps difficult terrain (e.g. Entangle). */
    private SpellEffect terrainSpell(String name) {
        return new SpellEffect(name, 1, SpellEffectType.CONTROL, SpellTargetType.AREA,
                SpellResolution.SAVE, "STR", null, null, null, false, false, null, null,
                "cube", 10, null, 1, "restrained", true, true, "Action", "90 feet", "DIFFICULT", null);
    }

    @Test
    void terrainSpellStampsDifficultTerrainZone() {
        UUID pid = UUID.randomUUID();
        UUID charId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CombatEncounter enc = aoeEncounter(pid, aId, 10, 5, bId, 0, 0);

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria"))
                .thenReturn(Optional.of(playerWithChar(pid, "Aria", charId)));
        when(characterRepo.findById(charId)).thenReturn(Optional.of(charWithDex(10)));
        when(playerStateService.getState(pid)).thenReturn(stateWithCantrip(pid, "Entangle"));
        when(playerStateService.getSessionStates(sessionId)).thenReturn(List.of(stateFor(pid, 20)));
        when(playerStateService.breakConcentration(eq(sessionId), any())).thenReturn(List.of());
        when(playerStateService.setConcentratingSpell(eq(pid), anyString())).thenReturn(stateFor(pid, 20));
        when(spellCatalog.effect("Entangle")).thenReturn(Optional.of(terrainSpell("Entangle")));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(enemyAt(aId, "Goblin A")));

        // Place the 10-ft cube (2×2) at (5,5).
        combat.playerCastSpell(sessionId, "Aria", "Entangle", 0, List.of(), 5, 5);

        GridState grid = enc.getGridState();
        long difficult = grid.getTerrain().stream()
                .filter(t -> t.type() == TerrainType.DIFFICULT).count();
        assertEquals(4, difficult, "a 10-ft cube stamps a 2×2 block of difficult terrain");
        assertEquals(1, grid.getZones().size(), "a terrain zone is recorded for cleanup");
        assertTrue(grid.getZones().get(0).concentration(), "Entangle's terrain is concentration-bound");
        assertEquals(null, grid.getZones().get(0).expiresAtRound(),
                "concentration terrain has no fixed expiry");
    }

    @Test
    void gridAttackSpendsActionKeepsTurnAndBlocksSecondAction() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CombatEncounter enc = aoeEncounter(pid, aId, 6, 5, bId, 0, 0);
        Enemy goblin = enemyAt(aId, "Goblin A");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findById(aId)).thenReturn(Optional.of(goblin));
        when(diceService.roll("1d20+2")).thenReturn(res(3)); // 3+2=5 misses AC 12 → enemy survives

        combat.playerAttack(sessionId, "Aria", aId);

        Token tok = enc.getGridState().getTokens().get(pid.toString());
        assertTrue(tok.isActionUsed(), "attacking spends the action");
        assertFalse(tok.isBonusActionUsed(), "attacking leaves the bonus action available");
        assertEquals(0, enc.getActiveIndex(), "the turn stays with the player (no auto-advance)");

        // A second action this turn is rejected.
        assertThrows(IllegalStateException.class, () -> combat.playerAttack(sessionId, "Aria", aId));
    }

    /** A SELF BUFF cast as a Bonus Action — spends the bonus action, not the action. */
    private SpellEffect bonusSelfBuff(String name) {
        return new SpellEffect(name, 1, SpellEffectType.BUFF, SpellTargetType.SELF,
                SpellResolution.AUTO, null, null, null, null, false, false, null, null,
                null, 0, null, 1, "blessed", false, true, "Bonus Action", "Self", null, null);
    }

    /** Runtime state carrying a single named weapon (drives attack-range inference). */
    private PlayerRuntimeStateDto stateWithWeapon(UUID playerId, String weapon) {
        return new PlayerRuntimeStateDto(playerId, 20, 20, 0, 10, java.util.Map.of(),
                List.of(), List.of(new InventoryItem(weapon, 1, ItemKind.WEAPON)),
                List.of(), List.of(), List.of(), false, 0, 0, false, false, null);
    }

    @Test
    void meleeAttackOnOutOfRangeEnemyIsRejected() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        // Player token at (5,5); enemy A at (12,5) → 35 ft away, beyond a 5 ft melee reach.
        CombatEncounter enc = aoeEncounter(pid, aId, 12, 5, bId, 0, 0);
        Enemy goblin = enemyAt(aId, "Goblin A");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findById(aId)).thenReturn(Optional.of(goblin));
        when(playerStateService.getState(pid)).thenReturn(stateFor(pid, 20)); // no weapons → 5 ft melee

        assertThrows(IllegalStateException.class, () -> combat.playerAttack(sessionId, "Aria", aId));
        assertEquals(20, goblin.getCurrentHp(), "an out-of-reach melee attack deals no damage");
    }

    @Test
    void rangedWeaponCanAttackAtRange() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CombatEncounter enc = aoeEncounter(pid, aId, 12, 5, bId, 0, 0); // 35 ft away
        Enemy goblin = enemyAt(aId, "Goblin A");

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findById(aId)).thenReturn(Optional.of(goblin));
        when(playerStateService.getState(pid)).thenReturn(stateWithWeapon(pid, "Longbow")); // 150 ft
        when(diceService.roll("1d20+2")).thenReturn(res(20)); // hits AC 12
        when(diceService.roll("1d8")).thenReturn(res(5));

        combat.playerAttack(sessionId, "Aria", aId);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: roll the damage

        assertEquals(15, goblin.getCurrentHp(), "a longbow reaches a 35 ft target");
    }

    /** A natural-20 d20 result (crit flag set), for exercising crit-damage doubling. */
    private DiceRollResult crit20() {
        return new DiceRollResult("n", 1, 20, 0, RollMode.NORMAL,
                List.of(20), null, 20, true, false);
    }

    /** Build a no-grid ACTIVE encounter with the player active and one enemy in initiative. */
    private CombatEncounter noGridEncounter(UUID pid, UUID enemyId) {
        return CombatEncounter.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).status(CombatStatus.ACTIVE)
                .initiativeOrder(List.of(
                        new Combatant(CombatantKind.PLAYER, pid, "Aria", 18, 0),
                        new Combatant(CombatantKind.ENEMY, enemyId, "Goblin", 5, 2)))
                .activeIndex(0).round(1).build();
    }

    @Test
    void weaponDamageUsesTheEquippedWeaponDie() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = noGridEncounter(pid, enemyId);
        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(20).currentHp(20).armorClass(10)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));
        when(playerStateService.getState(pid)).thenReturn(stateWithWeapon(pid, "Greatsword"));
        when(diceService.roll("1d20+2")).thenReturn(res(20)); // hits AC 10 (not a crit)
        when(diceService.roll("2d6")).thenReturn(res(9));      // a greatsword rolls 2d6

        combat.playerAttack(sessionId, "Aria", enemyId);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: roll the damage

        assertEquals(11, goblin.getCurrentHp(), "greatsword deals its 2d6 die, not a flat 1d8");
        verify(diceService).roll("2d6");
    }

    @Test
    void weaponDamageFallsBackToOneD4WhenUnarmed() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = noGridEncounter(pid, enemyId);
        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(20).currentHp(20).armorClass(10)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));
        when(playerStateService.getState(pid)).thenReturn(stateFor(pid, 20)); // no weapon carried
        when(diceService.roll("1d20+2")).thenReturn(res(20));
        when(diceService.roll("1d4")).thenReturn(res(3));

        combat.playerAttack(sessionId, "Aria", enemyId);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: roll the damage

        assertEquals(17, goblin.getCurrentHp(), "an unarmed strike falls back to 1d4");
        verify(diceService).roll("1d4");
    }

    @Test
    void criticalHitDoublesTheWeaponDamageDice() {
        UUID pid = UUID.randomUUID();
        UUID enemyId = UUID.randomUUID();
        CombatEncounter enc = noGridEncounter(pid, enemyId);
        Enemy goblin = Enemy.builder().id(enemyId).sessionId(sessionId)
                .name("Goblin").maxHp(30).currentHp(30).armorClass(10)
                .attackBonus(4).damageDice("1d6+2").initiative(5).alive(true).build();

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(enemyRepo.findById(enemyId)).thenReturn(Optional.of(goblin));
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(goblin));
        when(playerStateService.getState(pid)).thenReturn(stateWithWeapon(pid, "Longsword")); // 1d8
        when(diceService.roll("1d20+2")).thenReturn(crit20());  // natural 20 → crit
        when(diceService.roll("2d8")).thenReturn(res(12));       // the doubled dice

        combat.playerAttack(sessionId, "Aria", enemyId);
        combat.resolvePlayerDamage(sessionId, "Aria"); // phase 2: roll the (doubled) crit damage

        assertEquals(18, goblin.getCurrentHp(), "a crit rolls 2d8, not 1d8");
        verify(diceService).roll("2d8");
        verify(diceService, never()).roll("1d8");
    }

    @Test
    void bonusActionSpellSpendsBonusAndLeavesActionAvailable() {
        UUID pid = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CombatEncounter enc = aoeEncounter(pid, aId, 10, 5, bId, 0, 0);

        when(encounterRepo.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)).thenReturn(Optional.of(enc));
        when(playerRepo.findBySessionIdAndUsername(sessionId, "Aria")).thenReturn(Optional.of(player(pid, "Aria")));
        when(playerStateService.getState(pid)).thenReturn(stateWithCantrip(pid, "Inner Light"));
        when(playerStateService.applyCondition(eq(pid), any())).thenReturn(stateFor(pid, 20));
        when(spellCatalog.effect("Inner Light")).thenReturn(Optional.of(bonusSelfBuff("Inner Light")));

        combat.playerCastSpell(sessionId, "Aria", "Inner Light", 0, List.of(), null, null);

        Token tok = enc.getGridState().getTokens().get(pid.toString());
        assertTrue(tok.isBonusActionUsed(), "a Bonus-Action spell spends the bonus action");
        assertFalse(tok.isActionUsed(), "the action is still available after a bonus-action spell");
        assertEquals(0, enc.getActiveIndex(), "the turn stays with the player");
    }
}
