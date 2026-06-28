package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.entity.PlayerRuntimeState;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.repository.PlayerRuntimeStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerStateServiceTest {

    private PlayerRuntimeStateRepository repository;
    private PlayerStateService service;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setup() {
        repository = mock(PlayerRuntimeStateRepository.class);
        service = new PlayerStateService(repository, mock(DiceService.class));
        when(repository.save(any(PlayerRuntimeState.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PlayerRuntimeState seed(int hp, int maxHp) {
        PlayerRuntimeState s = PlayerRuntimeState.builder()
                .playerId(id).sessionId(UUID.randomUUID())
                .currentHp(hp).maxHp(maxHp).tempHp(0).build();
        when(repository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private DiceRollResult d20(int total, boolean crit, boolean fumble) {
        return new DiceRollResult("1d20", 1, 20, 0, RollMode.NORMAL,
                List.of(total), null, total, crit, fumble);
    }

    /* ── damage / dying ──────────────────────────────────────────── */

    @Test
    void droppingToZeroSetsDyingNotDead() {
        PlayerRuntimeState s = seed(8, 20);
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, -8);
        assertEquals(0, dto.currentHp());
        assertFalse(dto.dead(), "a clean drop to 0 is dying, not dead");
        assertFalse(dto.stable());
        assertEquals(0, dto.deathSaveFailures());
    }

    @Test
    void massiveDamageIsInstantDeath() {
        seed(8, 20);
        // leftover past 0 = 30 - 8 = 22 ≥ maxHp 20 → instant death
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, -30);
        assertEquals(0, dto.currentHp());
        assertTrue(dto.dead(), "leftover damage ≥ maxHp is instant death");
    }

    @Test
    void damageAtZeroAddsOneFailure() {
        seed(0, 20);
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, -3);
        assertEquals(1, dto.deathSaveFailures());
        assertFalse(dto.dead());
    }

    @Test
    void criticalDamageAtZeroAddsTwoFailures() {
        seed(0, 20);
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, -3, true);
        assertEquals(2, dto.deathSaveFailures());
        assertFalse(dto.dead());
    }

    @Test
    void threeFailuresFromDamageMeansDead() {
        PlayerRuntimeState s = seed(0, 20);
        s.setDeathSaveFailures(2);
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, -3);
        assertEquals(3, dto.deathSaveFailures());
        assertTrue(dto.dead());
    }

    @Test
    void damagingAStableCreatureMakesItDyingAgain() {
        PlayerRuntimeState s = seed(0, 20);
        s.setStable(true);
        s.setDeathSaveSuccesses(3);   // realistic post-natural-stabilization state
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, -3);
        assertFalse(dto.stable(), "a stable creature taking damage reverts to dying");
        assertEquals(0, dto.deathSaveSuccesses(), "re-downing resets the success counter");
        assertEquals(1, dto.deathSaveFailures());
    }

    /* ── death saves ─────────────────────────────────────────────── */

    @Test
    void deathSaveSuccessOnTenPlus() {
        seed(0, 20);
        PlayerStateService.DeathSaveResult r = service.recordDeathSave(id, d20(14, false, false));
        assertEquals(PlayerStateService.DeathSaveOutcome.SUCCESS, r.outcome());
        assertEquals(1, r.state().deathSaveSuccesses());
    }

    @Test
    void deathSaveFailureOnNineOrLess() {
        seed(0, 20);
        PlayerStateService.DeathSaveResult r = service.recordDeathSave(id, d20(9, false, false));
        assertEquals(PlayerStateService.DeathSaveOutcome.FAILURE, r.outcome());
        assertEquals(1, r.state().deathSaveFailures());
    }

    @Test
    void naturalTwentyRevivesAtOneHp() {
        seed(0, 20);
        PlayerStateService.DeathSaveResult r = service.recordDeathSave(id, d20(20, true, false));
        assertEquals(PlayerStateService.DeathSaveOutcome.REVIVED, r.outcome());
        assertEquals(1, r.state().currentHp());
        assertFalse(r.state().stable());
        assertEquals(0, r.state().deathSaveSuccesses());
        assertEquals(0, r.state().deathSaveFailures());
    }

    @Test
    void naturalOneIsTwoFailures() {
        seed(0, 20);
        PlayerStateService.DeathSaveResult r = service.recordDeathSave(id, d20(1, false, true));
        assertEquals(PlayerStateService.DeathSaveOutcome.FAILURE, r.outcome());
        assertEquals(2, r.state().deathSaveFailures());
    }

    @Test
    void thirdSuccessStabilizes() {
        PlayerRuntimeState s = seed(0, 20);
        s.setDeathSaveSuccesses(2);
        PlayerStateService.DeathSaveResult r = service.recordDeathSave(id, d20(12, false, false));
        assertEquals(PlayerStateService.DeathSaveOutcome.STABILIZED, r.outcome());
        assertTrue(r.state().stable());
        assertEquals(0, r.state().currentHp(), "stable but still at 0 HP");
    }

    @Test
    void thirdFailureDies() {
        PlayerRuntimeState s = seed(0, 20);
        s.setDeathSaveFailures(2);
        PlayerStateService.DeathSaveResult r = service.recordDeathSave(id, d20(5, false, false));
        assertEquals(PlayerStateService.DeathSaveOutcome.DIED, r.outcome());
        assertTrue(r.state().dead());
    }

    /* ── healing ─────────────────────────────────────────────────── */

    @Test
    void healRevivesADyingCreature() {
        PlayerRuntimeState s = seed(0, 20);
        s.setDeathSaveFailures(2);
        s.setStable(true);
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, 6);
        assertEquals(6, dto.currentHp());
        assertFalse(dto.stable());
        assertEquals(0, dto.deathSaveFailures());
        assertFalse(dto.dead());
    }

    @Test
    void healIsANoOpOnTheDead() {
        PlayerRuntimeState s = seed(0, 20);
        s.setDead(true);
        PlayerRuntimeStateDto dto = service.applyHpDelta(id, 10);
        assertEquals(0, dto.currentHp(), "you cannot heal the dead");
        assertTrue(dto.dead());
    }

    /* ── stabilize ───────────────────────────────────────────────── */

    @Test
    void stabilizeMarksADyingCreatureStable() {
        seed(0, 20);
        PlayerRuntimeStateDto dto = service.stabilize(id);
        assertTrue(dto.stable());
        assertEquals(0, dto.currentHp());
    }

    @Test
    void stabilizeRejectsAConsciousTarget() {
        seed(5, 20);
        assertThrows(IllegalStateException.class, () -> service.stabilize(id));
    }

    @Test
    void stabilizeRejectsADeadTarget() {
        PlayerRuntimeState s = seed(0, 20);
        s.setDead(true);
        assertThrows(IllegalStateException.class, () -> service.stabilize(id));
    }

    /* ── long rest ───────────────────────────────────────────────── */

    @Test
    void longRestClearsDyingButLeavesTheDeadDead() {
        PlayerRuntimeState dead = PlayerRuntimeState.builder()
                .playerId(id).sessionId(UUID.randomUUID())
                .currentHp(0).maxHp(20).dead(true).deathSaveFailures(3).build();
        when(repository.findById(id)).thenReturn(Optional.of(dead));
        PlayerRuntimeStateDto dto = service.longRest(id);
        assertTrue(dto.dead(), "a long rest does not raise the dead");
        assertEquals(0, dto.currentHp());
    }
}
