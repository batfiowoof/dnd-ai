package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.exception.SessionFullException;
import com.dungeon.master.kafka.event.SessionEvent;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.mapper.PlayerMapper;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns session membership changes — joining, host-initiated removal, and self-leave — kept apart
 * from {@link GameSessionService}'s lifecycle and query responsibilities. Defers session lookup to
 * {@link GameSessionService#getSession(UUID)} so the not-found behavior stays in one place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMembershipService {

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final PlayerStateService playerStateService;
    private final GameEventProducer eventProducer;
    private final PlayerMapper playerMapper;
    private final GameSessionService gameSessionService;

    @Transactional
    public PlayerDto joinSession(UUID sessionId, JoinSessionRequest request, String username) {
        GameSession session = gameSessionService.getSession(sessionId);

        // Existing member → idempotent rejoin: return their player as-is, regardless of the session's
        // status or capacity. A rejoin is a pure read — it must NOT re-seed runtime state (that would
        // wipe HP/spell slots), re-emit PLAYER_JOINED, or touch the turn order, and it ignores any
        // characterId in the request (no mid-game character swap). This is what lets a player who
        // refreshed or navigated away get back into an already-ACTIVE session. The WAITING/full guards
        // below therefore apply only to genuinely NEW joiners.
        Optional<Player> existing = playerRepository.findBySessionIdAndUsername(sessionId, username);
        if (existing.isPresent()) {
            log.info("Player {} rejoined session {}", username, sessionId);
            return playerMapper.toDto(existing.get());
        }

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Session is not accepting new players");
        }

        long humanPlayerCount = playerRepository.countBySessionIdAndRole(sessionId, PlayerRole.PLAYER);
        if (humanPlayerCount >= session.getMaxPlayers()) {
            throw new SessionFullException(
                    "Session is full (max " + session.getMaxPlayers() + " players)");
        }

        Character character = characterRepository.findByIdAndOwnerUsername(request.characterId(), username)
                .orElseThrow(() -> new IllegalArgumentException("Character not found or not owned by you"));

        Player player = Player.builder()
                .username(username)
                .characterName(character.getName())
                .characterId(character.getId())
                .sessionId(sessionId)
                .role(PlayerRole.PLAYER)
                .turnIndex(session.getTurnOrder().size())
                .build();
        player = playerRepository.save(player);
        playerStateService.seedForPlayer(player, character);

        session.getTurnOrder().add(player.getId());
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, player.getId(), SessionEvent.Type.PLAYER_JOINED));

        log.info("Player {} joined session {}", username, sessionId);
        return playerMapper.toDto(player);
    }

    @Transactional
    public void removePlayer(UUID sessionId, UUID playerId, String requestingUsername) {
        GameSession session = gameSessionService.getSession(sessionId);

        if (!requestingUsername.equals(session.getCreatedBy())) {
            throw new IllegalStateException("Only the session creator can remove players");
        }

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Cannot remove players after the session has started");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));

        if (!player.getSessionId().equals(sessionId)) {
            throw new IllegalStateException("Player does not belong to this session");
        }

        if (player.getRole() == PlayerRole.DM_AI) {
            throw new IllegalStateException("Cannot remove the AI Dungeon Master");
        }

        if (player.getUsername().equals(requestingUsername)) {
            throw new IllegalStateException("Cannot remove yourself from the session");
        }

        session.getTurnOrder().remove(playerId);
        reindexRemainingPlayers(sessionId, playerId);
        sessionRepository.save(session);
        playerRepository.delete(player);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, playerId, SessionEvent.Type.PLAYER_LEFT));

        log.info("Player {} removed from session {} by {}", playerId, sessionId, requestingUsername);
    }

    /** The calling user leaves a session. Creators must delete the session instead. */
    @Transactional
    public void leaveSession(UUID sessionId, String username) {
        GameSession session = gameSessionService.getSession(sessionId);

        if (username.equals(session.getCreatedBy())) {
            throw new IllegalStateException("The session creator must delete the session, not leave it");
        }

        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException("You are not in this session"));

        UUID playerId = player.getId();
        session.getTurnOrder().remove(playerId);
        if (playerId.equals(session.getCurrentTurnPlayerId())) {
            session.setCurrentTurnPlayerId(
                    session.getTurnOrder().isEmpty() ? null : session.getTurnOrder().get(0));
        }

        reindexRemainingPlayers(sessionId, playerId);
        sessionRepository.save(session);
        playerRepository.delete(player);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, playerId, SessionEvent.Type.PLAYER_LEFT));

        log.info("Player {} left session {}", username, sessionId);
    }

    /**
     * Re-index the remaining PLAYER-role players' turn order after one leaves, so {@code turnIndex}
     * stays a contiguous 0..n-1 sequence. Excludes {@code removedPlayerId} (it may not be deleted
     * from the repository yet).
     */
    private void reindexRemainingPlayers(UUID sessionId, UUID removedPlayerId) {
        List<Player> remaining = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> !p.getId().equals(removedPlayerId) && p.getRole() == PlayerRole.PLAYER)
                .toList();
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setTurnIndex(i);
            playerRepository.save(remaining.get(i));
        }
    }
}
