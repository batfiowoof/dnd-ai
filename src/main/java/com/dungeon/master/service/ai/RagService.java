package com.dungeon.master.service.ai;

import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.entity.WorldDocument;
import com.dungeon.master.repository.GameSessionRepository;
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
    private static final int TOP_K_RULES = 3;
    private static final int RECENT_TURNS_COUNT = 5;
    private static final String SESSION_HISTORY_CATEGORY = "SESSION_HISTORY";
    private static final String CAMPAIGN_RECAP_CATEGORY = "CAMPAIGN_RECAP";

    private final WorldDocumentRepository worldDocumentRepository;
    private final TurnEventRepository turnEventRepository;
    private final GameSessionRepository gameSessionRepository;
    private final EmbeddingService embeddingService;

    public String buildContext(UUID sessionId, String playerAction) {
        StringBuilder context = new StringBuilder();

        // Include session's world setting if present
        gameSessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.getWorldSetting() != null && !session.getWorldSetting().isBlank()) {
                context.append("=== World Setting ===\n");
                context.append(session.getWorldSetting());
                context.append("\n\n");
            }
        });

        // Embed the action ONCE and reuse the vector for both the world-knowledge and the
        // rules retrieval, rather than embedding the same text twice per turn.
        String queryVector = embedQuery(playerAction);

        List<WorldDocument> relevantDocs = queryVector == null ? List.of()
                : fetchByIds(worldDocumentRepository.findSimilarSessionDocIds(
                        queryVector, sessionId, TOP_K_DOCUMENTS));
        if (!relevantDocs.isEmpty()) {
            context.append("=== World Knowledge ===\n");
            for (WorldDocument doc : relevantDocs) {
                context.append("[").append(doc.getCategory()).append("] ")
                        .append(doc.getTitle()).append(": ")
                        .append(doc.getContent()).append("\n\n");
            }
        }

        List<WorldDocument> ruleDocs = queryVector == null ? List.of()
                : fetchByIds(worldDocumentRepository.findSimilarRuleIds(queryVector, TOP_K_RULES));
        if (!ruleDocs.isEmpty()) {
            context.append("=== D&D 5e Rules (reference for flavour — the engine still owns all math) ===\n");
            for (WorldDocument doc : ruleDocs) {
                context.append(doc.getTitle()).append(": ").append(doc.getContent()).append("\n\n");
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
        String queryVector = embedQuery(query);
        if (queryVector == null) {
            return List.of();
        }
        return fetchByIds(worldDocumentRepository.findSimilarDocumentIds(queryVector, TOP_K_DOCUMENTS));
    }

    /** Embed a query string to a pgvector literal, or null when it's blank or embedding fails. */
    private String embedQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            return embeddingService.embeddingToString(embeddingService.generateEmbedding(query));
        } catch (Exception e) {
            log.error("Failed to embed retrieval query", e);
            return null;
        }
    }

    private List<WorldDocument> fetchByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return worldDocumentRepository.findAllById(ids);
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

            // Replace the session's prior history snapshot rather than appending a new row each tick,
            // so re-indexing (including Kafka redelivery) stays idempotent: one SESSION_HISTORY doc
            // per session, always the latest window.
            worldDocumentRepository.deleteBySessionIdAndCategory(sessionId, SESSION_HISTORY_CATEGORY);
            worldDocumentRepository.insertWithSession(
                    UUID.randomUUID(),
                    "Session History - " + sessionId,
                    historyText,
                    SESSION_HISTORY_CATEGORY,
                    sessionId,
                    vectorString);

            log.info("Indexed session history for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to index session history for session: {}", sessionId, e);
        }
    }

    /**
     * Seed a prior session's recap into THIS (continuing) session's retrievable lore, so the DM can
     * recall the campaign so far via RAG throughout — not only in the opening. Idempotent per session
     * (replaces any existing recap doc). Best-effort — a failure never blocks the opening.
     */
    public void indexPriorRecap(UUID sessionId, String recapText) {
        if (recapText == null || recapText.isBlank()) {
            return;
        }
        try {
            float[] embedding = embeddingService.generateEmbedding(recapText);
            String vectorString = embeddingService.embeddingToString(embedding);

            worldDocumentRepository.deleteBySessionIdAndCategory(sessionId, CAMPAIGN_RECAP_CATEGORY);
            worldDocumentRepository.insertWithSession(
                    UUID.randomUUID(),
                    "Previously, on this campaign",
                    recapText,
                    CAMPAIGN_RECAP_CATEGORY,
                    sessionId,
                    vectorString);

            log.info("Seeded prior-campaign recap for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to seed prior recap for session: {}", sessionId, e);
        }
    }
}
