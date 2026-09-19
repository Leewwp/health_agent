# health-agent · Unified three-domain health assistant

[![English](https://img.shields.io/badge/English-2f81f7?style=flat-square)](README.md)
[![简体中文](https://img.shields.io/badge/简体中文-d0d7de?style=flat-square)](README.zh-CN.md)

[![CI](https://github.com/Leewwp/health_agent/actions/workflows/ci.yml/badge.svg)](https://github.com/Leewwp/health_agent/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-brightgreen)
![MySQL 8.4](https://img.shields.io/badge/MySQL-8.4-blue)
![Tests](https://img.shields.io/badge/tests-910%20%2F%200%20fail-success)
![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-purple)

A unified conversational health assistant covering **diet, exercise, and daily routine**: a Java state machine deterministically orchestrates a multi-role workflow while the LLM only handles semantic understanding and constrained expression. It ships with hybrid meal retrieval (RAG), a health profile with transactional weekly plans, contract-based degradation, risk guardrails, end-to-end tracing, and an offline evaluation system — and exposes read-only domain capabilities via MCP.

> **Terminology note**: "multi-agent" here means a multi-role workflow deterministically orchestrated by a Java state machine — **not** multiple autonomous agents planning or calling tools on their own. Candidate recall, numerics, risk, and state transitions are controlled by Java; the LLM handles semantic understanding and constrained expression.

**Engineering scale**: 910 automated tests (`mvn test`, measured 2026-08-31: 0 failures, 53 environment-gated skips), 12 real-MySQL integration test classes (gated by `-Ditest.mysql=true`), 43 frontend behavior-contract tests, Flyway V1–V24 migrations, GitHub Actions CI, one-command Docker Compose deployment. Starts and demos fully without an API key — deterministic degradation is itself a live demonstration of the fallback design.

---

## Table of contents

- [Evolution: from diet-agent to health-agent](#evolution-from-diet-agent-to-health-agent)
- [Core design](#core-design)
- [Runtime architecture](#runtime-architecture)
- [Quick start](#quick-start)
- [Meal RAG and evaluation](#meal-rag-and-evaluation)
- [MCP server](#mcp-server)
- [Tests and CI](#tests-and-ci)
- [Configuration](#configuration)
- [Data pipeline](#data-pipeline)
- [Deployment (Compose)](#deployment-compose)
- [Documentation map](#documentation-map)
- [Boundaries](#boundaries)

---

## Evolution: from diet-agent to health-agent

This project is a refactor-and-upgrade of a single-domain diet recommendation agent (diet-agent). The old project validated the architectural bet of "multi-agent + tracing + evaluation" but carried engineering debt: 0 tests, no database migrations, no unified LLM-call contract, and recommendations without retrieval augmentation. The upgrade did two things:

| Dimension | diet-agent (old) | health-agent (new) |
| --- | --- | --- |
| Product scope | single-domain diet recommendations | three-domain unified health chat; legacy `/api/v1/diet/**` kept compatible |
| Intent model | 6-intent enumeration + 7 slot dimensions | orthogonal intent model: domain × task × riskFlags × phase |
| LLM calls | business services hold `ReActAgent` directly | `AgentInvoker` runtime-interface isolation + unified contract + six failure classes |
| Failure handling | ad-hoc fallbacks scattered per step | contract-based, classified, observable deterministic degradation |
| Retrieval | MySQL tag search + scoring | hybrid RAG + two-stage routing + hard-constraint re-check by ID lookback |
| Planning | verbal multi-meal suggestions | persistable weekly plans: lifecycle + transactions + row locks + unique constraints + version snapshots |
| Tests | **0** | **910** (including real-MySQL integration scenarios) |
| Data engineering | manual dumps | Flyway V1–V24 + a Python ETL pipeline |

## Core design

### 1. Orthogonal intent model and deterministic orchestration

A 6-intent enumeration was enough for a single domain; three domains cause combinatorial explosion (3 domains × 5 tasks = 15 enum values, and every new dimension ripples through the whole chain). Refactored into an orthogonal decomposition:

```text
domain          MEAL / EXERCISE / ROUTINE / COMPOSITE     → picks the domain module
task            CHAT / BROWSE / RECOMMEND / PLAN / ADJUST → picks the flow shape
riskFlags       decided by RiskRuleCatalog                 → overlays any intent, triggers blocking
phase           conversation state-machine stage           → decoupled from intent
```

Intent decides the flow, risk decides blocking, phase decides state — the three never pollute each other. `HealthOrchestratorService` manages state transitions and routing; `MealModule` / `ExerciseModule` / `RoutineModule` each own retrieval and generation; when the topic switches across domains, an unfinished plan brief is suspended, not discarded. Stated preferences (`preferenceSignals`, e.g. "I don't eat cilantro") and requirement slots ("something light") are expressed separately, so transient preferences are never mistaken for long-term ones.

### 2. Contract-based agent invocation and deterministic degradation

From a production standpoint the LLM is an **unreliable dependency**: timeouts, rate limits, invalid JSON, out-of-range fields, and fabricated candidate IDs all happen. Degradation was upgraded from "scattered if-patches" to a contract:

- **Dependency inversion**: the `AgentInvoker` runtime interface isolates AgentScope — `AgentScopeInvoker` (real DashScope, main/light dual-model routing balancing latency and cost) and `FixtureAgentInvoker` (versioned fixed fixtures) are swappable, and business modules never touch `ReActAgent`; without an API key, the full chain still tests and demos;
- **Unified contract**: every agent call carries contractVersion / promptVersion / input-output DTOs / validators; output must pass JSON parsing (markdown fences stripped) → schema validation → enum validation → candidate-ID validation;
- **Failure classification and deterministic degradation**: six failure classes `TIMEOUT / UPSTREAM_UNAVAILABLE / INVALID_JSON / SCHEMA_VIOLATION / CANDIDATE_VIOLATION / MISSING_CONFIG`, each with a deterministic fallback path (template takeover); the failure reason goes into the trace. On any LLM exception the conversation always has a reply, and no dirty state leaks into the next request.

### 3. Hybrid-retrieval RAG with two-stage routing

Design principle: **RAG only recalls candidates; hard-constraint filtering and final ranking always happen in Java/MySQL** — semantic similarity does not mean constraint satisfaction, and recalling a meal containing an allergen to an allergic user is an incident.

```text
user input
  → MealRetrievalRouter two-stage routing
      ├─ strong constraints (meal type / allergens / exclusions) → Structured retrieval
      └─ no strong constraints + subjective/long-tail semantic words → Hybrid retrieval
            ├─ structured recall (MySQL)
            └─ standalone Qdrant vector recall (payload-filtered by review
               status / source / allergens / excluded IDs)
                  → candidate fusion (weights configurable, default 0.5/0.5)
                  → re-query MySQL by ID and re-apply every hard constraint
                  → stale-index hits dropped outright
  → scoring and ranking → the LLM only generates recommendation reasons
    for already-ranked candidates
```

- **MySQL is the source of truth; Qdrant is only a rebuildable index**: collection names derive from provider + model + dimension + version identity, an embedding change automatically moves to a new collection, and a dimension mismatch degrades immediately;
- **Explicit degradation**: when embedding/Qdrant is unavailable, times out, or returns nothing, the system falls back to structured retrieval immediately and tags the reason (`vector_store_unavailable` / `embedding_unavailable` / `no_vector_hits`); evaluation reports tally the degradation distribution separately;
- **Dual embedding adapters**: DashScope text-embedding-v3 by default, MiniMax embo-01 as an experimental comparison, switchable via configuration.

[See below for evaluation results and how two-stage routing was derived](#meal-rag-and-evaluation).

### 4. Health profile and transactional weekly plans

Numeric conclusions (energy, macros, training dosage) must never be LLM-fabricated:

- **Deterministic quantification**: the health profile plus the Mifflin-St Jeor equation computes daily energy ranges, with explainable inputs and computation basis;
- **Plan brief**: structured requirements are collected before generation and support colloquial edits — weekday and Chinese time expressions are parsed deterministically into five classes `EXTRACTED / PARTIAL / AMBIGUOUS / UNRELATED / INVALID`; only when rules cannot parse safely does a single structured-extraction agent run as backup, with candidates validated in Java before merging;
- **Constrained generation**: the LLM may only schedule from the "plan-eligible" candidate whitelist; eligibility, risk, schedule, and dosage are validated by deterministic rules (Java Guard); when the agent is unavailable, the rules generate from the same brief — a plan can never become impossible because the LLM is down;
- **Scope isolation**: a new plan allows only EXERCISE / MEAL / COMPOSITE scopes; a composite plan is merged by a deterministic service only after both sub-briefs are confirmed;
- **Lifecycle and concurrency correctness**: a DRAFT → UNENABLED → ENABLED → HISTORY state machine; transactional writes + row locks + a per-user database-level ENABLED unique constraint + requestId idempotency + version snapshots (five classes of generation basis — profile/rules/session/facts/resources — go into the snapshot). Real-MySQL integration tests verify: no half-written artifacts on failure at any step, exactly one ENABLED under concurrent activation.

### 5. Risk governance (RiskRuleCatalog)

Risk interception (pregnancy, extreme dieting, medical-diagnosis requests) converges into a **single versioned rule catalog** — the single source of truth, with its own consistency tests. NORMAL / ADVISORY / BLOCK_PLAN tiers use fixed copy, never improvised by the LLM; guards run at three stages — pre-candidate, at composition, and post-output — so risk checking never depends on a single exit point. The LLM may help interpret what the user said, but risk decisions and risk copy never pass through the model.

### 6. Observable, evaluable, and open

- **End-to-end tracing + PII masking**: every step event, agent call, token count, latency, and degradation reason is persisted; sensitive inputs such as health profiles are logged only as digests or masked values; an admin trace-diagnosis workbench renders event timelines and masked JSON by stepOrder (protected by `ADMIN_TOKEN`);
- **health-eval-v2 evaluation engine**: a 36-sample versioned annotated benchmark, 10+ metrics all with valid denominators (missing gold records null, never a zero score), regression-runnable in fixture mode; reports record git commit / dataset version / rule version, so numbers are traceable;
- **MCP server**: domain capabilities exposed to the external agent ecosystem over a standard protocol ([see below](#mcp-server)).

## Runtime architecture

![runtime architecture](docs/architecture.png)

(Exported from [docs/runtime-architecture.html](docs/runtime-architecture.html); the interactive version opens directly in a browser.)

Component overview:

| Component | Responsibility |
| --- | --- |
| Frontend SPA (`frontend/`) | build-free native ES Modules: hash routing / fetch wrapper / light store / chat, browse, profile, plans, admin pages |
| Nginx | static hosting + same-origin reverse proxy for `/api/`, `/mcp`, and health checks — no CORS |
| Identity | HMAC anonymous cookie (`HEALTH_SESSION`) instead of client-asserted identity; admin token isolated |
| Orchestration | `HealthOrchestratorService` state machine: intent → clarification → RiskGuard → domain module |
| Agent layer | `AgentInvoker` contract-based calls to DashScope / fixtures; six failure classes and deterministic degradation |
| Retrieval | `MealRetrievalRouter` two-stage routing; structured (MySQL) / hybrid (+Qdrant) dual implementations |
| Planning | brief → constrained generation → Java Guard → transactional activation; version snapshots |
| Data | MySQL 8 (Flyway V1–V24); Qdrant vector index (rebuildable, not the source of truth) |

## Quick start

Requirements: Java 21, Maven 3.9+, MySQL 8.

### One-command start (recommended)

```bash
./scripts/start-local.sh     # starts and opens http://localhost:8092/#/chat
./scripts/stop-local.sh      # stops backend and frontend Nginx containers (MySQL/Qdrant data untouched)
```

What the script does: checks Java 21 / Maven / Docker / local MySQL → builds and starts Spring Boot in the background → waits for `/actuator/health` UP → rebuilds the stateless frontend Nginx on port 8092 with same-origin proxying → opens the browser after self-checks pass. Logs live in `.local-run/logs/`. If a port is occupied by an unrelated process it exits with an error instead of silently switching ports (use `BACKEND_PORT=8083 FRONTEND_PORT=8093` to change ports).

Pages: health chat (`#/chat`), meal browse (`#/meals`), exercise browse (`#/exercises`), health profile (`#/profile`), weekly plans (`#/plans`), admin (trace diagnostics / evaluation reports).

### Manual start

1. Start local MySQL (default `root/123456`);
2. `mvn spring-boot:run` (Flyway auto-migrates: fresh databases get full schemas, legacy databases are auto-baselined; reviewed resources are imported idempotently at startup);
3. Put `DASHSCOPE_API_KEY=...` in the repository-root `.env` (git-ignored; without a key the chat degrades deterministically to templates and the service still starts).

The service runs at `http://localhost:8080` by default.

## Meal RAG and evaluation

Fixed annotated query set: 60 queries across six layers (exact tags / natural language / long-tail phrasing / synonyms / exclusions / allergens — 10 each). REAL_HYBRID run on 2026-08-27 against local MySQL + Qdrant (295/295 indexed, 60/60 with zero degradation):

| Metric | Structured | Hybrid | Delta |
| --- | ---: | ---: | ---: |
| Recall@3 | 0.2646 | 0.2716 | +0.0070 |
| Precision@3 | 0.7778 | 0.7944 | +0.0167 |
| Hard-constraint hit rate | 1.000 | 1.000 | 0 |
| P95 latency | 9.1 ms | 247.4 ms | +238 ms |

Semantic fusion brings a small recall gain but amplifies latency 27×. Next, a 12-query human-annotated semantic challenge set compares three strategies:

| Strategy | Recall@10 | Avg latency | Hard-constraint violations |
| --- | ---: | ---: | ---: |
| Structured | 0.2167 | 5.7 ms | 0 |
| Hybrid (all queries) | 0.2500 | 212.2 ms | 0 |
| **TwoStage routing** | 0.2167 | **83.7 ms** | 0 |

Two-stage routing keeps strongly-constrained queries on the structured path and sends only unconstrained semantic wording to the vector path: latency drops to 40% of full hybrid with zero hard-constraint violations. Hence Structured is the online default and Hybrid/TwoStage stay as switchable experimental paths — decided by evaluation, not by gut.

Evaluation reports record per-query structured/vector/fused candidate counts and per-stage latencies; the top level is tagged `runClassification` (REAL_HYBRID / PARTIAL_HYBRID / FALLBACK_ONLY) — a degraded run may never be cited as hybrid performance. Numbers are authoritative in `data/reports/rag_evaluation.json` and `semantic_challenge_v1.json`; the full record is in [docs/research/meal-rag-evaluation.md](docs/research/meal-rag-evaluation.md).

### Running the evaluation and vector indexing (requires a real API key)

```bash
# generate embeddings (idempotent writes to meal_item_embedding)
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.embedding.generate-on-startup=true"
# run the fixed query-set evaluation → data/reports/rag_evaluation.json
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.rag.eval-run=true"
# bulk-index to Qdrant (after Compose is up, gRPC 6334)
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.vectorstore.mode=qdrant --diet.vectorstore.index-on-startup=true"
```

The MiniMax embo-01 comparison experiment needs `DIET_EMBEDDING_PROVIDER=minimax` and related variables in `.env` plus a separate report path (`rag_evaluation_minimax.json`); it is for effect comparison only and does not change the online Structured default. Never commit `.env` or any real key.

## MCP server

The application registers a single MCP Streamable HTTP endpoint at `/mcp` (MCP Java SDK 0.17.0 servlet transport) for external MCP clients to discover and call read-only / pure-computation tools:

| Tool | Purpose | Reused domain service |
| --- | --- | --- |
| `search_meals` | search reviewed meals by health slots (incl. hard constraints and hybrid retrieval) | `MealModule.recommendMeals` |
| `get_meal_detail` | fetch reviewed meal detail by resource ID | `HealthResourceProvider.mealById` |
| `get_routine_facts` | look up structured routine facts by keyword | `RoutineModule.lookup` |
| `calculate_targets` | deterministically compute daily energy ranges (no profile writes) | `EnergyCalculator` |

Design notes:

- handlers call domain services directly — no HTTP self-callbacks, zero business writes;
- **Skills Registry**: three versioned skill manifests (YAML), strictly validated at startup (schema parseable, `allowed_tools` within the whitelist, unique names); an invalid manifest refuses to boot; exposed as stable `skill://<name>` URIs;
- **Fail-closed security boundary**: Bearer `MCP_API_TOKEN` authentication — with no token configured, all requests are refused; exact Origin allowlist (scheme/host/port all equal) — an empty allowlist does not mean allow-all; when prod enables the token, an explicit allowlist must be configured or startup fails. This implementation does not claim full MCP OAuth 2.1 compliance.

## Tests and CI

```bash
mvn test                            # 910 tests: 0 failures, 53 environment-gated skips (measured 2026-08-31)
mvn test -Ditest.mysql=true         # real-MySQL integration: 12 test classes all green; only Qdrant/live-model separately gated and skipped
node --test frontend/tests/*.test.mjs   # frontend behavior contracts 43/43 (measured 2026-08-31)
```

- **Unit / contract layer**: agent contracts (valid/invalid JSON, schema/candidate violations, timeouts, missing key), intent routing, plan briefs and topic switching, risk-catalog consistency, idempotency and trace content, MCP endpoint security boundaries, trace masking, hybrid retrieval and two-stage routing, meal facet data contracts (`data/meal/facets.json` as the single source of truth + drift guards);
- **Real-MySQL integration layer** (gated by `-Ditest.mysql=true`, isolated test database auto-migrated): transactional rollback with no half-written artifacts, row locks, concurrent-enable uniqueness, profile version consistency, scoped-generation requestId idempotency, eight-slot AND/OR/"three-meals" compatibility and stable ordering;
- **CI**: GitHub Actions, Java 21 + a MySQL 8.4 service container running the full MySQL-gated suite, with surefire reports uploaded on failure;
- **Fixture and real dual modes**: fixtures guarantee regression stability (CI runs the full suite stably); real models verify integration by smoke tests — the two complement each other and neither replaces the other.

## Configuration

| Environment variable | Purpose | Default |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | DashScope model key (`.env` / system env; empty → degrade) | empty |
| `DASHSCOPE_BASE_URL` | DashScope-compatible endpoint | official address |
| `DIET_LLM_MAIN_MODEL` / `DIET_LLM_LIGHT_MODEL` | main generation / light model | `qwen-turbo` |
| `DIET_SESSION_SECRET` | anonymous-cookie HMAC secret | dev-only |
| `ADMIN_TOKEN` | admin diagnostics token | empty (unprotected in dev) |
| `DATABASE_URL/USERNAME/PASSWORD` | prod datasource | dev uses local root/123456 |
| `DIET_VECTORSTORE_MODE` | vector index mode (in-memory/qdrant) | `in-memory` |
| `QDRANT_HOST/QDRANT_GRPC_PORT` | Qdrant gRPC address | `localhost`/`6334` |
| `MCP_API_TOKEN` | `/mcp` Bearer token (fail-closed when unset) | empty |
| `MCP_ALLOWED_ORIGINS` | Origin allowlist (comma-separated) | empty |
| `DIET_MCP_ALLOW_MISSING_ORIGIN` | whether a missing Origin is allowed through | `false` |

- **dev** (default): `X-User-Id` fallback allowed, admin unprotected;
- **prod** (`--spring.profiles.active=prod`): `X-User-Id` refused, `ADMIN_TOKEN` enforced, cookies Secure; missing `DASHSCOPE_API_KEY` / `DIET_SESSION_SECRET` / `ADMIN_TOKEN` fail startup (fail-closed);
- **Agent mode**: `diet.agent.mode=fixture` for offline demos; default `agentscope` uses real models; prod forces `agentscope`.

## Data pipeline

Python ETL (`scripts/build_reviewed_resources.py`): 1,000-recipe CSV + 1,324-exercise dataset → cleaning → 295 reviewed meals + 30 plan_ready exercises + 15 routine facts as seed SQL + an ETL report (`data/reports/resource_etl_report.json`). Raw exercise fields (target/synergist muscle groups, equipment) are imported faithfully; missing fields are explicitly marked and excluded from plan eligibility. Reviewed resources are imported idempotently at startup by `ReviewedResourceSeeder`; meal facets use `data/meal/facets.json` as the canonical vocabulary and single source of truth (ETL, the Java normalizer, prompts, and frontend filters are all generated from or read against it).

## Deployment (Compose)

```bash
cp deploy/.env.example .env   # fill in DIET_SESSION_SECRET / ADMIN_TOKEN etc.
docker compose up -d --build
```

Single-instance Nginx + Spring Boot + MySQL (optional Qdrant). Back up with `mysqldump` before migrating; on failure, roll back to the previous image tag or fix forward — no destructive automatic rollback; health check at `/actuator/health`.

## Smoke examples

```bash
# health chat (three domains)
curl -X POST http://localhost:8080/api/v1/health/chat -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-1","message":"午餐想吃清淡的"}'
# risk interception
curl -X POST http://localhost:8080/api/v1/health/chat -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-2","message":"我怀孕了怎么安排饮食"}'
# reviewed-resource browsing (paginated, size≤50)
curl "http://localhost:8080/api/v1/health/meals?page=1&size=20"
curl "http://localhost:8080/api/v1/health/exercises?page=1&size=20"
```

## Documentation map

| Topic | Location |
| --- | --- |
| Architecture decision records (17 ADRs) | [docs/adr/](docs/adr/) (0002 orthogonal intents, 0004 RAG boundary, 0006 layered risk, 0010 read boundary, 0014 unified weekly plan, 0016 explicit task routing, 0017 meal facets …) |
| Domain vocabulary and product concepts | [CONTEXT.md](CONTEXT.md) |
| Architecture / schema / test baseline | [AGENTS.md](AGENTS.md) |
| RAG evaluation record | [docs/research/meal-rag-evaluation.md](docs/research/meal-rag-evaluation.md) |
| Raw evaluation reports | `data/reports/*.json` |
| MVP-phase scope and evidence | [docs/mvp-phases.md](docs/mvp-phases.md), [docs/release-evidence.md](docs/release-evidence.md) |
| Interview-suitability review | [docs/agent-mvp-suitability-review.md](docs/agent-mvp-suitability-review.md) |
| Frontend browser acceptance | [docs/frontend-browser-acceptance.md](docs/frontend-browser-acceptance.md) |

## Boundaries

This is an engineering demonstration project (single-instance delivery topology, ADR-0007), not a production system:

- no real account system or online traffic — identity is a server-issued anonymous demo identity;
- no medical diagnosis: aimed at the general healthy population aged 18+; risk interception uses conservative fixed copy, and BLOCK_PLAN blocks outright;
- tests and evaluation run mostly in fixture mode (real models verified by smoke tests); all RAG numbers are labeled with their run scope (single run, 295-item reviewed-subset corpus, single-annotator query set) and are not extrapolated as stable gains;
- the evaluation benchmark is one annotator, two passes — no claim of multi-annotator statistical significance;
- concurrency correctness relies on database constraints (not a clustered solution); no message queue, no multi-instance locks, no long-term behavior tracking.
