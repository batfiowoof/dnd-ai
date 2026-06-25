package com.dungeon.master.kafka.producer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.CombatNarrationEvent;
import com.dungeon.master.kafka.event.DmResponseEvent;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.kafka.event.SessionEvent;
import com.dungeon.master.kafka.event.TurnNextEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GameEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendPlayerAction(PlayerActionEvent event) {
        log.info("Publishing player action: session={}, player={}, turn={}",
                event.sessionId(), event.playerId(), event.turnNumber());
        kafkaTemplate.send(KafkaConfig.TOPIC_PLAYER_ACTION,
                event.sessionId().toString(), event);
    }

    public void sendDmResponse(DmResponseEvent event) {
        log.info("Publishing DM response: session={}, turn={}",
                event.sessionId(), event.turnNumber());
        kafkaTemplate.send(KafkaConfig.TOPIC_DM_RESPONSE,
                event.sessionId().toString(), event);
    }

    public void sendTurnNext(TurnNextEvent event) {
        log.info("Publishing turn advance: session={}, nextPlayer={}, turn={}",
                event.sessionId(), event.nextPlayerId(), event.turnNumber());
        kafkaTemplate.send(KafkaConfig.TOPIC_TURN_NEXT,
                event.sessionId().toString(), event);
    }

    public void sendSessionEvent(SessionEvent event) {
        log.info("Publishing session event: session={}, type={}",
                event.sessionId(), event.type());
        kafkaTemplate.send(KafkaConfig.TOPIC_SESSION_EVENT,
                event.sessionId().toString(), event);
    }

    public void sendCombatNarration(CombatNarrationEvent event) {
        log.info("Publishing combat narration beat: session={}, turnEvent={}, turn={}",
                event.sessionId(), event.turnEventId(), event.turnNumber());
        kafkaTemplate.send(KafkaConfig.TOPIC_COMBAT_NARRATION,
                event.sessionId().toString(), event);
    }
}
