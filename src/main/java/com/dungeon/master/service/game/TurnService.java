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
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.TurnMode;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PendingCheckRepository;
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
    private final PendingCheckRepository pendingCheckRepository;
    private final RoundCollector roundCollector;
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
        // Turn ownership is only meaningful in initiative-ordered rotation. Collaborative and
        // freeform let any player act at (almost) any time, so don't gate on the pointer there.
        if (session.getTurnMode() == TurnMode.INITIATIVE
                && !player.getId().equals(session.getCurrentTurnPlayerId())) {
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

        // Acting overrides any ability check the DM was waiting on for this player: drop the
        // stale pending check so its turn-hold in DmResponseConsumer is released and the new
        // action's DM response advances normally (no permanent stall on an unrolled check).
        pendingCheckRepository.deleteBySessionIdAndPlayerId(sessionId, player.getId());

        switch (session.getTurnMode()) {
            case COLLABORATIVE -> {
                // Join the debounced round; the collector persists one combined TurnEvent and
                // fires a single RoundActionEvent on flush. No per-action LLM call, no racing.
                int expected = (int) playerRepository.countBySessionIdAndRole(sessionId, PlayerRole.PLAYER);
                roundCollector.submit(sessionId, player.getId(), player.getCharacterName(),
                        action, session.getCollabWindowSeconds(), expected);
                log.info("Action collected (collaborative): session={}, player={}", sessionId, username);
            }
            case INITIATIVE -> {
                if (!player.getId().equals(session.getCurrentTurnPlayerId())) {
                    throw new NotYourTurnException("It's not your turn, " + username
                            + ". Current turn: " + session.getCurrentTurnPlayerId());
                }
                dispatchImmediateAction(sessionId, player, action);
            }
            case FREEFORM -> dispatchImmediateAction(sessionId, player, action);
        }
    }

    /** Pass for the current collaborative round (no action). No-op outside collaborative mode. */
    @Transactional
    public void passAction(UUID sessionId, String username) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        if (session.getStatus() != GameStatus.ACTIVE) {
            throw new IllegalStateException("Game is not active");
        }
        if (session.getTurnMode() != TurnMode.COLLABORATIVE) {
            throw new IllegalStateException("Pass is only available in collaborative mode");
        }
        if (combatEncounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("Combat is in progress — use combat actions");
        }
        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "Player not found: " + username + " in session " + sessionId));
        int expected = (int) playerRepository.countBySessionIdAndRole(sessionId, PlayerRole.PLAYER);
        roundCollector.pass(sessionId, player.getId(), player.getCharacterName(),
                session.getCollabWindowSeconds(), expected);
        log.info("Player passed (collaborative): session={}, player={}", sessionId, username);
    }

    /** Persist the action and fire the per-action DM pipeline (initiative + freeform modes). */
    private void dispatchImmediateAction(UUID sessionId, Player player, String action) {
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
                sessionId, player.getId(), player.getCharacterName(), action, nextTurnNumber,
                turnEvent.getId());
        eventProducer.sendPlayerAction(kafkaEvent);

        log.info("Action submitted: session={}, player={}, turn={}",
                sessionId, player.getUsername(), nextTurnNumber);
    }

    /**
     * Attach the DM narration to a specific {@link TurnEvent} loaded by id (never "find latest")
     * and emit the canonical {@link DmResponseEvent}. Loading by id keeps concurrent in-flight
     * writers (collaborative rounds, check beats, freeform streaming) from clobbering each
     * other's rows — the same discipline {@link #recordCombatNarration} uses.
     */
    @Transactional
    public void recordDmResponse(UUID turnEventId, UUID sessionId, UUID playerId,
                                 String dmResponse, int turnNumber) {
        TurnEvent turnEvent = turnEventRepository.findById(turnEventId)
                .orElseThrow(() -> new IllegalStateException("Turn event not found: " + turnEventId));

        turnEvent.setDmResponse(dmResponse);
        turnEventRepository.save(turnEvent);

        DmResponseEvent responseEvent = new DmResponseEvent(
                sessionId, playerId, turnEvent.getAction(), dmResponse, turnNumber);
        eventProducer.sendDmResponse(responseEvent);

        log.info("DM response recorded: session={}, turnEvent={}, turn={}",
                sessionId, turnEventId, turnNumber);
    }

    /**
     * Persist a combat "beat" as a {@link TurnEvent} with the mechanical summary as its
     * {@code action} and no DM response yet. Combat shares the session-wide turn-number
     * sequence so the fight stays ordered in history and is picked up by RAG. The narration
     * is filled in later by {@link #recordCombatNarration} once the LLM finishes streaming.
     *
     * @param playerId an associated player in the session (the acting player for a
     *                 player-cycle beat, or any session player for start/end beats) — the
     *                 row's {@code player_id} is non-null but combat narration is keyed by
     *                 turn number, not by this player.
     */
    @Transactional
    public TurnEvent createCombatBeat(UUID sessionId, UUID playerId, String summary) {
        int nextTurnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .map(e -> e.getTurnNumber() + 1)
                .orElse(1);

        TurnEvent beat = TurnEvent.builder()
                .sessionId(sessionId)
                .playerId(playerId)
                .action(summary)
                .turnNumber(nextTurnNumber)
                .source(com.dungeon.master.model.enums.TurnSource.COMBAT)
                .build();
        TurnEvent saved = turnEventRepository.save(beat);

        log.info("Combat beat recorded: session={}, turn={}", sessionId, nextTurnNumber);
        return saved;
    }

    /**
     * Persist a NARRATIVE beat (its mechanical summary as {@code action}, no DM response yet)
     * and return it. Used by ability-check resolution to attach the streamed DM narration via
     * {@link #recordDmResponse} once it finishes.
     */
    @Transactional
    public TurnEvent createNarrativeBeat(UUID sessionId, UUID playerId, String summary) {
        int nextTurnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .map(e -> e.getTurnNumber() + 1)
                .orElse(1);

        TurnEvent beat = TurnEvent.builder()
                .sessionId(sessionId)
                .playerId(playerId)
                .action(summary)
                .turnNumber(nextTurnNumber)
                .build();
        TurnEvent saved = turnEventRepository.save(beat);

        log.info("Narrative beat recorded: session={}, turn={}", sessionId, nextTurnNumber);
        return saved;
    }

    /**
     * Fill in the DM narration for a previously-created combat beat. Loads the exact event
     * by id (never "find latest") so concurrent in-flight beats can't collide.
     */
    @Transactional
    public void recordCombatNarration(UUID turnEventId, String narration) {
        TurnEvent beat = turnEventRepository.findById(turnEventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Combat beat not found: " + turnEventId));
        beat.setDmResponse(narration);
        turnEventRepository.save(beat);
        log.info("Combat narration recorded: turnEvent={}, turn={}",
                turnEventId, beat.getTurnNumber());
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
