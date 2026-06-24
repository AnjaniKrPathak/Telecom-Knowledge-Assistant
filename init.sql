-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- The document_embeddings table is created automatically by LangChain4j PgVectorEmbeddingStore.
-- This file is here to ensure the extension is available before the app starts.

-- Optional: create index after data is loaded for better performance
-- CREATE INDEX ON document_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
