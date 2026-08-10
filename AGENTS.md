# AGENTS.md

Multi-agent diet recommendation service (Spring Boot + AgentScope/DashScope + MyBatis + MySQL). All code comments and prompts are in Chinese — keep new comments/prompts in Chinese.

## Stack & commands

- Java 21, Spring Boot 3.3.13, Maven (system `mvn`; no `mvnw` wrapper), MyBatis 3, Lombok, Hutool, MySQL 8.
- Frontend is plain vanilla JS served from `src/main/resources/static/` (index.html + app.js/api.js/app.css). No Node/npm build step.
- Build: `mvn -DskipTests compile` · Run: `mvn spring-boot:run` (port 8080).
- Test suite: JUnit 5 under `src/test/` (269 tests, all passing — run with `mvn test`). Includes unit tests for browse APIs, retrievers, recall evaluation, seed SQL/migration validation, transaction/row-lock plan invariants, typed feedback, resource providers and orchestrator seams.

## Run prerequisites

- Local MySQL at `localhost:3306` (root/123456, per `application.yml`). `createDatabaseIfNotExist=true` creates only the empty DB — Flyway then migrates it automatically (`classpath:db/migration`, baseline-on-migrate with baseline-version 1): fresh DB gets the full schema via V1..V6 (legacy baseline, requestId idempotency, reviewed resources, health profile/plan, typed feedback, plan version provenance + ACTIVE constraint); a legacy DB imported from `src/main/resources/db/diet_db.sql` gets baselined at V1 and only the incremental migrations run. Startup also idempotently imports the reviewed seed (`db/seed/reviewed_resources.sql`, INSERT IGNORE via `ReviewedResourceSeeder`): 292 meals, 30 plan_ready exercises, 15 routine facts.
- `agentscope.dashscope.api-key` in `application.yml` is the literal placeholder `请填入自己的apiKey`. Real LLM calls (intent/response/plan agents) fail until it's set or overridden (`-Dagentscope.dashscope.api-key=...` or env). LLM models: main `qwen-max`, light `qwen-turbo`.
- Legacy diet chain (`/api/v1/diet/**`) reads the `X-User-Id` header (defaults to `1` in `DietChatController`; frontend stores it in localStorage). The health chain (`/api/v1/health/**`) uses a server-issued HMAC anonymous Cookie via `AnonymousIdentityInterceptor` instead.

## Architecture

- Entrypoint: `com.diet.DietApplication` (`@MapperScan("com.diet.mapper")`). Ignore the javadoc mention of a sibling `com.agent.AgentApplication` — it does not exist in this repo.
- The core is one state machine: `DietOrchestratorService.dietChat` → session lock (in-memory per-session) → intent recognition → slot merge → clarify → meal search/rank → recommend/plan response agents → `RiskGuardService` → persist.
- Health chain: `controller/health/` (thin) → `health/orchestrator/HealthOrchestratorService` → `health/{intent,clarify,recommend,plan,module,profile,risk,session,resource,feedback,browse}`. Weekly plan: `health/plan/{WeeklyPlanService,WeeklyPlanComposerService,PlanValidationService}` (transactional, row-locked activation, immutable version provenance).
- Agents are built in `agent/builder/*` from prompt files in `src/main/resources/diet/prompts/*.txt` (intent, clarify, recommend-response, plan-response, evaluation-judge). LLM output is JSON, parsed by `util/LlmJsonService` (strips markdown fences; throws on malformed JSON).
- Resources are read only through `health/resource/HealthResourceProvider` (REVIEWED_DB via `DbReviewedResourceProvider` / FIXTURE_SEED via `SeedResourceProvider`, selected by `diet.resource.mode`). Risk rules have a single source of truth: `health/risk/RiskRuleCatalog`.
- DB `diet_db` tables: `diet_messages`, `diet_sessions` (JSON columns `slots`, `last_recommendations`), `diet_request_trace`, `diet_slot_option`, `meal_item`, `recommend_feedback`, plus health tables `health_profile(_version)`, `weekly_plan(_version/_item)`, `exercise_item`, `routine_fact`, `meal_item_embedding`. MyBatis `map-underscore-to-camel-case` is on.
- Slot values (mealTime, mood, cuisine, taste, …) are constrained by the `diet_slot_option` table — editing slot semantics usually means touching seed data + prompts.

## Gotchas

- Trace code (javadoc/comments) says "agent_traces 表" but the real table is `diet_request_trace` (see `AgentTraceMapper.xml`). Do not create an `agent_traces` table.
- This directory is inside the large backup git repo rooted at `/Users/pp/Desktop/file` (remote `Leewwp/backup`). `git` commands here see the entire parent tree — never `git add .`; stage only `code/project/health-agent/...` paths. The `groupproject-team_9` remote belongs to a different project, ignore it.
- Per the parent rules at `/Users/pp/Desktop/file/code/AGENTS.md`: UI changes must be verified in a real browser against the running app, with the URL, interactions, and result reported.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage labels are used as-is: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
