-- Scope RAG lore + session history to a session so one campaign's world knowledge can't leak into
-- another's retrieval. NULL = global (e.g. the bundled SRD RULES, which stay shared across sessions).

ALTER TABLE world_documents
    ADD COLUMN session_id UUID;

CREATE INDEX idx_world_documents_session ON world_documents(session_id);
