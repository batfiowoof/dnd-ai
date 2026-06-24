package com.dungeon.master.service.game;

import com.dungeon.master.exception.NotYourTurnException;
import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.kafka.event.DmResponseEvent;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.kafka.event.TurnNextEvent;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.repository.TurnEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TurnService {

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final TurnEventRepository turnEventRepository;
    private final CombatEncounterRepository combatEncounterRepository;
    private final GameEventProducer eventProducer;

    /**
     * Validate that it is {@code username}'s turn in an ACTIVE session and return
     * their player. Used by mechanical actions (cast/use-item) that must gate on
     * turn ownership before mutating authoritative state.
     */
    public Player requireActiveTurn(UUID sessionId, String username) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        if (session.getStatus() != GameStatus.ACTIVE) {
            throw new IllegalStateException("Game is not active");
        }
        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "Player not found: " + username + " in session " + sessionId));
        if (!player.getId().equals(session.getCurrentTurnPlayerId())) {
            throw new NotYourTurnException("It's not your turn, " + username);
        }
        return player;
    }

    @Transactional
    public void submitAction(UUID sessionId, String username, String action) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));

        if (session.getStatus() != GameStatus.ACTIVE) {
            throw new IllegalStateException("Game is not active");
        }

        if (combatEncounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("Combat is in progress — use combat actions");
        }

        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "Player not found: " + username + " in session " + sessionId));

        if (!player.getId().equals(session.getCurrentTurnPlayerId())) {
            throw new NotYourTurnException(
                    "It's not your turn, " + username + ". Current turn: " + session.getCurrentTurnPlayerId());
        }

        int nextTurnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .map(e -> e.getTurnNumber() + 1)
                .orElse(1);

        TurnEvent turnEvent = TurnEvent.builder()
                .sessionId(sessionId)
                .playerId(player.getId())
                .action(action)
                .turnNumber(nextTurnNumber)
                .build();
        turnEventRepository.save(turnEvent);

        PlayerActionEvent kafkaEvent = new PlayerActionEvent(
                sessionId, player.getId(), player.getCharacterName(), action, nextTurnNumber);
        eventProducer.sendPlayerAction(kafkaEvent);

        log.info("Action submitted: session={}, player={}, turn={}", sessionId, username, nextTurnNumber);
    }

    @Transactional
    public void recordDmResponse(UUID sessionId, UUID playerId, String dmResponse, int turnNumber) {
        TurnEvent turnEvent = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .orElseThrow(() -> new IllegalStateException("No turn event found for session: " + sessionId));

        turnEvent.setDmResponse(dmResponse);
        turnEventRepository.save(turnEvent);

        DmResponseEvent responseEvent = new DmResponseEvent(
                sessionId, playerId, turnEvent.getAction(), dmResponse, turnNumber);
        eventProducer.sendDmResponse(responseEvent);

        log.info("DM response recorded: session={}, turn={}", sessionId, turnNumber);
    }

    @Transactional
    public UUID advanceTurn(UUID sessionId) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));

        List<UUID> turnOrder = session.getTurnOrder();
        if (turnOrder.isEmpty()) {
            throw new IllegalStateException("No players in turn order");
        }

        UUID currentPlayerId = session.getCurrentTurnPlayerId();
        int currentIndex = turnOrder.indexOf(currentPlayerId);
        int nextIndex = (currentIndex + 1) % turnOrder.size();
        UUID nextPlayerId = turnOrder.get(nextIndex);

        session.setCurrentTurnPlayerId(nextPlayerId);
        sessionRepository.save(session);

        int turnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .map(TurnEvent::getTurnNumber)
                .orElse(0);

        eventProducer.sendTurnNext(new TurnNextEvent(sessionId, nextPlayerId, turnNumber));

        log.info("Turn advanced: session={}, nextPlayer={}", sessionId, nextPlayerId);
        return nextPlayerId;
    }

    public List<TurnEvent> getSessionHistory(UUID sessionId) {
        return turnEventRepository.findBySessionIdOrderByTurnNumberDesc(sessionId);
    }
}
