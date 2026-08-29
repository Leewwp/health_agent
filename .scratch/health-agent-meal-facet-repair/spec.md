# 餐食标签与简报补充回路修复

Status: resolved (2026-08-29；代码、数据、测试与浏览器验收完成)

## Problem Statement

上一轮“餐食标签体系改造”和“简报补充回路”已经在工作树中留下了部分实现，但当前成果不能交付：后端全量测试为 817 项中 19 个失败、1 个错误，真实 MySQL 读路径测试为 16 项中 6 个错误。失败和错误集中在本轮新增的 `foodType`、菜系意图解析、计划偏好过滤、fixture 标签和数据库浏览查询，说明代码、数据库、Agent 提示词、测试基准与验收文档没有形成同一份契约。

当前用户可观察的问题包括：

- 新数据库或重新导入审核种子时，`food_type` 可能缺失或为空，无法可靠筛选餐食。
- 带餐食类型的旧浏览查询在 MyBatis 运行时引用不存在的 `foodType` 参数。
- 简报对菜系多值、未支持“中餐”、可补充项和按日回退的行为与已确认决策不一致。
- Agent 提示词仍声明旧槽位集合，聊天摘要仍读取旧单值字段；现有前端源代码测试无法证明新字段真的呈现。
- fixture 推荐被过度套用正式审核库的多维筛选，改变了既有推荐合同。
- 验收文档和旧规格声称全绿或已完成，但当前可复现测试是红色；工作树还包含与本功能无关且陈旧的未跟踪简历文件。

## Solution

完成一次以契约一致性和可复现验收为门槛的修复。以最新确认的领域决策为唯一产品合同：`cuisine` 和 `foodType` 都是数组型、多选基础筛选维度；同维度内 OR、跨维度 AND；显式但不在词汇表中的菜系原值必须诚实保留并登记 `unsupportedPreferences`，但永不进入筛选或生成约束。所有新旧入口、审核数据库、fixture、Agent、会话持久化、前端摘要和计划说明都消费同一字段定义。已应用的 Flyway 迁移保持不可变，通过纠正迁移和更新种子生成链修复新旧数据库；最终以全量 Maven、MySQL 门控、前端行为测试和真实浏览器证据全部通过为完成条件。

## User Stories

1. As a meal-plan user, I want to provide several cuisines, so that the plan can honor any of my selected cuisines without guessing.
2. As a meal-plan user, I want to provide several food types, so that vegetarian, light, noodle, or other categories can be combined predictably.
3. As a meal-plan user, I want cuisine and food type to remain separate, so that a food shape is not mislabeled as a regional cuisine.
4. As a meal-plan user, I want an unsupported explicit value such as “中餐” to be retained in the brief and called out as unsupported, so that my input is not silently lost.
5. As a meal-plan user, I want unsupported values excluded from filtering, so that the system does not pretend to have candidates it cannot prove.
6. As a meal-plan user, I want adding a new preference to preserve previously recorded unsupported preferences, so that the brief remains truthful across turns.
7. As a meal-plan user, I want “换成/改为” to replace list preferences, so that I can revise a brief without stale values remaining.
8. As a meal-plan user, I want a multi-cuisine conflict to be handled according to the confirmed multi-select contract, so that the assistant does not issue obsolete single-select messages.
9. As a meal-plan user, I want the brief summary to show all cuisines, food types, tastes, convenience, and unsupported values, so that I can inspect exactly what will be used.
10. As a meal-plan user, I want supplementable items to disappear when filled and include food type when empty, so that the next action is clear.
11. As a meal-plan user, I want a brief restored after session restart to contain the same arrays and unsupported values, so that progress is not lost.
12. As a meal-plan user, I want a plan generated with food preferences to apply same-day fallback transparently when candidates are insufficient, so that constraints are relaxed only where explained.
13. As a meal-plan user, I want unsupported preferences and candidate-scarcity fallbacks shown in separate sections, so that I can distinguish capability limits from data scarcity.
14. As a recommendation user, I want ordinary fixture recommendations to preserve their established meal-time behavior, so that offline demos do not unexpectedly behave like the production database.
15. As a recommendation user, I want reviewed-database recommendations to consume the same cuisine and food-type vocabulary as the browse API, so that a visible filter is meaningful to the agent.
16. As a legacy diet-chain user, I want old requests without `foodType` to continue working, so that adding the new field does not break `/api/v1/diet/**`.
17. As a legacy session user, I want old single-value cuisine JSON to be read as a one-element array, so that existing sessions remain usable.
18. As an Agent, I want slot options and prompt examples to include `foodType`, so that model output does not drift from the server contract.
19. As an Agent, I want cuisine, food type, taste, and convenience to be described with their correct meanings, so that clarification questions are not misleading.
20. As a resource browser user, I want `mealTime=早餐/午餐/晚餐` to match a resource tagged `三餐`, so that broad meal-time tags behave consistently in every query path.
21. As a resource browser user, I want browse counts and rows to use identical filters, so that pagination totals cannot disagree with displayed results.
22. As a deployer, I want a fresh database followed by seed import to produce non-null, correctly split meal facets, so that Docker and CI do not differ from a repaired local database.
23. As a deployer, I want rerunning the resource ETL to preserve the same facet contract, so that generated seed files do not reintroduce the defect.
24. As a maintainer, I want applied migrations to remain checksum-safe, so that existing environments can upgrade without rewriting history.
25. As a maintainer, I want integration-test fixtures to include every new non-null column, so that real MySQL tests exercise the same schema as production.
26. As a frontend user, I want chat brief cards to render array fields rather than legacy aliases, so that the visible summary matches the API payload.
27. As a frontend user, I want plan cards to wrap long names without horizontal overflow, so that desktop and mobile layouts remain usable.
28. As a reviewer, I want browser evidence to record the actual running URL, build, viewport, input, response fields, and result, so that a stale document cannot be mistaken for verification.
29. As a reviewer, I want the specification, ADR, context glossary, tracker status, and acceptance document to agree on the same field shape and test status, so that completion claims are auditable.
30. As a maintainer, I want unrelated untracked resume documents kept out of this change, so that the repair remains scoped and accidental files are not committed.

## Implementation Decisions

- The canonical meal brief shape is `{weekStart, mealTimes, healthGoal, cuisines: string[], foodTypes: string[], tastePreferences: string[], convenience: string|null, unsupportedPreferences: string[]}`. `cuisines` and `foodTypes` are multi-select arrays; `convenience` remains single-select. The legacy `cuisine` accessor is compatibility-only and must not define the API contract.
- An unsupported value stored in `cuisines` does not count as a satisfied supported-cuisine preference: the brief may still be generated because cuisine is optional, but `supplementable` continues to offer a cuisine supplement until at least one supported cuisine is present. The summary marks the unsupported value separately, and generation never filters on it.
- A deterministic cuisine parser runs before unrelated-input gating. It recognizes only explicit cuisine forms and the closed superclass words. Supported aliases are normalized from the shared vocabulary. An explicit unknown value is retained in `cuisines` exactly as entered (normalized for whitespace), and also recorded once as `cuisine:<value>` in `unsupportedPreferences`; it is excluded by every filter and candidate selector.
- Update the superseded brief specification and ADR/context wording so there is no remaining single-value cuisine statement or contradictory “no migrations” statement. The accepted current decision is multi-select cuisine plus food type, and the corrective migrations are explicitly in scope for this repair.
- Keep already-applied migrations immutable. Add a new corrective Flyway migration for deployed databases that keeps `food_type` NOT NULL with no default, repairs JSON `null`/empty facet rows, migrates every legacy type value including `烧烤`, and normalizes the slot dictionary. Every insert path must explicitly provide the column; the seeder validates the row-level invariant and fails loudly on mismatch. The migration must be idempotent in effect and must not fabricate user-facing cuisine facts beyond the documented demo classification.
- Fresh-schema seed import and legacy-schema migration must converge to the same facet invariant and deterministic classification for equivalent source rows. The acceptance test compares the resulting facet projection, not only non-nullness, so a repaired local database cannot mask a different fresh-database outcome.
- Update the reviewed-resource ETL generator and the generated reviewed seed so every meal INSERT includes `food_type` and uses the same split rules as the migration. Validate the generated SQL in a fresh database after all migrations and again on a database containing legacy rows. `ReviewedResourceSeeder` must fail loudly on a seed/schema mismatch instead of silently accepting incomplete rows.
- Update MySQL integration-test insert helpers and any local media/demo seed path to provide `food_type` explicitly. Add assertions that approved public meals never expose JSON `null` for either facet.
- Fix the browse/count MyBatis contract by consolidating the duplicate methods into one parameter-complete query family with an explicit `foodType` parameter. Browse and count must use the same predicate order and include the `三餐` compatibility expression. Remove dynamic references to parameters not declared by the Java mapper.
- Preserve the health resource read boundary: only the approved meal DB adapter may depend on MealMapper. The old diet chain may continue to use MealMapper through its existing service, but must receive the new field in request/row conversion without requiring callers to provide it.
- Synchronize intent and clarification prompts with the slot dictionary: include `foodType` in examples and allowed slots, classify food types separately from cuisine, and keep unknown raw model slots from bypassing deterministic normalization. Prompt wording and seed option labels must use the same Chinese vocabulary.
- Keep fixture semantics explicit. Ordinary fixture recommendation continues to enforce the established meal-time contract and does not become a production-style multi-dimensional database filter unless the external behavior specification and its tests are deliberately changed together. Plan preference filtering remains available at the plan-candidate seam with deterministic labels.
- Repair plan preference filtering so same-field values are OR, different fields are AND, taste and nutrition values map to their correct candidate tag groups, and an empty or incomplete preference pool causes whole-day fallback while retaining meal-time, uniqueness, calorie, and diversity constraints. Record the fallback date and unmet supported preference keys in `generationNotes`.
- Make unsupported preferences and candidate-scarcity notes distinct in generation metadata, version snapshots, plan detail APIs, and the plan page. Old metadata must deserialize to non-null empty collections.
- Update chat summary rendering to read `cuisines` and `foodTypes` arrays first, with a compatibility fallback for old `cuisine` only when necessary. Render the structured brief, not parsed speech text. Update frontend tests to assert rendered behavior or the actual summary function output; do not accept a source-regex test as proof of field support.
- Keep the existing plan-card layout fix: stable grid dimensions, wrapped long names, no nested card redesign, and no horizontal overflow at 1440x900 and 390x844.
- Mark the previous brief-supplement specification as not resolved until all gates pass. Correct its completion notes, acceptance counts, and port/build references. Do not delete or modify the three untracked resume files as part of this repair; explicitly exclude them from the change or ask the owner to handle them separately.

## Testing Decisions

- Use the existing highest seams first: `POST /api/v1/health/chat` for end-to-end routing, brief updates, structured summaries, lifecycle, and preflight; the reviewed meal reader/controller seam for browse/count and `三餐`; and the weekly plan composer/picker seam for preference filtering and generation notes. Add lower-level tests only for deterministic parser, mapper binding, migration, and serialization boundaries.
- Add or update brief-service tests for: supported and unsupported cuisine extraction, preservation across updates and restart, multi-cuisine and multi-food-type merge/replacement, unknown raw-slot rejection, summary and supplementable output, and health-goal versus taste classification.
- Add parser tests for explicit labels, suffix forms, superclass words, Chinese punctuation and conjunctions, negated spans, alias ordering, and values outside the allowed forms.
- Add contract tests for JSON round trips of new and legacy sessions, including null/empty arrays, `_meta.briefLifecycle`, unsupported preferences, and legacy single-value cuisine import.
- Add mapper tests that execute the actual MyBatis statements against MySQL, covering both browse and count with and without food type, all meal-time values including `三餐`, name search, favorites, and pagination consistency. Mockito-only verification is insufficient for dynamic SQL binding.
- Add migration/seed tests against a disposable or dedicated MySQL schema: fresh migration then seed, legacy data then corrective migration, ETL regeneration, rerun/idempotence, non-null `food_type`, complete type split including `烧烤`, and approved/public filtering.
- Add reviewed-reader and controller integration tests that insert rows with every new non-null field and verify cuisine/food type filters, counts, and returned tags. Keep the architecture boundary test green.
- Add fixture tests that assert ordinary recommendation behavior remains unchanged, while plan candidate tests assert deterministic cuisine/food-type/taste/nutrition/convenience filtering and whole-day fallback semantics.
- Update health evaluation fixtures or implementation only after deciding whether the changed labels intentionally alter the benchmark; the expected numerator/denominator must be documented and all deterministic metrics must pass.
- The health-eval-v2 fixture baseline is not intentionally changed by this repair; restore the pre-change deterministic numerator of 15 and document any fixture-label correction required to do so.
- Boundary examples are contractual: “我喜欢中餐、川菜” yields `cuisines=["川菜","中餐"]` and one `cuisine:中餐` unsupported key; “菜系换成中餐” rebuilds the cuisine array and does not retain an old supported cuisine; “不想吃素” produces no positive `foodType=素食`; repeated/whitespace values deduplicate; multiple convenience values retain the existing value and explain that convenience is single-select unless “换成/改为” is present. A JSON literal `null` deserializes to an empty list.
- Replace frontend source-regex assertions with behavior-level tests for summaries containing multiple cuisines, food types, unsupported values, and empty states. Retain chip, button, generation-note, and overflow tests.
- Run the verification matrix in this order: compile; focused Java tests; full `mvn test`; MySQL-gated `mvn test -Ditest.mysql=true`; all frontend Node tests; then real Chromium against the newly built process.
- Browser acceptance must cover fresh-session meal brief completion, food type and multi-cuisine supplements, unsupported “中餐”, restart persistence, recommendation preflight, plan generation notes, `三餐` browse matching, desktop 1440x900, and mobile 390x844. Record actual URL, backend/frontend ports, build timestamp or commit, request inputs, key response fields, screenshots, and horizontal-overflow measurements.
- Completion requires compile success, zero non-gated failures/errors in the full suite, green MySQL gate, green frontend behavior tests, successful fresh-schema seed verification, and browser evidence matching the current worktree. Any skipped environment gate must be named explicitly.

Execution order is fixed: (1) Mapper binding and explicit `food_type` writes; (2) corrective migration, ETL and fresh/legacy convergence checks; (3) canonical brief/parser semantics and prompt/session/frontend contracts; (4) fixture and plan fallback behavior plus evaluation baseline; (5) full tests, MySQL gate, fresh-schema verification, and real-browser evidence.

## Out of Scope

- No new recommendation model, retrieval system, vector schema, health domain, or second chat state machine.
- No changes to risk rules, weekly-plan lifecycle semantics, feedback attribution, security boundaries, MCP tool scope, or the old diet API behavior beyond adding backward-compatible field transport.
- No long-term preference memory or cross-session personalization beyond repairing existing brief/session persistence.
- No expansion of the real reviewed meal dataset to make “中餐” supported; it remains an explicitly unsupported value unless an independently approved data project changes that decision.
- No unrelated resume/document rewriting or deletion. Those files are reported as scope contamination and handled separately.
- No editing of applied Flyway migration history; any deployed-schema repair uses a new migration.

## Further Notes

- The two audits agree that the previous completion claim was premature. The local repaired database and running instance can make the feature appear healthy while a fresh schema, CI, or a different MySQL query path fails; fresh-schema testing is therefore a release blocker, not an optional environment check.
- `INSERT IGNORE` must not be treated as successful validation. Seed import must expose schema mismatches and verify row-level facet invariants after import.
- The current repository has both `CONTEXT.md`/ADR language describing multi-select facets and an older brief spec describing single-value cuisine. This repair deliberately resolves that documentation conflict in favor of the latest confirmed Q17-Q21 decision and requires all tests and UI contracts to follow it.
- Existing browser notes mention multiple ports and stale test totals. They are historical evidence only until regenerated from the same build that passes the verification matrix.
- The three untracked resume files contain stale project metrics (726/728 tests, V1..V20 or 23 migrations). They should not be included in the implementation branch unless separately requested and updated.
