package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.PlayerActionEvent;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlayerActionConsumer {

    private final DmAiService dmAiService;
    private final TurnService turnService;

    @KafkaListener(topics = KafkaConfig.TOPIC_PLAYER_ACTION, groupId = "dnd-ai-dm-group")
    public void handlePlayerAction(PlayerActionEvent event) {
        log.info("Received player action: session={}, player={}, turn={}",
                event.sessionId(), event.playerId(), event.turnNumber());

        try {
            String dmResponse = dmAiService.generateResponse(
                    event.sessionId(), event.playerName(), event.action());

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
