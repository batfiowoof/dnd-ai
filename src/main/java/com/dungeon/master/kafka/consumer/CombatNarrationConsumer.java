package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.CombatNarrationEvent;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.game.TurnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Streams the DM's narration of a resolved combat beat off-thread, mirroring
 * {@link PlayerActionConsumer}. The combat engine has already broadcast the mechanical
 * results (dice/HP modals) instantly; this lands the flavor text a beat later without
 * holding the combat transaction open for the multi-second LLM call.
 *
 * <p>Reuses the existing DM streaming event shape ({@code DM_THINKING}/{@code DM_CHUNK}/
 * {@code DM_NARRATION}) with a stable {@code "combat"} sentinel playerId, so the frontend
 * renders it as a normal streamed DM chat entry keyed by the beat's (unique) turn number.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CombatNarrationConsumer {

    private static final String COMBAT_PLAYER_KEY = "combat";

    private final DmAiService dmAiService;
    private final TurnService turnService;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = KafkaConfig.TOPIC_COMBAT_NARRATION, groupId = "dnd-ai-dm-group")
    public void handleCombatNarration(CombatNarrationEvent event) {
        log.info("Received combat narration beat: session={}, turnEvent={}, turn={}",
                event.sessionId(), event.turnEventId(), event.turnNumber());

        String destination = "/topic/game/" + event.sessionId();
        String turnNumber = String.valueOf(event.turnNumber());

        try {
            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_THINKING",
                    "turnNumber", turnNumber,
                    "playerId", COMBAT_PLAYER_KEY));

            String narration = dmAiService.generateCombatNarration(
                    event.sessionId(), event.beatSummary(),
                    chunk -> messagingTemplate.convertAndSend(destination, (Object) Map.of(
                            "type", "DM_CHUNK",
                            "turnNumber", turnNumber,
                            "playerId", COMBAT_PLAYER_KEY,
                            "delta", chunk)));

            // Finalize the streamed entry with the authoritative text (turn does not advance —
            // combat owns its own initiative pointer).
            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_NARRATION",
                    "turnNumber", turnNumber,
                    "playerId", COMBAT_PLAYER_KEY,
                    "dmNarration", narration));

            turnService.recordCombatNarration(event.turnEventId(), narration);

        } catch (Exception e) {
            log.error("Failed to narrate combat beat: session={}, turnEvent={}",
                    event.sessionId(), event.turnEventId(), e);
            // The mechanical summary is already the source of truth; persist it so history
            // and RAG still see the beat even if narration failed, and close out the live
            // entry so the "DM is thinking" indicator doesn't stick.
            messagingTemplate.convertAndSend(destination, (Object) Map.of(
                    "type", "DM_NARRATION",
                    "turnNumber", turnNumber,
                    "playerId", COMBAT_PLAYER_KEY,
                    "dmNarration", event.beatSummary()));
            turnService.recordCombatNarration(event.turnEventId(), event.beatSummary());
        }
    }
}
