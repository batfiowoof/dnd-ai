-- ============================================
-- Resize world_documents embeddings 1536 -> 1024
-- ============================================
-- The embedding model moved from qwen2.5:7b (a chat model, wrong for embeddings
-- and not 1536-dim) to bge-m3, which produces 1024-dim dense vectors. Any vectors
-- stored under the old config are invalid, so we drop the index, clear the table,
-- resize the column, and rebuild the IVFFlat cosine index at the new dimension.

DROP INDEX IF EXISTS idx_world_documents_embedding;

TRUNCATE world_documents;

ALTER TABLE world_documents
    ALTER COLUMN embedding TYPE vector(1024);

CREATE INDEX idx_world_documents_embedding ON world_documents
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
