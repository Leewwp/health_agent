# AGENTS.md

Multi-agent diet recommendation service (Spring Boot + AgentScope/DashScope + MyBatis + MySQL). All code comments and prompts are in Chinese — keep new comments/prompts in Chinese.

## Stack & commands

- Java 21, Spring Boot 3.3.13, Maven (system `mvn`; no `mvnw` wrapper), MyBatis 3, Lombok, Hutool, MySQL 8.
- Frontend is plain vanilla JS served from `src/main/resources/static/` (index.html + app.js/api.js/app.css). No Node/npm build step.
- Build: `mvn -DskipTests compile` · Run: `mvn spring-boot:run` (port 8080).
- There is **no test suite** (`src/test` does not exist). Verify with compile + run + curl against `http://localhost:8080`.

## Run prerequisites

- Local MySQL at `localhost:3306` (root/123456, per `application.yml`). `createDatabaseIfNotExist=true` creates only the empty DB — you must import `src/main/resources/db/diet_db.sql` (Navicat dump: schema + seed data for `diet_slot_option` and `meal_item`) before first run.
- `agentscope.dashscope.api-key` in `application.yml` is the literal placeholder `请填入自己的apiKey`. Real LLM calls (intent/response/plan agents) fail until it's set or overridden (`-Dagentscope.dashscope.api-key=...` or env). LLM models: main `qwen-max`, light `qwen-turbo`.
- HTTP requests need `X-User-Id` header (defaults to `1` in `DietChatController`). Frontend stores it in localStorage.

## Architecture

- Entrypoint: `com.diet.DietApplication` (`@MapperScan("com.diet.mapper")`). Ignore the javadoc mention of a sibling `com.agent.AgentApplication` — it does not exist in this repo.
- The core is one state machine: `DietOrchestratorService.dietChat` → session lock (in-memory per-session) → intent recognition → slot merge → clarify → meal search/rank → recommend/plan response agents → `RiskGuardService` → persist.
- Flow: `controller/` (thin) → `service/orchestrator/DietOrchestratorService` → `service/{intent,clarify,recommend,plan,evaluation,risk,meal,slot,session,trace}`.
- Agents are built in `agent/builder/*` from prompt files in `src/main/resources/diet/prompts/*.txt` (intent, clarify, recommend-response, plan-response, evaluation-judge). LLM output is JSON, parsed by `util/LlmJsonService` (strips markdown fences; throws on malformed JSON).
- DB `diet_db` tables: `diet_messages`, `diet_sessions` (JSON columns `slots`, `last_recommendations`), `diet_request_trace`, `diet_slot_option`, `meal_item`, `recommend_feedback`. MyBatis `map-underscore-to-camel-case` is on.
- Slot values (mealTime, mood, cuisine, taste, …) are constrained by the `diet_slot_option` table — editing slot semantics usually means touching seed data + prompts.

## Gotchas

- Trace code (javadoc/comments) says "agent_traces 表" but the real table is `diet_request_trace` (see `AgentTraceMapper.xml`). Do not create an `agent_traces` table.
- This directory is inside the large backup git repo rooted at `/Users/pp/Desktop/file` (remote `Leewwp/backup`). `git` commands here see the entire parent tree — never `git add .`; stage only `code/project/diet-agent/...` paths. The `groupproject-team_9` remote belongs to a different project, ignore it.
- Per the parent rules at `/Users/pp/Desktop/file/code/AGENTS.md`: UI changes must be verified in a real browser against the running app, with the URL, interactions, and result reported.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage labels are used as-is: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
