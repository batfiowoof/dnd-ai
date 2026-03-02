-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- Game Sessions
-- ============================================
CREATE TABLE game_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(6) NOT NULL UNIQUE,
    status          VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    max_players     INT NOT NULL DEFAULT 4,
    current_turn_player_id UUID,
    turn_order      JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ============================================
-- Players
-- ============================================
CREATE TABLE players (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(255) NOT NULL,
    character_name  VARCHAR(255),
    character_sheet JSONB,
    role            VARCHAR(20) NOT NULL DEFAULT 'PLAYER',
    session_id      UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    turn_index      INT NOT NULL DEFAULT 0
);

-- ============================================
-- Turn Events
-- ============================================
CREATE TABLE turn_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    player_id       UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    action          TEXT NOT NULL,
    dm_response     TEXT,
    timestamp       TIMESTAMP NOT NULL DEFAULT NOW(),
    turn_number     INT NOT NULL
);

-- ============================================
-- World Documents (RAG vector store)
-- ============================================
CREATE TABLE world_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(255) NOT NULL,
    content         TEXT NOT NULL,
    category        VARCHAR(50) NOT NULL,
    embedding       vector(1536)
);

-- ============================================
-- Indexes
-- ============================================
CREATE INDEX idx_game_sessions_code ON game_sessions(code);
CREATE INDEX idx_players_session_id ON players(session_id);
CREATE INDEX idx_players_session_username ON players(session_id, username);
CREATE INDEX idx_turn_events_session_id ON turn_events(session_id);
CREATE INDEX idx_turn_events_session_turn ON turn_events(session_id, turn_number DESC);

-- IVFFlat index for vector similarity search (cosine distance)
CREATE INDEX idx_world_documents_embedding ON world_documents
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
