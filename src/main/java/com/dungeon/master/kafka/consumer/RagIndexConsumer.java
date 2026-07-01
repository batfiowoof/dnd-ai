package com.dungeon.master.kafka.consumer;

import com.dungeon.master.config.KafkaConfig;
import com.dungeon.master.kafka.event.RagIndexEvent;
import com.dungeon.master.service.ai.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Off-thread RAG indexing. {@link com.dungeon.master.kafka.consumer.DmResponseConsumer} fires a
 * {@link RagIndexEvent} on the periodic history tick; this consumer performs the blocking embedding
 * call + pgvector write so the DM-response broadcast path never stalls on it.
 *
 * <p>Keeps the default {@code earliest} offset semantics: {@link RagService#indexSessionHistory}
 * replaces the session's prior SESSION_HISTORY snapshot, so redelivery/replay is idempotent and
 * cannot pile up duplicate rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagIndexConsumer {

    private final RagService ragService;

    @KafkaListener(topics = KafkaConfig.TOPIC_RAG_INDEX, groupId = "dnd-ai-rag-group")
    public void handleRagIndex(RagIndexEvent event) {
        log.info("Received RAG index request: session={}, turn={}",
                event.sessionId(), event.turnNumber());
        ragService.indexSessionHistory(event.sessionId());
    }
}
