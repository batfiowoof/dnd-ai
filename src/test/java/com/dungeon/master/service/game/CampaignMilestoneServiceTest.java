package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Authored-milestone validation, one-shot firing, and party-wide advance. */
class CampaignMilestoneServiceTest {

    private GameSessionRepository sessionRepository;
    private PlayerRepository playerRepository;
    private CharacterRepository characterRepository;
    private CharacterService characterService;
    private PlayerStateService playerStateService;
    private CampaignMilestoneService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID charId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        sessionRepository = mock(GameSessionRepository.class);
        playerRepository = mock(PlayerRepository.class);
        characterRepository = mock(CharacterRepository.class);
        characterService = mock(CharacterService.class);
        playerStateService = mock(PlayerStateService.class);
        service = new CampaignMilestoneService(sessionRepository, playerRepository,
                characterRepository, characterService, playerStateService);

        GameSession session = GameSession.builder()
                .id(sessionId)
                .milestones(new ArrayList<>(List.of(new Milestone("boss", "Defeat the Boss", "", false))))
                .build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Player p = Player.builder().sessionId(sessionId).characterId(charId).role(PlayerRole.PLAYER).build();
        when(playerRepository.findBySessionId(sessionId)).thenReturn(List.of(p));

        Character before = Character.builder().name("Hero").characterClass("Fighter").level(3).build();
        when(characterRepository.findById(charId)).thenReturn(Optional.of(before));
        when(characterService.applyMilestoneLevel(any(Character.class))).thenAnswer(inv -> {
            Character c = inv.getArgument(0);
            c.setLevel(c.getLevel() + 1);
            return c;
        });
    }

    @Test
    void awardingAuthoredMilestoneLevelsTheParty() {
        var result = service.completeMilestone(sessionId, "boss");
        assertTrue(result.awarded());
        assertEquals(1, result.leveledCharacters().size());
    }

    @Test
    void awardingTheSameMilestoneTwiceIsRejected() {
        service.completeMilestone(sessionId, "boss");
        var second = service.completeMilestone(sessionId, "boss");
        assertFalse(second.awarded(), "a milestone may only fire once");
        assertEquals("milestone already reached", second.note());
    }

    @Test
    void unknownMilestoneKeyIsRejected() {
        var result = service.completeMilestone(sessionId, "not-a-real-key");
        assertFalse(result.awarded());
        assertTrue(result.leveledCharacters().isEmpty());
    }
}
