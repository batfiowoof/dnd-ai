package com.dungeon.master.service.game;

import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.TurnSource;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.TurnEventRepository;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.ai.DmPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRecapServiceTest {

    private TurnEventRepository turnEventRepository;
    private GameSessionRepository sessionRepository;
    private DmAiService dmAiService;
    private DmPromptBuilder promptBuilder;
    private SessionRecapService service;

    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        turnEventRepository = mock(TurnEventRepository.class);
        sessionRepository = mock(GameSessionRepository.class);
        dmAiService = mock(DmAiService.class);
        promptBuilder = mock(DmPromptBuilder.class);
        service = new SessionRecapService(turnEventRepository, sessionRepository, dmAiService, promptBuilder);
        when(promptBuilder.partyRoster(sessionId)).thenReturn("Aragorn (Fighter)");
    }

    private TurnEvent turn(int n, TurnSource source, String action, String dmResponse) {
        return TurnEvent.builder()
                .sessionId(sessionId).playerId(UUID.randomUUID())
                .turnNumber(n).source(source).action(action).dmResponse(dmResponse)
                .build();
    }

    @Test
    void recapKeepsNarrativeAndCombatOutcomesButDropsBlowByBlow() {
        List<TurnEvent> turns = List.of(
                turn(1, TurnSource.NARRATIVE, "We search the ruins", "You find an old map."),
                turn(2, TurnSource.COMBAT, "Goblin attacks Aragorn for 4 damage.", "The goblin swings."),
                turn(3, TurnSource.COMBAT, "Aragorn strikes — the goblin is defeated.", "It crumples."),
                turn(4, TurnSource.NARRATIVE, "We take the map", "The party heads north."));
        when(turnEventRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)).thenReturn(turns);

        GameSession session = GameSession.builder().build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(dmAiService.generateSessionRecap(eq(sessionId), anyString(), any()))
                .thenReturn("The heroes found a map and slew a goblin.");

        String recap = service.generateAndStore(sessionId, chunk -> { });

        // The LLM is called exactly once, over a source that keeps the story + combat OUTCOMES
        // but not the routine blow-by-blow beat.
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(dmAiService, times(1)).generateSessionRecap(eq(sessionId), source.capture(), any());
        String src = source.getValue();
        assertTrue(src.contains("We search the ruins"), "narrative action kept");
        assertTrue(src.contains("You find an old map."), "narrative DM response kept");
        assertTrue(src.contains("the goblin is defeated"), "consequential combat outcome kept");
        assertTrue(src.contains("Notable combat outcomes"), "outcomes block present");
        assertFalse(src.contains("Goblin attacks Aragorn for 4 damage"),
                "routine combat blow-by-blow excluded");

        // Recap is persisted onto the session.
        assertEquals("The heroes found a map and slew a goblin.", recap);
        assertEquals("The heroes found a map and slew a goblin.", session.getRecap());
        verify(sessionRepository).save(session);
    }

    @Test
    void emptySessionYieldsEmptyRecapAndNoLlmCall() {
        when(turnEventRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)).thenReturn(List.of());

        String recap = service.generateAndStore(sessionId, chunk -> { });

        assertEquals("", recap);
        verify(dmAiService, times(0)).generateSessionRecap(any(), anyString(), any());
    }
}
