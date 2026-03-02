package com.dungeon.master.service.game;

import com.dungeon.master.exception.SessionFullException;
import com.dungeon.master.exception.SessionNotFoundException;
import com.dungeon.master.kafka.event.SessionEvent;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.CreateSessionRequest;
import com.dungeon.master.model.dto.CreateSessionResponse;
import com.dungeon.master.model.dto.GameStateDto;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.repository.TurnEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameSessionService {

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final TurnEventRepository turnEventRepository;
    private final GameEventProducer eventProducer;

    @Transactional
    public CreateSessionResponse createSession(CreateSessionRequest request, String username) {
        String code = generateJoinCode();
        log.info("Creating new game session with code: {}", code);

        GameSession session = GameSession.builder()
                .code(code)
                .status(GameStatus.WAITING)
                .build();
        session = sessionRepository.save(session);

        Player player = Player.builder()
                .username(username)
                .characterName(request.characterName())
                .sessionId(session.getId())
                .role(PlayerRole.PLAYER)
                .turnIndex(0)
                .build();
        player = playerRepository.save(player);

        Player dmPlayer = Player.builder()
                .username("AI_DUNGEON_MASTER")
                .characterName("Dungeon Master")
                .sessionId(session.getId())
                .role(PlayerRole.DM_AI)
                .turnIndex(-1)
                .build();
        playerRepository.save(dmPlayer);

        session.getTurnOrder().add(player.getId());
        session.setCurrentTurnPlayerId(player.getId());
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                session.getId(), player.getId(), SessionEvent.Type.PLAYER_JOINED));

        log.info("Session created: id={}, code={}, creator={}", session.getId(), code, username);
        return new CreateSessionResponse(session.getId(), code, player.getId());
    }

    @Transactional
    public PlayerDto joinSession(UUID sessionId, JoinSessionRequest request, String username) {
        GameSession session = getSession(sessionId);

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Session is not accepting new players");
        }

        long humanPlayerCount = playerRepository.countBySessionIdAndRole(sessionId, PlayerRole.PLAYER);
        if (humanPlayerCount >= session.getMaxPlayers()) {
            throw new SessionFullException(
                    "Session is full (max " + session.getMaxPlayers() + " players)");
        }

        Optional<Player> existing = playerRepository.findBySessionIdAndUsername(sessionId, username);
        if (existing.isPresent()) {
            throw new IllegalStateException("Player already in session");
        }

        Player player = Player.builder()
                .username(username)
                .characterName(request.characterName())
                .sessionId(sessionId)
                .role(PlayerRole.PLAYER)
                .turnIndex(session.getTurnOrder().size())
                .build();
        player = playerRepository.save(player);

        session.getTurnOrder().add(player.getId());
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, player.getId(), SessionEvent.Type.PLAYER_JOINED));

        log.info("Player {} joined session {}", username, sessionId);
        return toPlayerDto(player);
    }

    @Transactional
    public void startSession(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Session cannot be started in current state: " + session.getStatus());
        }

        if (session.getTurnOrder().isEmpty()) {
            throw new IllegalStateException("No players in session");
        }

        session.setStatus(GameStatus.ACTIVE);
        session.setCurrentTurnPlayerId(session.getTurnOrder().get(0));
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, null, SessionEvent.Type.GAME_STARTED));

        log.info("Session {} started with {} players", sessionId, session.getTurnOrder().size());
    }

    @Transactional
    public void endSession(UUID sessionId) {
        GameSession session = getSession(sessionId);
        session.setStatus(GameStatus.FINISHED);
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, null, SessionEvent.Type.GAME_ENDED));

        log.info("Session {} ended", sessionId);
    }

    public GameSession getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
    }

    public GameSession getSessionByCode(String code) {
        return sessionRepository.findByCode(code)
                .orElseThrow(() -> new SessionNotFoundException("Session not found with code: " + code));
    }

    public GameStateDto getGameState(UUID sessionId) {
        GameSession session = getSession(sessionId);
        List<Player> players = playerRepository.findBySessionId(sessionId);
        List<PlayerDto> playerDtos = players.stream()
                .map(this::toPlayerDto)
                .toList();

        int turnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .map(TurnEvent::getTurnNumber)
                .orElse(0);

        return new GameStateDto(
                session.getId(),
                session.getCode(),
                session.getStatus(),
                playerDtos,
                session.getCurrentTurnPlayerId(),
                turnNumber);
    }

    public List<PlayerDto> getPlayers(UUID sessionId) {
        getSession(sessionId);
        return playerRepository.findBySessionId(sessionId).stream()
                .map(this::toPlayerDto)
                .toList();
    }

    private String generateJoinCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }

    private PlayerDto toPlayerDto(Player player) {
        return new PlayerDto(
                player.getId(),
                player.getUsername(),
                player.getCharacterName(),
                player.getRole(),
                player.getTurnIndex());
    }
}
