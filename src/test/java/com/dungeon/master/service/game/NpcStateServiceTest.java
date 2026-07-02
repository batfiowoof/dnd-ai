package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.entity.NpcState;
import com.dungeon.master.model.entity.World;
import com.dungeon.master.repository.NpcStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Seeding from authored baselines and clamped disposition adjustments. */
class NpcStateServiceTest {

    private NpcStateRepository repository;
    private NpcStateService service;
    private final UUID sessionId = UUID.randomUUID();

    private static WorldNpc npc(String name, Integer disposition) {
        return new WorldNpc(name, "", "", "", "", "", "", "", disposition);
    }

    @BeforeEach
    void setup() {
        repository = mock(NpcStateRepository.class);
        service = new NpcStateService(repository);
    }

    @Test
    void seedsOneRowPerNpcFromTheAuthoredBaseline() {
        World world = World.builder()
                .npcs(List.of(npc("Marlena", 30), npc("Harbourmaster", -70), npc("  ", 0)))
                .build();

        service.seedForSession(sessionId, world);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NpcState>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<NpcState> rows = captor.getValue();

        assertEquals(2, rows.size(), "blank-named NPCs are skipped");
        NpcState marlena = rows.stream().filter(r -> r.getNpcName().equals("Marlena")).findFirst().orElseThrow();
        assertEquals(30, marlena.getDisposition());
        assertEquals(30, marlena.getBaseline(), "current seeds from the authored baseline");
    }

    @Test
    void adjustClampsAndReportsTheNewBand() {
        NpcState row = NpcState.builder()
                .sessionId(sessionId).npcName("Marlena").disposition(40).baseline(40).build();
        when(repository.findBySessionIdAndNpcNameIgnoreCase(eq(sessionId), anyString()))
                .thenReturn(Optional.of(row));

        // +80 from 40 clamps at 100 → Devoted.
        NpcStateService.AdjustResult up = service.adjust(sessionId, "marlena", 80);
        assertTrue(up.found());
        assertTrue(up.changed());
        assertEquals(100, up.disposition());
        assertEquals("Devoted", up.band());

        // A missing NPC is reported, not created.
        when(repository.findBySessionIdAndNpcNameIgnoreCase(eq(sessionId), anyString()))
                .thenReturn(Optional.empty());
        NpcStateService.AdjustResult missing = service.adjust(sessionId, "Nobody", 10);
        assertFalse(missing.found());
    }
}
