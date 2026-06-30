package com.dungeon.master.repository;

import com.dungeon.master.model.entity.WorldDocument;
import com.dungeon.master.model.enums.DocumentCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorldDocumentRepository extends JpaRepository<WorldDocument, UUID> {

    List<WorldDocument> findByCategory(DocumentCategory category);

    /**
     * Nearest world-knowledge documents — RULES are excluded so the bundled SRD reference can't
     * crowd out lore / session-history retrieval (rules are surfaced separately via
     * {@link #findSimilarRuleIds}).
     */
    @Query(value = """
            SELECT id FROM world_documents
            WHERE category <> 'RULES'
            ORDER BY embedding <=> cast(:queryVector as vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findSimilarDocumentIds(@Param("queryVector") String queryVector,
                                      @Param("limit") int limit);

    /**
     * Nearest world-knowledge documents scoped to ONE session (its authored LORE plus its own
     * SESSION_HISTORY), so a campaign never retrieves another world's lore. RULES stay global and are
     * fetched separately via {@link #findSimilarRuleIds}.
     */
    @Query(value = """
            SELECT id FROM world_documents
            WHERE category <> 'RULES' AND session_id = :sessionId
            ORDER BY embedding <=> cast(:queryVector as vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findSimilarSessionDocIds(@Param("queryVector") String queryVector,
                                        @Param("sessionId") UUID sessionId,
                                        @Param("limit") int limit);

    /** Nearest RULES documents only — the SRD reference block injected into the DM prompt. */
    @Query(value = """
            SELECT id FROM world_documents
            WHERE category = 'RULES'
            ORDER BY embedding <=> cast(:queryVector as vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findSimilarRuleIds(@Param("queryVector") String queryVector,
                                  @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO world_documents (id, title, content, category, embedding)
            VALUES (:id, :title, :content, :category, cast(:embedding as vector))
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    void insertWithEmbedding(@Param("id") UUID id,
                              @Param("title") String title,
                              @Param("content") String content,
                              @Param("category") String category,
                              @Param("embedding") String embedding);

    /** Insert a session-scoped document (LORE or SESSION_HISTORY) with its embedding. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO world_documents (id, title, content, category, session_id, embedding)
            VALUES (:id, :title, :content, :category, :sessionId, cast(:embedding as vector))
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    void insertWithSession(@Param("id") UUID id,
                           @Param("title") String title,
                           @Param("content") String content,
                           @Param("category") String category,
                           @Param("sessionId") UUID sessionId,
                           @Param("embedding") String embedding);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE world_documents SET embedding = cast(:embedding as vector)
            WHERE id = :id
            """, nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);
}
