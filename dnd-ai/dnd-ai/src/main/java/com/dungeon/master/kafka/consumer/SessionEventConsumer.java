package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.SessionEvent;
import com.dungeon.master.service.game.GameSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final GameSessionService gameSessionService;

    @KafkaListener(topics = KafkaConfig.TOPIC_SESSION_EVENT, groupId = "dnd-ai-session-group")
    public void handleSessionEvent(SessionEvent event) {
        log.info("Session event: session={}, type={}", event.sessionId(), event.type());

        switch (event.type()) {
            case PLAYER_JOINED -> {
                var gameState = gameSessionService.getGameState(event.sessionId());
                messagingTemplate.convertAndSend(
                        "/topic/game/" + event.sessionId(),
                        Map.of("type", "PLAYER_JOINED", "gameState", gameState));
            }
            case PLAYER_LEFT -> {
                var gameState = gameSessionService.getGameState(event.sessionId());
                messagingTemplate.convertAndSend(
                        "/topic/game/" + event.sessionId(),
                        Map.of("type", "PLAYER_LEFT",
                                "playerId", event.playerId().toString(),
                                "gameState", gameState));
            }
            case GAME_STARTED -> {
                var gameState = gameSessionService.getGameState(event.sessionId());
                messagingTemplate.convertAndSend(
                        "/topic/game/" + event.sessionId(),
                        Map.of("type", "GAME_STARTED", "gameState", gameState));
            }
            case GAME_ENDED -> messagingTemplate.convertAndSend(
                    "/topic/game/" + event.sessionId(),
                    Map.of("type", "GAME_ENDED",
                            "sessionId", event.sessionId().toString()));
        }
    }
}
