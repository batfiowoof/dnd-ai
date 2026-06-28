package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.DmResponseEvent;
import com.dungeon.master.model.dto.DmResponseDto;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.enums.TurnMode;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.service.ai.RagService;
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
    private final GameSessionRepository sessionRepository;
    private final RagService ragService;

    @KafkaListener(topics = KafkaConfig.TOPIC_DM_RESPONSE, groupId = "dnd-ai-ws-group")
    public void handleDmResponse(DmResponseEvent event) {
        log.info("Broadcasting DM response: session={}, turn={}",
                event.sessionId(), event.turnNumber());

        GameSession session = sessionRepository.findById(event.sessionId()).orElse(null);
        TurnMode mode = session == null ? TurnMode.COLLABORATIVE : session.getTurnMode();

        // Only initiative mode rotates a turn pointer. Collaborative/freeform leave it null.
        // Rolls now resolve inline within the DM turn (engine tools), so there is no pending check to
        // wait on — every initiative turn advances once its DM response is recorded.
        UUID nextPlayerId = null;
        if (mode == TurnMode.INITIATIVE) {
            nextPlayerId = turnService.advanceTurn(event.sessionId());
        }

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

        // Periodically fold recent history into the RAG index. Relocated here from
        // TurnNextConsumer so it runs in every mode (collaborative/freeform emit no TURN_CHANGE).
        if (event.turnNumber() > 0 && event.turnNumber() % 10 == 0) {
            ragService.indexSessionHistory(event.sessionId());
        }

        log.info("DM response broadcast complete: session={}", event.sessionId());
    }
}
