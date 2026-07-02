package com.dungeon.master.service.game;

import com.dungeon.master.exception.SessionNotFoundException;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns advancing the shared in-game clock outside of travel. {@link TravelService} advances the clock
 * for journeys; resting is the other thing that consumes in-game time, so its handlers advance the clock
 * here. Advancing time also drives exhaustion: {@link PlayerStateService#accrueExhaustion} tires anyone
 * who has gone a full day without a long rest. Broadcasts the new clock (reusing the wired
 * {@code LOCATION_CHANGED} message) and any exhaustion changes so every client stays in sync.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameClockService {

    /** A short rest is at least one hour of in-game time. */
    public static final long SHORT_REST_MINUTES = 60;
    /** A long rest is eight hours of in-game time. */
    public static final long LONG_REST_MINUTES = 8 * 60;

    private final GameSessionRepository sessionRepository;
    private final PlayerStateService playerStateService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Advance the session clock by {@code minutes}, persist it, and broadcast the new time (the party's
     * location is unchanged). Returns the new total in-game minutes. Does not accrue exhaustion — call
     * {@link #accrueAndBroadcast} after applying the rest so a long rest can reset its own window first.
     */
    @Transactional
    public long advanceClock(UUID sessionId, long minutes) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
        long now = session.getInGameMinutes() + Math.max(0, minutes);
        session.setInGameMinutes(now);
        sessionRepository.save(session);
        broadcastClock(session);
        return now;
    }

    /**
     * Accrue exhaustion for the session at its current clock and broadcast every player whose state
     * changed. Safe to call after any time advance (rest here, or travel in {@link TravelService}).
     */
    @Transactional
    public void accrueAndBroadcast(UUID sessionId) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        List<PlayerRuntimeStateDto> changed =
                playerStateService.accrueExhaustion(sessionId, session.getInGameMinutes());
        for (PlayerRuntimeStateDto state : changed) {
            messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                    PlayerStateEvent.of(sessionId, state));
        }
    }

    /** Broadcast the advanced clock so every client updates its in-game clock; location is unchanged. */
    private void broadcastClock(GameSession session) {
        String region = session.getCurrentRegion() == null ? "" : session.getCurrentRegion();
        String subregion = session.getCurrentSubregion() == null ? "" : session.getCurrentSubregion();
        messagingTemplate.convertAndSend("/topic/game/" + session.getId(), (Object) Map.of(
                "type", "LOCATION_CHANGED",
                "sessionId", session.getId().toString(),
                "currentRegion", region,
                "currentSubregion", subregion,
                "fromRegion", region,
                "fromSubregion", subregion,
                "inGameMinutes", (Object) session.getInGameMinutes(),
                "pace", session.getTravelPace().name()));
    }
}
