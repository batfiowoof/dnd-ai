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

    @Query(value = """
            SELECT id FROM world_documents
            ORDER BY embedding <=> cast(:queryVector as vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findSimilarDocumentIds(@Param("queryVector") String queryVector,
                                      @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO world_documents (id, title, content, category, embedding)
            VALUES (:id, :title, :content, :category, cast(:embedding as vector))
            """, nativeQuery = true)
    void insertWithEmbedding(@Param("id") UUID id,
                              @Param("title") String title,
                              @Param("content") String content,
                              @Param("category") String category,
                              @Param("embedding") String embedding);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE world_documents SET embedding = cast(:embedding as vector)
            WHERE id = :id
            """, nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);
}
