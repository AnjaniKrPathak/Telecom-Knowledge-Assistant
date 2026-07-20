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

## Hybrid Search

Retrieval combines two complementary signals instead of relying on vector similarity alone:

- **Vector leg** — the existing pgvector cosine-similarity search (semantic/paraphrase matches).
- **Keyword leg** — PostgreSQL full-text search (`tsvector` + GIN index) over the same chunks,
  which is what actually finds exact terms embeddings tend to under-weight: ticket numbers,
  error codes, product/model names, long numeric identifiers, acronyms.

**How the two legs combine depends on which entry point you use:**

- **`HybridSearchService.search(...)`** (standalone, not currently exposed via a controller) —
  runs both legs and merges them with **Reciprocal Rank Fusion (RRF)**, which combines ranked
  lists by rank position rather than raw score, so no manual tuning of relative weights is
  needed even though cosine similarity and BM25 live on different scales.
- **`RagQueryService`** (the live `/api/query` path) — runs its normal vector search (scoped to
  the detected intent's category, with fallback), then calls
  `HybridSearchService.bm25CandidateMatches(...)` to pull in any BM25-only hits the vector leg's
  candidate pool missed entirely, de-duplicated by chunk id. This matters most for questions
  whose relevance hinges on one exact literal token — e.g. "find Price Key ID
  9174084950013085926" — where a data row's cosine similarity to the question can rank below
  unrelated column-definition text, but BM25's exact-term match surfaces it reliably. The
  reranker (see below) then judges the combined pool the same way regardless of which leg found
  a given chunk — this is a recall safety net, not a second ranking system competing with the
  reranker's judgment.

On startup, a schema migration (`FullTextSearchSchemaInitializer`) adds a generated
`text_search_vector` column and GIN index to the existing `document_embeddings` table — no
manual SQL required, and no changes to ingestion. If that migration can't run for any reason,
both entry points transparently fall back to vector-only retrieval and log a warning instead of
failing queries.

Tunable via `rag.hybrid.*` in `application.yml` (see the Configuration table below) —
`rag.hybrid.enabled=false` disables the keyword leg entirely, for both entry points.

---

## Knowledge Graph (Neo4j)

On top of vector + hybrid retrieval, ingested chunks are also mined for **entities** (Offerings,
Flat Offerings, Change Requests, Bundles, Tariffs, Discounts, Rules, Relations, Price Keys, ...)
and the **relationships** between them, written into a Neo4j graph. This adds graph-native
retrieval on top of text search: dependency chains, root-cause leads, and change-impact analysis
that a similarity search over chunk text alone can't answer, because the relationship may live in
a completely different chunk than the one that mentions the entity you asked about.

- **Entity extraction** — every catalog field on an Excel-derived chunk (offeringName,
  flatOfferingId, changeRequestId, ruleId/ruleName, discountId, bundleId, tariffName, relationId,
  priceKey, plus any configured/auto-detected `*Id`/`*Name`/`*Key`/`*Code` column) becomes a graph
  node, keyed so the same identifier referenced from different sheets/documents merges onto one
  node. A conservative regex fallback also recognizes common identifier mentions (`CR-1029`,
  `Rule 71325001`, ...) directly in narrative DOCX/PDF text.
- **Relationship extraction** — entities that co-occur in the same chunk/row are linked; if the
  chunk's text contains a keyword like "depends on", "triggers", "replaces", or "overrides", the
  link is typed and directed accordingly instead of a generic co-occurrence edge.
- **Graph search** — free-text lookup of entities, and neighbor expansion to whatever's connected
  to a given entity.
- **Dependency analysis** — what an entity depends on, and what depends on it, transitively.
- **Root-cause analysis** — given a "symptom" entity, ranks upstream candidates most likely to be
  the root cause.
- **Impact analysis** — given a changed entity, finds everything downstream that could be
  affected, grouped by hop distance and source document.
- **Path finding** — shortest path (and all shortest paths) between any two entities, regardless
  of relationship type/direction.

All of the above are heuristics inferred from co-occurrence and keyword matching, not verified
domain logic — treat root-cause/impact/path results as leads to confirm, not ground truth.

### Start Neo4j

Neo4j is included in the project's `docker-compose.yml`:

```bash
docker compose up -d neo4j
```

Neo4j Browser → [http://localhost:7474](http://localhost:7474) (`neo4j` / `ragpassword`, matching
`neo4j.*` in `application.yml`).

### Populate the graph

New ingestions (`POST /api/documents/upload`, `/url`, folder/batch ingestion) populate the graph
automatically when `rag.graph.auto-ingest=true` (default). To (re)build the graph from everything
already sitting in `document_embeddings` — e.g. documents ingested before this feature existed —
run a one-off backfill (safe to re-run any time; every write is a MERGE-based upsert):

```bash
curl -X POST http://localhost:8080/api/graph/backfill
```

### Query the graph

```bash
# Find an entity
curl "http://localhost:8080/api/graph/search?q=71325001"

# What does Rule:71325001 depend on / what depends on it
curl "http://localhost:8080/api/graph/entities/Rule:71325001/dependencies?depth=3"
curl "http://localhost:8080/api/graph/entities/Rule:71325001/dependents?depth=3"

# Candidate root causes upstream of a misbehaving rule
curl "http://localhost:8080/api/graph/entities/Rule:71325001/root-cause?depth=4"

# Blast radius if Offering:12345 changes
curl "http://localhost:8080/api/graph/entities/Offering:12345/impact?depth=4"

# Shortest connection between two entities, whatever it is
curl "http://localhost:8080/api/graph/path?from=Rule:71325001&to=Offering:12345"
```

Entity ids follow `Type:normalizedValue` (e.g. `Rule:71325001`) — `GET /api/graph/search` or
`GET /api/graph/entity-id?type=Rule&value=71325001` resolve a human-typed value to the exact id.

Optionally, set `rag.graph.query-augmentation-enabled=true` to have `RagQueryService` add a short
"related knowledge graph context" block (nearby dependencies/impact of entities recognized in the
retrieved chunks) to the prompt before every answer — off by default so its effect on answer
quality can be evaluated deliberately.

---

## Webex Chatbot

The bot answers questions asked in a Webex space via `/api/webex/webhook`, and keeps the space
engaged instead of going quiet while it works:

1. It immediately posts **"🤔 Thinking about your question..."**
2. It edits that same message in place as `RagQueryService` moves through its stages —
   **"🔎 Searching the knowledge base..."** → **"📚 Found N relevant sources — drafting an
   answer..."** — via Webex's message-edit API (`PUT /v1/messages/{id}`), so the user watches
   live progress rather than sitting on one long silent wait.
3. It sends the real answer as an Adaptive Card with inline **👍 Like / 👎 Dislike** buttons
   (or, for clients that don't render cards, as plain text — reply `👍`/`👎` to rate the last
   answer instead), then removes the "thinking" placeholder.

Tapping a button (or replying `👍`/`👎`, or "helpful"/"not helpful") fires the existing feedback
loop — `FeedbackService` records the rating and folds it into future retrieval ranking (see
`FeedbackService.sourceBoost`) — and the bot follows up asking if you'd like to add a comment.

Set `webex.thinking-status-enabled: false` to skip the placeholder/live-edit step and just send
one reply, e.g. if edit/delete calls meaningfully count against a rate-limited bot token.

The Webex room id doubles as the conversation-memory session id, so every space automatically
gets its own running conversation (follow-up questions resolve using history) with zero manual
setup. Both required webhooks (`messages/created` for questions, `attachmentActions/created` for
button taps) are kept in sync with your current ngrok URL automatically — no manual Developer
Portal steps.

### Broadcasting (proactive messages)

`POST /api/webex/broadcast` lets the bot push a message to people/rooms first, instead of only
ever replying inside a space someone messaged it in:

```bash
curl -X POST https://your-public-host/api/webex/broadcast \
  -H "Content-Type: application/json" \
  -d '{
        "message": "🔔 Maintenance window tonight 10pm-12am IST.",
        "personEmails": ["alice@example.com", "bob@example.com"],
        "roomIds": [],
        "allKnownRooms": false
      }'
```

Webex auto-creates the 1:1 room the first time the bot messages a person, so no prior chat is
needed. Set `allKnownRooms: true` to also broadcast to every room the bot currently belongs to
(fetched live via `GET /v1/rooms`). Every target is attempted independently — one failed send
never aborts the rest — and the response reports a per-target success/failure breakdown.
`webex.broadcast-delay-ms` (default 250ms) paces sends to stay under Webex's rate limits.

### Secrets

The bot token now lives in `config/webex-secrets.yml` — a git-ignored file, not the tracked
`application.yml` — imported via `spring.config.import`. Copy the template and fill in your token:

```bash
cp config/webex-secrets.yml.example config/webex-secrets.yml
```

Get a token from https://developer.webex.com/my-apps → Create a Bot. `WEBEX_BOT_TOKEN` as an
environment variable still works too (`application.yml`'s `${WEBEX_BOT_TOKEN:}` falls back to it).

---

## Switching LLM Providers

The chat model is provider-switchable via Spring profiles — no code changes needed. Four
profiles ship out of the box:

| Profile | Provider | Model | Requires |
|---|---|---|---|
| `llama3` (default) | Local Ollama | `llama3` | Nothing — runs locally |
| `kimi` | Moonshot AI | `moonshot-v1-8k` | `KIMI_API_KEY` |
| `gpt` | OpenAI | `gpt-4o` | `OPENAI_API_KEY` |
| `openai` | OpenAI | `gpt-3.5-turbo` | `OPENAI_API_KEY` |

Activate one with `--spring.profiles.active=<name>` or the `SPRING_PROFILES_ACTIVE` env var:

```bash
export KIMI_API_KEY=sk-...
./mvnw spring-boot:run -Dspring-boot.run.profiles=kimi
```

Each profile is its own `application-<name>.yml` file under `src/main/resources/`, setting
`llm.provider`, `llm.model-name`, `llm.base-url`, and `llm.api-key`. Kimi and GPT/OpenAI all
reuse the same OpenAI-compatible client (`langchain4j-open-ai`) — only the base URL, model
name, and key differ — since Moonshot's Kimi API is OpenAI-compatible. The embedding model
always stays on local Ollama (`nomic-embed-text`) regardless of which chat profile is active,
since pgvector's column dimension is fixed at ingestion time.

To add another provider, create a new `application-<name>.yml` following the same shape, and
if it isn't OpenAI-compatible, add a case for it in `LangChainConfig.chatLanguageModel()`.

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
| `rag.hybrid.enabled` | `true` | Toggle keyword leg (vector-only if false) |
| `rag.hybrid.candidate-multiplier` | `4` | Candidates pulled per leg before fusion = max-results × this |
| `rag.hybrid.rrf-k` | `60` | Reciprocal Rank Fusion smoothing constant |
| `rag.hybrid.min-vector-score` | `0.5` | Cosine-similarity floor for the vector leg |
| `rag.hybrid.fts-language` | `english` | Postgres text-search configuration |
| `rag.intent-routing.enabled` | `true` | Route lookup questions (Offering Name/ID, CR ID, ...) to the Excel catalog only, and explanation questions to the DOCX narrative chunks only |
| `rag.intent-routing.fallback-when-empty` | `true` | Retry unfiltered if the category-filtered search returns nothing |
| `rag.excel.business-fields` | `{TUTI: tuti}` | Extra spreadsheet column header → metadata key mappings, for a column not already covered by the built-in known-alias table or the generic *Id/*Name/*Key/*Code fallback |
| `webex.thinking-status-enabled` | `true` | Post a "🤔 Thinking..." placeholder and live-edit it (searching/drafting) while the Webex bot works |
| `webex.attachment-actions-webhook-enabled` | `true` | Send Webex answers as Adaptive Cards with tappable 👍/👎 buttons instead of plain text |
| `webex.broadcast-delay-ms` | `250` | Pause (ms) between each send in a `/api/webex/broadcast` batch, to stay under rate limits |
| `rag.feedback.enabled` | `true` | Toggle whether accumulated thumbs up/down nudge retrieval ranking |
| `neo4j.uri` | `bolt://localhost:7687` | Neo4j Bolt connection URI |
| `neo4j.username` / `neo4j.password` | `neo4j` / `ragpassword` | Neo4j credentials (matches docker-compose.yml) |
| `rag.graph.enabled` | `true` | Master switch for the knowledge-graph feature |
| `rag.graph.auto-ingest` | `true` | Extract entities/relationships during normal file ingestion |
| `rag.graph.entity-co-occurrence` | `true` | Link entities that co-occur in the same chunk/row |
| `rag.graph.max-path-depth` | `6` | Hop ceiling for graph search / shortest-path / all-paths |
| `rag.graph.max-dependency-depth` | `4` | Hop ceiling for dependency / root-cause / impact traversals |
| `rag.graph.backfill-batch-size` | `500` | Rows read per page during `POST /api/graph/backfill` |
| `rag.graph.query-augmentation-enabled` | `false` | Add graph dependency/impact context to the RAG prompt |

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
│   │   ├── QueryController.java
│   │   └── GraphController.java     # /api/graph/* — search, dependencies, root-cause, impact, paths
│   ├── document/
│   │   ├── DocumentType.java
│   │   └── DocumentLoaderService.java   # Multi-format loader
│   ├── graph/                       # Neo4j knowledge graph feature
│   │   ├── model/                   # GraphEntity, GraphRelationship, GraphRelationType
│   │   ├── extraction/               # EntityExtractionService, RelationshipExtractionService
│   │   ├── service/                  # KnowledgeGraphService (all Neo4j reads/writes),
│   │   │                             # GraphIngestionService, GraphBackfillService,
│   │   │                             # GraphSearchService, DependencyAnalysisService,
│   │   │                             # RootCauseAnalysisService, ImpactAnalysisService,
│   │   │                             # PathFindingService, GraphContextEnricherService
│   │   └── dto/                      # GraphBackfillReport
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
