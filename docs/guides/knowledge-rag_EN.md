# 📚 Knowledge Base QA (RAG)

> Deep dive behind the README "REST API" section: gating conditions, multi-KB & agent binding, directory watching / BM25 hybrid retrieval.

---

Drop documents into the `knowledge/` directory (`.md` / `.txt`, optional front-matter `title:`); after ingestion both paths automatically retrieve & inject before answering:

```bash
mkdir -p knowledge && cp your-doc.md knowledge/
curl -X POST http://localhost:8080/api/knowledge/sync          # incremental ingestion (only mtime-changed docs re-embedded)
curl 'http://localhost:8080/api/knowledge/search?q=deploy'     # debug retrieval hit view
```

Answers carry `【Source N】` inline citations, the CLI prints a "Sources:" footer, and `meta.sources` / `ChatResponse.sources` expose structured provenance (doc name / title / score). Configuration (top-k / min score / injection budget) lives under `app.knowledge.*` in `application.yaml`.

#### 🗂️ Multiple Knowledge Bases & Agent Binding

Each first-level subdirectory of `knowledge/` is a standalone knowledge base (kb id); loose files at the root belong to the shared `default` base:

```bash
mkdir -p knowledge/java knowledge/frontend        # subdirectory = knowledge base
cp spring.md knowledge/java/ && cp vue.md knowledge/frontend/
curl -X POST http://localhost:8080/api/knowledge/sync
```

Set the `agent` table's `knowledge` column to a comma-separated list of kb ids (e.g. `java,frontend`) to bind an agent to specific bases — retrieval filters on the vector metadata `kb` field (`kb in [...]`), so an agent only reads its bound bases; leave it empty/NULL to search all knowledge. Moving a document across subdirectories (kb change) triggers automatic re-ingestion on the next sync.

#### 🔍 RAG Trigger Logic

RAG is never triggered by explicit commands — it is a **bypass check before every prompt assembly**; unmet conditions degrade silently with zero side effects on the main flow.

Trigger decision flow:

```mermaid
flowchart TD
    A[User request<br/>Path A direct answer / Path B orchestration node] --> B["AgentRequestSpecFactory<br/>before assembling system prompt"]
    B --> C{"app.knowledge.enabled?"}
    C -->|false| X[Silent skip<br/>zero side effects]
    C -->|true| D{"Agent role in skip list?<br/>aggregator: its material is subtask results"}
    D -->|yes| X
    D -->|no| E{"User text length ≥<br/>min-query-chars (8)?"}
    E -->|no| X
    E -->|yes| F["Vector search: user text → DashScope embedding<br/>→ pgvector cosine top-k (4)"]
    F --> G{"Hits with score ≥<br/>min-score (0.5)?"}
    G -->|no| X
    G -->|yes| H["Accumulate tokens per hit:<br/>hits beyond context-budget (3000) are truncated"]
    H --> I["Render knowledge block with 【Source N】<br/>append to system prompt"]
    I --> J["Answer: inline 【Source N】 citations<br/>+ meta.sources + CLI source footer"]
```

Retrieval injection sequence:

```mermaid
sequenceDiagram
    participant U as User
    participant F as AgentRequestSpecFactory
    participant R as KnowledgeRetriever
    participant S as KnowledgeService
    participant E as DashScope Embedding
    participant V as pgvector
    participant L as LLM
    U->>F: Request (Path A / Path B node)
    F->>R: buildKnowledgeBlock(agent, sessionId, user)
    R->>R: enabled / role / query-length short-circuits
    R->>S: search(query)
    S->>E: Embed query
    S->>V: cosine similarity top-k
    V-->>S: Hit chunks
    S-->>R: List<KnowledgeHit>
    R-->>F: Knowledge block (【Source N】 within budget) or null
    F->>L: system prompt (+ knowledge block)
    L-->>U: Answer with inline citations + meta.sources
```

Trigger conditions (fully config-driven, tune via `application.yaml`):

| Trigger condition | Config key | Current value | When unmet |
|---|---|---|---|
| Knowledge base enabled | `enabled` | true | silent skip |
| Role not in skip list | — (aggregator skipped by policy) | aggregator | silent skip |
| Query length threshold | `min-query-chars` | 8 | silent skip |
| Hit score threshold | `min-score` | 0.5 | drop the hit |
| Within injection budget | `context-budget` | 3000 | truncate budget-exceeding hits |


---

[⬅ Back to README](../../README.md)
