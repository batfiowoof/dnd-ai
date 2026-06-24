package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlayerActionConsumer {

    private final DmAiService dmAiService;
    private final TurnService turnService;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = KafkaConfig.TOPIC_PLAYER_ACTION, groupId = "dnd-ai-dm-group")
    public void handlePlayerAction(PlayerActionEvent event) {
        log.info("Received player action: session={}, player={}, turn={}",
                event.sessionId(), event.playerId(), event.turnNumber());

        String destination = "/topic/game/" + event.sessionId();
        String turnNumber = String.valueOf(event.turnNumber());

        try {
            // Echo the action + show a "DM is thinking" indicator while the model warms up.
            // Carrying the action here lets every client render the action line consistently
            // (and in order, before the streamed narration) without optimistic duplicates.
            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_THINKING",
                    "turnNumber", turnNumber,
                    "playerId", event.playerId().toString(),
                    "playerName", event.playerName(),
                    "action", event.action()));

            // Stream the narration token-by-token; each chunk is broadcast to the room.
            String dmResponse = dmAiService.generateResponseStreaming(
                    event.sessionId(), event.playerId(), event.playerName(), event.action(),
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", turnNumber,
                            "playerId", event.playerId().toString(),
                            "delta", chunk)));

            // Persist the full response and emit the canonical DM event (advances the turn).
            turnService.recordDmResponse(
                    event.sessionId(), event.playerId(), dmResponse, event.turnNumber());

        } catch (Exception e) {
            log.error("Failed to process player action: session={}, player={}",
                    event.sessionId(), event.playerId(), e);

            String fallbackResponse = "The Dungeon Master is momentarily distracted... " +
                    "[An error occurred processing your action. Please try again.]";
            turnService.recordDmResponse(
                    event.sessionId(), event.playerId(), fallbackResponse, event.turnNumber());
        }
    }
}
