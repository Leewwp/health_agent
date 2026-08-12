# AGENTS.md

Multi-agent diet recommendation service (Spring Boot + AgentScope/DashScope + MyBatis + MySQL). All code comments and prompts are in Chinese — keep new comments/prompts in Chinese.

## Stack & commands

- Java 21, Spring Boot 3.3.13, Maven (system `mvn`; no `mvnw` wrapper), MyBatis 3, Lombok, Hutool, MySQL 8.
- Frontend is plain vanilla JS ES Modules under `frontend/` (router/api/store/ui/pages/admin modules, hash routing; `frontend/assets` + `frontend/index.html`). No Node/npm build step — served by Nginx (`deploy/nginx.conf` local, `deploy/nginx.compose.conf` for Compose) as same-origin reverse proxy for `/api/` and `/actuator/health`. The old `src/main/resources/static/` copy is deleted.
- Build: `mvn -DskipTests compile` · Run: `mvn spring-boot:run` (port 8080).
- Test suite: JUnit 5 under `src/test/` — `mvn test` discovers 534 tests (501 pass + 33 environment gates skipped); `mvn test -Ditest.mysql=true` runs 30 real-MySQL integration tests (`MysqlTransactionIntegrationTest` 16 + `MysqlReviewedDbPlanMealIntegrationTest` 4 + `MysqlReviewedReadersIntegrationTest` 10 — the last covers #68 reviewed reader audit/source filtering/pagination/recheck and #69 reviewed browse + Structured/Hybrid re-check against the seeded 295/30 baseline, needs local MySQL root/123456) for 531 pass + only the Qdrant gates skipped. `mvn test -Ditest.mysql=true -Ditest.qdrant=true` runs all 534 when local Qdrant 1.17.0 is on gRPC port 6334. Maven rewrites the CLI flags to system properties `itest.*`; CI (`.github/workflows/ci.yml`) enables the MySQL gate with a MySQL 8.4 service container. Includes tests for browse APIs, retrievers, recall evaluation, seed SQL/migration validation, transaction/row-lock plan invariants, typed feedback, resource providers, orchestrator seams, MCP and Qdrant compatibility, the VectorStore adapters (in-memory unit + Qdrant integration), MCP endpoint security boundary (token/Origin), Trace redaction, the four MCP tools (schema rejection/business error mapping + #63 outputSchema contract), Skills Registry validation and MCP Resources, the hybrid two-path vector fusion with MySQL re-check, and the structured profile risk fields with plan-write Guards (#62).

## Run prerequisites

- Local MySQL at `localhost:3306` (root/123456, per `application.yml`). `createDatabaseIfNotExist=true` creates only the empty DB — Flyway then migrates it automatically (`classpath:db/migration`, baseline-on-migrate with baseline-version 1): fresh DB gets the full schema via V1..V6 (legacy baseline, requestId idempotency, reviewed resources, health profile/plan, typed feedback, plan version provenance + ACTIVE constraint); a legacy DB imported from `src/main/resources/db/diet_db.sql` gets baselined at V1 and only the incremental migrations run. Startup also idempotently imports the reviewed seed (`db/seed/reviewed_resources.sql`, INSERT IGNORE via `ReviewedResourceSeeder`): 295 meals, 30 plan_ready exercises, 15 routine facts.
- `agentscope.dashscope.api-key` in `application.yml` is the literal placeholder `请填入自己的apiKey`. Real LLM calls (intent/response/plan agents) fail until it's set or overridden (`-Dagentscope.dashscope.api-key=...` or env). LLM models are configured via `diet.llm.main-model` / `diet.llm.light-model` (both default to `deepseek-v4-flash-0731`; the earlier interview MaaS acceptance used `qwen3.8-max`/`qwen3.7-flash`, and M5 acceptance used `deepseek-v4-flash-0731`; embedding used `qwen3.7-text-embedding` overrides). `diet.llm.openai-compatible=true` switches chat to the OpenAI-compatible endpoint (`OpenAiCompatibleChatModel`, needed for MaaS spaces that expose only `/compatible-mode/v1`); embedding base-url is independent (`diet.embedding.base-url`, native `/api/v1` for MaaS spaces).
- Legacy diet chain (`/api/v1/diet/**`) reads the `X-User-Id` header (defaults to `1` in `DietChatController`; frontend stores it in localStorage). The health chain (`/api/v1/health/**`) uses a server-issued HMAC anonymous Cookie via `AnonymousIdentityInterceptor` instead.

## Architecture

- Entrypoint: `com.diet.DietApplication` (`@MapperScan("com.diet.mapper")`). Ignore the javadoc mention of a sibling `com.agent.AgentApplication` — it does not exist in this repo.
- The core is one state machine: `DietOrchestratorService.dietChat` → session lock (in-memory per-session) → intent recognition → slot merge → clarify → meal search/rank → recommend/plan response agents → `RiskGuardService` → persist.
- Health chain: `controller/health/` (thin) → `health/orchestrator/HealthOrchestratorService` → `health/{intent,clarify,recommend,plan,module,profile,risk,session,resource,feedback,browse}`. Weekly plan: `health/plan/{WeeklyPlanService,WeeklyPlanComposerService,PlanValidationService}` (transactional, row-locked activation, immutable version provenance).
- Agents are built in `agent/builder/*` from prompt files in `src/main/resources/diet/prompts/*.txt` (intent, clarify, recommend-response, plan-response, evaluation-judge). LLM output is JSON, parsed by `util/LlmJsonService` (strips markdown fences; throws on malformed JSON).
- Resources are read only through `health/resource/HealthResourceProvider` (REVIEWED_DB via `DbReviewedResourceProvider` / FIXTURE_SEED via `SeedResourceProvider`, selected by `diet.resource.mode`). **方案 B 读取边界（#66/#67，ADR-0010）**：正式审核库的餐食浏览/Structured/Hybrid RAG/Embedding/向量索引/评估快照经 `health/reader/meal`（`ReviewedMealReader` + `DbReviewedMealReader`），动作浏览经 `health/reader/exercise`（`ReviewedExerciseReader` + `DbReviewedExerciseReader`）；动作词汇归一抽为无 I/O 共享模块 `health/reader/exercise/ExerciseVocabulary`（Provider 与动作读取 adapter 共用）。只有 `DbReviewedResourceProvider`、`DbReviewedMealReader`、`DbReviewedExerciseReader` 三个类可依赖 MealMapper/ExerciseMapper/RoutineFactMapper，其余健康调用方一律经接口消费——由 `src/test/java/com/diet/architecture/HealthResourceReadBoundaryArchitectureTest.java` 固定（违规即失败）。FIXTURE_SEED 不是正式库浏览/RAG/索引能力的等价模式；fixture 下显式启用三个正式 DB runner（generate/index/eval-on-startup）必须 fail-fast。Risk rules have a single source of truth: `health/risk/RiskRuleCatalog`.
- DB `diet_db` tables: `diet_messages`, `diet_sessions` (JSON columns `slots`, `last_recommendations`), `diet_request_trace`, `diet_slot_option`, `meal_item`, `recommend_feedback`, plus health tables `health_profile(_version)`, `weekly_plan(_version/_item)`, `exercise_item`, `routine_fact`, `meal_item_embedding`. MyBatis `map-underscore-to-camel-case` is on.
- Slot values (mealTime, mood, cuisine, taste, …) are constrained by the `diet_slot_option` table — editing slot semantics usually means touching seed data + prompts.

## Gotchas

- Trace code (javadoc/comments) says "agent_traces 表" but the real table is `diet_request_trace` (see `AgentTraceMapper.xml`). Do not create an `agent_traces` table.
- This repo's git root is `health-agent/` itself, remote `origin` = `Leewwp/health_agent` (main 与 origin 同步). 上级目录 `/Users/pp/Desktop/file` 仍是另一个备份 git 仓库（remote `Leewwp/backup`）——不要在该目录执行本仓库的 git 操作。`groupproject-team_9` remote 属于另一个项目，忽略。
- Deployment: `docker compose up -d --build` (mysql 8.4 + app multi-stage JRE 21 + nginx same-origin proxy, prod profile requires `DASHSCOPE_API_KEY`/`DIET_SESSION_SECRET`/`ADMIN_TOKEN` per `deploy/.env.example`). CI runs on push/PR to `main`; acceptance evidence checklist lives in `docs/release-evidence.md` (M0-M4).
- Per the parent rules at `/Users/pp/Desktop/file/code/AGENTS.md`: UI changes must be verified in a real browser against the running app, with the URL, interactions, and result reported.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/` in this repo (tracker at `https://github.com/Leewwp/health_agent/issues` for the 39-45 审计批次；`.scratch/health-agent/map.md` 维护索引与状态). See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage labels are used as-is: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
