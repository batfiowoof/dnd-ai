package com.dungeon.master.service.ai;

import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.entity.WorldDocument;
import com.dungeon.master.repository.TurnEventRepository;
import com.dungeon.master.repository.WorldDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final int TOP_K_DOCUMENTS = 5;
    private static final int RECENT_TURNS_COUNT = 5;

    private final WorldDocumentRepository worldDocumentRepository;
    private final TurnEventRepository turnEventRepository;
    private final EmbeddingService embeddingService;

    public String buildContext(UUID sessionId, String playerAction) {
        StringBuilder context = new StringBuilder();

        List<WorldDocument> relevantDocs = retrieveRelevantDocuments(playerAction);
        if (!relevantDocs.isEmpty()) {
            context.append("=== World Knowledge ===\n");
            for (WorldDocument doc : relevantDocs) {
                context.append("[").append(doc.getCategory()).append("] ")
                        .append(doc.getTitle()).append(": ")
                        .append(doc.getContent()).append("\n\n");
            }
        }

        List<TurnEvent> recentTurns = getRecentTurns(sessionId);
        if (!recentTurns.isEmpty()) {
            context.append("=== Recent Session History ===\n");
            Collections.reverse(recentTurns);
            for (TurnEvent turn : recentTurns) {
                context.append("Turn ").append(turn.getTurnNumber()).append(" - Player Action: ")
                        .append(turn.getAction());
                if (turn.getDmResponse() != null) {
                    context.append("\nDM Response: ").append(turn.getDmResponse());
                }
                context.append("\n\n");
            }
        }

        return context.toString();
    }

    public List<WorldDocument> retrieveRelevantDocuments(String query) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String vectorString = embeddingService.embeddingToString(queryEmbedding);

            List<UUID> similarIds = worldDocumentRepository.findSimilarDocumentIds(
                    vectorString, TOP_K_DOCUMENTS);

            if (similarIds.isEmpty()) {
                log.debug("No similar documents found for query");
                return List.of();
            }

            return worldDocumentRepository.findAllById(similarIds);
        } catch (Exception e) {
            log.error("Failed to retrieve relevant documents", e);
            return List.of();
        }
    }

    public List<TurnEvent> getRecentTurns(UUID sessionId) {
        return turnEventRepository.findTop5BySessionIdOrderByTurnNumberDesc(sessionId);
    }

    public void indexSessionHistory(UUID sessionId) {
        List<TurnEvent> recentTurns = turnEventRepository
                .findTop20BySessionIdOrderByTurnNumberDesc(sessionId);

        if (recentTurns.isEmpty()) {
            return;
        }

        String historyText = recentTurns.stream()
                .map(turn -> "Turn " + turn.getTurnNumber() + ": " + turn.getAction()
                        + (turn.getDmResponse() != null ? " -> " + turn.getDmResponse() : ""))
                .collect(Collectors.joining("\n"));

        try {
            float[] embedding = embeddingService.generateEmbedding(historyText);
            String vectorString = embeddingService.embeddingToString(embedding);
            UUID docId = UUID.randomUUID();

            worldDocumentRepository.insertWithEmbedding(
                    docId,
                    "Session History - " + sessionId,
                    historyText,
                    "SESSION_HISTORY",
                    vectorString);

            log.info("Indexed session history for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to index session history for session: {}", sessionId, e);
        }
    }
}
