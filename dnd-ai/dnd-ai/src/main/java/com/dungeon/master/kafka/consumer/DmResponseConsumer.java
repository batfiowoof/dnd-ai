package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.DmResponseEvent;
import com.dungeon.master.model.dto.DmResponseDto;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DmResponseConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final TurnService turnService;

    @KafkaListener(topics = KafkaConfig.TOPIC_DM_RESPONSE, groupId = "dnd-ai-ws-group")
    public void handleDmResponse(DmResponseEvent event) {
        log.info("Broadcasting DM response: session={}, turn={}",
                event.sessionId(), event.turnNumber());

        UUID nextPlayerId = turnService.advanceTurn(event.sessionId());

        DmResponseDto responseDto = new DmResponseDto(
                event.sessionId(),
                event.playerId(),
                event.playerAction(),
                event.dmNarration(),
                nextPlayerId,
                event.turnNumber());

        messagingTemplate.convertAndSend(
                "/topic/game/" + event.sessionId(), (Object) responseDto);

        messagingTemplate.convertAndSend(
                "/topic/game/" + event.sessionId() + "/dm", (Object) responseDto);

        log.info("DM response broadcast complete: session={}", event.sessionId());
    }
}
