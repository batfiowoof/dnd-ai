package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.TurnNextEvent;
import com.dungeon.master.service.ai.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TurnNextConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final RagService ragService;

    @KafkaListener(topics = KafkaConfig.TOPIC_TURN_NEXT, groupId = "dnd-ai-turn-group")
    public void handleTurnNext(TurnNextEvent event) {
        log.info("Turn advanced: session={}, nextPlayer={}, turn={}",
                event.sessionId(), event.nextPlayerId(), event.turnNumber());

        messagingTemplate.convertAndSend(
                "/topic/game/" + event.sessionId(),
                Map.of(
                        "type", "TURN_CHANGE",
                        "nextPlayerId", event.nextPlayerId().toString(),
                        "turnNumber", event.turnNumber()));

        if (event.turnNumber() > 0 && event.turnNumber() % 10 == 0) {
            ragService.indexSessionHistory(event.sessionId());
        }
    }
}
