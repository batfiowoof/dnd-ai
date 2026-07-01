package com.dungeon.master.service.game;

import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.mapper.PlayerMapper;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionMembershipServiceTest {

    private GameSessionRepository sessionRepository;
    private PlayerRepository playerRepository;
    private CharacterRepository characterRepository;
    private PlayerStateService playerStateService;
    private GameEventProducer eventProducer;
    private PlayerMapper playerMapper;
    private GameSessionService gameSessionService;

    private SessionMembershipService service;

    private final UUID sessionId = UUID.randomUUID();
    private final String username = "alice";

    @BeforeEach
    void setup() {
        sessionRepository = mock(GameSessionRepository.class);
        playerRepository = mock(PlayerRepository.class);
        characterRepository = mock(CharacterRepository.class);
        playerStateService = mock(PlayerStateService.class);
        eventProducer = mock(GameEventProducer.class);
        playerMapper = mock(PlayerMapper.class);
        gameSessionService = mock(GameSessionService.class);
        service = new SessionMembershipService(sessionRepository, playerRepository, characterRepository,
                playerStateService, eventProducer, playerMapper, gameSessionService);
    }

    private GameSession session(GameStatus status) {
        GameSession s = mock(GameSession.class);
        when(s.getStatus()).thenReturn(status);
        when(gameSessionService.getSession(sessionId)).thenReturn(s);
        return s;
    }

    /* ── rejoin: an existing member can re-enter an already-ACTIVE session ─────────── */

    @Test
    void existingMemberRejoinsActiveSessionIdempotently() {
        session(GameStatus.ACTIVE);
        Player existing = mock(Player.class);
        PlayerDto dto = new PlayerDto(UUID.randomUUID(), username, "Aragorn",
                PlayerRole.PLAYER, 0, UUID.randomUUID(), null, null);
        when(playerRepository.findBySessionIdAndUsername(sessionId, username))
                .thenReturn(Optional.of(existing));
        when(playerMapper.toDto(existing)).thenReturn(dto);

        PlayerDto result = service.joinSession(
                sessionId, new JoinSessionRequest(username, UUID.randomUUID()), username);

        assertSame(dto, result, "rejoin returns the existing player's DTO");
        // A rejoin is a pure read — no new row, no re-seed, no PLAYER_JOINED, no turn-order change.
        verify(playerRepository, never()).save(any(Player.class));
        verify(playerStateService, never()).seedForPlayer(any(), any());
        verify(eventProducer, never()).sendSessionEvent(any());
    }

    /* ── guard: a genuinely NEW player still cannot join an ACTIVE session ─────────── */

    @Test
    void newPlayerCannotJoinActiveSession() {
        session(GameStatus.ACTIVE);
        when(playerRepository.findBySessionIdAndUsername(sessionId, username))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.joinSession(sessionId, new JoinSessionRequest(username, UUID.randomUUID()), username));
        assertEquals("Session is not accepting new players", ex.getMessage());
        verify(playerRepository, never()).save(any(Player.class));
    }
}
