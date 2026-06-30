package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.AbilityScoreImprovement;
import com.dungeon.master.model.dto.CharacterDto;
import com.dungeon.master.model.dto.CharacterLevelUpRequest;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.repository.CharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Milestone (choices-deferred) advance and the pending-choice resolution. */
class CharacterServiceTest {

    private CharacterRepository repository;
    private Dnd5eReferenceService reference;
    private CharacterService service;

    @BeforeEach
    void setup() {
        repository = mock(CharacterRepository.class);
        reference = mock(Dnd5eReferenceService.class);
        service = new CharacterService(repository, reference);
        when(repository.save(any(Character.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reference.getClass("wizard")).thenReturn(Optional.of(Map.of("hitDie", 6)));
        when(reference.getClass("fighter")).thenReturn(Optional.of(Map.of("hitDie", 10)));
    }

    private Character character(String clazz, int level, int con, int hp) {
        return Character.builder()
                .ownerUsername("u").name("Hero").race("Human").characterClass(clazz)
                .level(level).constitution(con).hitPoints(hp).build();
    }

    @Test
    void milestoneAdvanceAddsFixedHpAndDefersCasterChoice() {
        Character wiz = character("Wizard", 1, 14, 8); // d6 → +4, CON +2

        Character out = service.applyMilestoneLevel(wiz);

        assertEquals(2, out.getLevel());
        assertEquals(14, out.getHitPoints()); // 8 + (4 + 2)
        assertEquals(2, out.getProficiencyBonus());
        assertTrue(out.getPendingChoiceLevels().contains(2), "a caster owes a spell pick each level");
    }

    @Test
    void milestoneAdvanceDefersAsiForMartialOnlyAtAsiLevels() {
        Character fighter = character("Fighter", 3, 14, 28); // → level 4 is an ASI level

        Character toFour = service.applyMilestoneLevel(fighter);
        assertEquals(4, toFour.getLevel());
        assertTrue(toFour.getPendingChoiceLevels().contains(4), "ASI level owes a choice");

        Character toFive = service.applyMilestoneLevel(toFour); // level 5: not ASI, martial
        assertEquals(5, toFive.getLevel());
        assertFalse(toFive.getPendingChoiceLevels().contains(5), "no choice owed at a plain martial level");
    }

    @Test
    void milestoneAdvanceIsNoOpAtMaxLevel() {
        Character maxed = character("Fighter", 20, 14, 200);
        Character out = service.applyMilestoneLevel(maxed);
        assertEquals(20, out.getLevel());
        assertEquals(200, out.getHitPoints());
    }

    @Test
    void pendingChoicesApplyAsiAndClearTheLevel() {
        UUID id = UUID.randomUUID();
        Character fighter = character("Fighter", 4, 14, 36);
        fighter.setPendingChoiceLevels(new java.util.ArrayList<>(List.of(4)));
        when(repository.findByIdAndOwnerUsername(id, "u")).thenReturn(Optional.of(fighter));

        CharacterLevelUpRequest req = new CharacterLevelUpRequest(
                new AbilityScoreImprovement(AbilityScoreImprovement.AsiMode.PLUS_TWO, "strength", null),
                List.of(), List.of());

        CharacterDto dto = service.applyPendingChoices(id, req, "u");

        assertEquals(12, dto.strength()); // 10 + 2
        assertEquals(4, dto.level(), "resolving choices never changes the level");
        assertTrue(dto.pendingChoiceLevels().isEmpty());
    }
}
