package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.TurnNextEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Broadcasts initiative-mode turn rotation as a {@code TURN_CHANGE} event. Only initiative
 * mode emits {@link TurnNextEvent}; collaborative/freeform never rotate a pointer. The
 * "index history every 10 turns" RAG trigger now lives in {@code DmResponseConsumer} so it
 * runs in every mode.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TurnNextConsumer {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = KafkaConfig.TOPIC_TURN_NEXT, groupId = "dnd-ai-turn-group")
    public void handleTurnNext(TurnNextEvent event) {
        log.info("Turn advanced: session={}, nextPlayer={}, turn={}",
                event.sessionId(), event.nextPlayerId(), event.turnNumber());

        messagingTemplate.convertAndSend(
                "/topic/game/" + event.sessionId(),
                (Object) Map.of(
                        "type", "TURN_CHANGE",
                        "nextPlayerId", event.nextPlayerId().toString(),
                        "turnNumber", String.valueOf(event.turnNumber())));
    }
}
