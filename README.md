# RAG Project — LangChain4j + Ollama + PostgreSQL/pgvector

A production-ready **Retrieval-Augmented Generation (RAG)** backend built with:

| Layer | Technology |
|---|---|
| Language / Framework | Java 21 + Spring Boot 3.3 |
| LLM | Ollama → **llama3** |
| Embeddings | Ollama → **nomic-embed-text** |
| Vector Store | **PostgreSQL 16 + pgvector** |
| RAG Orchestration | **LangChain4j 0.35** |
| Supported Docs | PDF, DOCX, Excel (XLSX/XLS), TXT, Web URLs |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         REST API (Spring Boot)                   │
│  POST /api/documents/upload   POST /api/documents/url           │
│  POST /api/query                                                  │
└────────────────────┬──────────────────────┬─────────────────────┘
                     │                      │
          ┌──────────▼──────────┐  ┌────────▼──────────┐
          │  IngestionService   │  │  RagQueryService   │
          │                     │  │                    │
          │ 1. Load document    │  │ 1. Embed question  │
          │ 2. Split to chunks  │  │ 2. Search pgvector │
          │ 3. Embed chunks     │  │ 3. Build prompt    │
          │ 4. Store pgvector   │  │ 4. Call llama3     │
          └──────────┬──────────┘  └────────┬───────────┘
                     │                      │
          ┌──────────▼──────────────────────▼───────────┐
          │            Ollama  (localhost:11434)          │
          │   • nomic-embed-text  (embeddings)            │
          │   • llama3            (generation)            │
          └──────────────────────────────────────────────┘
                     │
          ┌──────────▼──────────────────────────────────┐
          │         PostgreSQL 16 + pgvector              │
          │   document_embeddings  (vectors + text)       │
          │   document_records     (metadata)             │
          └─────────────────────────────────────────────-┘
```

---

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **Docker & Docker Compose**
- **Ollama** installed — [https://ollama.com](https://ollama.com)

---

## Quick Start

### 1. Start PostgreSQL + pgvector

```bash
cd docker
docker compose up -d
```

Verify:
```bash
docker ps          # rag-postgres and rag-pgadmin should be running
```

pgAdmin UI → [http://localhost:5050](http://localhost:5050)
- Email: `admin@rag.local`  |  Password: `admin`

### 2. Pull Ollama Models

```bash
# LLM for generation
ollama pull llama3

# Embedding model
ollama pull nomic-embed-text

# Verify both are available
ollama list
```

### 3. Start Ollama

```bash
ollama serve
# Runs on http://localhost:11434
```

### 4. Run the Spring Boot App

```bash
./mvnw spring-boot:run
```

App starts on [http://localhost:8080](http://localhost:8080)  
Swagger UI → [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

---

## API Reference

### Ingest a PDF / DOCX / Excel / TXT file

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/document.pdf"
```

### Ingest a Web URL

```bash
curl -X POST http://localhost:8080/api/documents/url \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/article"}'
```

### Ask a Question

```bash
curl -X POST http://localhost:8080/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is the refund policy?"}'
```

**Response:**
```json
{
  "question": "What is the refund policy?",
  "answer": "Based on the documents, the refund policy states...",
  "sources": [
    {
      "source": "policy.pdf",
      "type": "PDF",
      "score": 0.91,
      "excerpt": "Refunds are processed within 5-7 business days..."
    }
  ]
}
```

### List all ingested documents

```bash
curl http://localhost:8080/api/documents
```

### Delete a document record

```bash
curl -X DELETE http://localhost:8080/api/documents/{id}
```

---

## Configuration

All settings are in `src/main/resources/application.yml`:

| Property | Default | Description |
|---|---|---|
| `ollama.chat-model` | `llama3` | LLM for generation |
| `ollama.embedding-model` | `nomic-embed-text` | Embedding model |
| `rag.chunk-size` | `500` | Tokens per chunk |
| `rag.chunk-overlap` | `50` | Overlap between chunks |
| `rag.max-results` | `5` | Top-k chunks retrieved |
| `pgvector.dimension` | `768` | Embedding dimension |

---

## Supported Document Types

| Type | Extension | Parser |
|---|---|---|
| PDF | `.pdf` | Apache PDFBox |
| Word | `.docx`, `.doc` | Apache POI |
| Excel | `.xlsx`, `.xls` | Apache POI |
| Plain text | `.txt`, `.md` | Built-in |
| Web page | `http://`, `https://` | Jsoup |

---

## Project Structure

```
rag-project/
├── docker/
│   ├── docker-compose.yml   # Postgres + pgvector + pgAdmin
│   └── init.sql             # Enables pgvector extension
├── src/main/java/com/rag/
│   ├── RagApplication.java
│   ├── config/
│   │   └── LangChainConfig.java   # Ollama + PgVector beans
│   ├── controller/
│   │   ├── IngestionController.java
│   │   └── QueryController.java
│   ├── document/
│   │   ├── DocumentType.java
│   │   └── DocumentLoaderService.java   # Multi-format loader
│   ├── model/
│   │   └── DocumentRecord.java
│   ├── repository/
│   │   └── DocumentRepository.java
│   └── service/
│       ├── IngestionService.java   # Chunk → embed → store
│       └── RagQueryService.java   # Retrieve → generate
├── src/main/resources/
│   └── application.yml
└── pom.xml
```

---

## Tips

- **Swap the LLM**: Change `ollama.chat-model` to `mistral`, `gemma2`, or any model you've pulled.
- **Tune retrieval**: Increase `rag.max-results` or lower `minScore` in `RagQueryService` for broader results.
- **Production index**: After loading data, add an IVFFlat index for faster similarity search:
  ```sql
  CREATE INDEX ON document_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
  ```
- **Embedding dimension**: If you swap embedding models, update `pgvector.dimension` to match (e.g., `4096` for `llama3` embeddings).
