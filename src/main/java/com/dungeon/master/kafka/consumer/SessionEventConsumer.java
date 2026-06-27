package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.SessionEvent;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.repository.TurnEventRepository;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.ai.DmTags;
import com.dungeon.master.service.game.GameSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionEventConsumer {

    /** Synthetic player id used in streamed opening messages (no human acts on turn 0). */
    private static final String OPENING_PLAYER_KEY = "dm";

    private final SimpMessagingTemplate messagingTemplate;
    private final GameSessionService gameSessionService;
    private final DmAiService dmAiService;
    private final PlayerRepository playerRepository;
    private final TurnEventRepository turnEventRepository;

    @KafkaListener(topics = KafkaConfig.TOPIC_SESSION_EVENT, groupId = "dnd-ai-session-group")
    public void handleSessionEvent(SessionEvent event) {
        log.info("Session event: session={}, type={}", event.sessionId(), event.type());

        switch (event.type()) {
            case PLAYER_JOINED -> {
                var gameState = gameSessionService.getGameState(event.sessionId());
                messagingTemplate.convertAndSend(
                        "/topic/game/" + event.sessionId(),
                        (Object) Map.of("type", "PLAYER_JOINED", "gameState", gameState));
            }
            case PLAYER_LEFT -> {
                var gameState = gameSessionService.getGameState(event.sessionId());
                messagingTemplate.convertAndSend(
                        "/topic/game/" + event.sessionId(),
                        (Object) Map.of("type", "PLAYER_LEFT",
                                "playerId", event.playerId().toString(),
                                "gameState", gameState));
            }
            case GAME_STARTED -> {
                var gameState = gameSessionService.getGameState(event.sessionId());
                messagingTemplate.convertAndSend(
                        "/topic/game/" + event.sessionId(),
                        (Object) Map.of("type", "GAME_STARTED", "gameState", gameState));
                generateOpeningNarration(event.sessionId());
            }
            case GAME_ENDED -> messagingTemplate.convertAndSend(
                    "/topic/game/" + event.sessionId(),
                    (Object) Map.of("type", "GAME_ENDED",
                            "sessionId", event.sessionId().toString()));
        }
    }

    /**
     * Streams a DM opening scene when the game starts, persists it as turn 0 (attributed to the
     * AI Dungeon Master player to satisfy the NOT-NULL FK), and broadcasts the final narration.
     * Does NOT advance the turn — {@code startSession} already set the first player.
     */
    private void generateOpeningNarration(UUID sessionId) {
        String destination = "/topic/game/" + sessionId;
        try {
            GameSession session = gameSessionService.getSession(sessionId);
            String worldSetting = session.getWorldSetting();

            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_THINKING",
                    "turnNumber", "0"));

            String opening = dmAiService.generateOpening(sessionId, worldSetting,
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", "0",
                            "playerId", OPENING_PLAYER_KEY,
                            "delta", chunk)));

            // Strip any directive tags the model leaked into the opening — the opening must never
            // show a raw tag (and never starts combat: encounter tags are not parsed here).
            String cleanedOpening = DmTags.strip(opening);
            if (cleanedOpening.isBlank()) {
                cleanedOpening = "The adventure begins. The Dungeon Master sets the scene.";
            }
            final String finalOpening = cleanedOpening;

            playerRepository.findBySessionIdAndRole(sessionId, PlayerRole.DM_AI).ifPresent(dm -> {
                TurnEvent openingTurn = TurnEvent.builder()
                        .sessionId(sessionId)
                        .playerId(dm.getId())
                        .action("[The adventure begins]")
                        .dmResponse(finalOpening)
                        .turnNumber(0)
                        .build();
                turnEventRepository.save(openingTurn);
            });

            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_NARRATION",
                    "turnNumber", "0",
                    "playerId", OPENING_PLAYER_KEY,
                    "dmNarration", finalOpening));

        } catch (Exception e) {
            log.error("Failed to generate opening narration for session={}", sessionId, e);
        }
    }
}
