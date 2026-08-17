# 健康聊天最小语义与跨品类路由修复规格

## Problem Statement

项目已经具备餐食、健身、作息三个领域的健康聊天链路，以及审核资源、会话状态和类型化展示块。但当前实现仍存在一组会直接破坏基本功能的问题：意图模型缺少明确的“其他对话”出口，低置信度或规则降级可能把无关问题送入餐食澄清；健康意图结果中的槽位没有稳定按当前领域隔离，餐食、健身和作息的历史状态可能互相污染；动作词汇归一主要服务于数据读取，不能完整接纳用户常见说法；调整请求、简短澄清回复和显式切换领域的优先级也不稳定。

这些问题的用户表现是：讨论餐食时出现健身动作，讨论作息时追问餐次，用户回答“胸肌”“大腿”“臀部”后仍无法完成健身推荐，或者“推荐电影”“你是 AI 吗”被当成餐食需求。作息事实查询还可能因为沿用个性化作息澄清规则而追问不必要的起床时间。审核餐食查询和 Compose 配置另有两个边界缺口，会影响结果真实性和项目启动可用性。

本规格只针对基本可用的对话演示，不追求生产级自然语言理解。核心验收标准是：用户输入能够被归入正确领域和任务；缺少必要信息时只追问该领域的问题；完成推荐后只返回对应类型的审核资源；无法可靠理解时明确澄清，不猜测、不跨域、不编造。

## Solution

在现有 `HealthOrchestratorService` 状态机上补齐一层轻量的确定性修正与输入归一：

```text
模型/规则识别
  -> 意图修正（风险、当前领域、澄清继承、调整前置条件）
  -> 用户输入归一
  -> 当前领域槽位投影
  -> 领域澄清
  -> 对应领域检索
  -> 类型化响应
  -> 会话持久化
```

继续使用正交的 `domain/task/riskFlags/phase` 模型。餐食对应 `MEAL`，健身对应 `EXERCISE`，作息对应 `ROUTINE`；增加 `OTHER + CHAT` 作为无关对话出口。保留现有的 Agent、Provider、Reader、类型化资源和会话机制，不建立第二套意图状态机。

健身输入通过共享的纯 Java 归一器接纳常见别名，例如“胸肌/胸部/胸大肌”归一为“胸”，“大腿/小腿/腿部”归一为“腿”，“臀部/臀肌/臀大肌”归一为“臀”，“新手/初学者”归一为“入门”，“减肥/瘦身”归一为“减脂”，“自重/无器械/不用器械”归一为“徒手”。归一器同时处理多值输入和否定表达；未知、冲突或仅有否定而没有可执行正向条件时进入澄清，不擅自猜测。

餐食和健身复用相同的“识别、修正、澄清、检索、响应”流程，但使用各自的槽位集合和资源模块。作息本批次只保留结构化事实查询：如“晚上几点前停止喝咖啡？”直接返回咖啡因事实，不要求用户先提供起床或入睡时间；只有明确要求个性化作息安排时才进入有限澄清或现有计划入口。

## User Stories

1. As a 健康聊天用户, I want “中午吃什么” to enter meal recommendation, so that I receive food rather than exercise content.
2. As a 健康聊天用户, I want “推荐清淡的早餐” to use meal slots only, so that exercise history cannot affect the result.
3. As a 健康聊天用户, I want “帮我推荐一下” to ask for the meal or exercise context that is actually missing, so that the assistant does not guess a domain.
4. As a 健康聊天用户, I want “换一批” to adjust the previous meal or exercise recommendation, so that it excludes only resources from that same domain.
5. As a 健康聊天用户, I want “清淡点” after a meal recommendation to remain in the meal domain, so that the assistant does not reinterpret it as a training request.
6. As a 健康聊天用户, I want “帮我推荐一份适合新手的轻量训练” to enter exercise recommendation, so that the next question concerns a body part or training goal.
7. As a 健康聊天用户, I want “胸肌” to be accepted as an exercise body-part answer, so that I do not need to know the internal canonical vocabulary.
8. As a 健康聊天用户, I want “胸部” and “胸大肌” to behave the same as “胸肌”, so that common synonyms do not produce different results.
9. As a 健康聊天用户, I want “大腿”“小腿”“腿部” to map to the supported leg category, so that lower-limb requests can retrieve reviewed exercises.
10. As a 健康聊天用户, I want “臀部”“臀肌”“臀大肌” to map to the supported hip/glute category, so that glute requests can retrieve reviewed exercises.
11. As a 健康聊天用户, I want “新手”“初学者” and “轻量” to be understood as beginner-level constraints, so that beginner exercise results are reachable.
12. As a 健康聊天用户, I want “减肥”“瘦身” to map to the exercise fat-loss goal, so that a common goal does not become an unknown slot.
13. As a 健康聊天用户, I want “自重”“无器械”“不用器械” to mean bodyweight training, so that the negation is not incorrectly stored as equipment.
14. As a 健康聊天用户, I want “不要练胸” to avoid adding chest as a positive constraint, so that the result does not contradict my explicit exclusion.
15. As a 健康聊天用户, I want a short answer such as “大腿” to inherit only the active exercise clarification context, so that old meal slots are ignored.
16. As a 健康聊天用户, I want an explicit switch from meal to exercise to take precedence over prior meal context, so that a new request starts in the domain I named.
17. As a 健康聊天用户, I want an explicit switch from exercise to meal to take precedence over prior exercise context, so that exercise slots do not leak into meal retrieval.
18. As a 健康聊天用户, I want a completed exercise request to return only `EXERCISE` display blocks, so that the UI never shows meal cards for a training question.
19. As a 健康聊天用户, I want a completed meal request to return only `MEAL` display blocks, so that the UI never shows exercise cards for a food question.
20. As a 健康聊天用户, I want routine fact questions about sleep, caffeine, naps and training time to return sourced routine facts directly, so that simple facts do not trigger an unnecessary personal schedule interview.
21. As a 健康聊天用户, I want “晚上几点前停止喝咖啡？” to return a caffeine fact, so that I am not asked when I wake up.
22. As a 健康聊天用户, I want “推荐电影”“你是 AI 吗” and similar unrelated requests to receive a bounded general response, so that they do not enter meal or exercise retrieval.
23. As a 健康聊天用户, I want ambiguous or unsupported vocabulary to produce a clarification request, so that the assistant does not invent a resource category.
24. As a 健康聊天用户, I want a risk-related request to keep risk precedence over ordinary recommendation routing, so that unsafe content is not returned as a normal plan or recommendation.
25. As a 健康聊天用户, I want “一周健身计划” to use the existing weekly-plan flow, so that this repair does not create a second planning algorithm in chat.
26. As a developer, I want the same high-level orchestration flow reused for meal and exercise, so that the two domains remain behaviorally consistent without sharing concrete slots.
27. As a developer, I want normalization to run for both model output and deterministic fallback, so that live and degraded paths accept the same user vocabulary.
28. As a developer, I want domain projection to be non-destructive to persisted session state, so that old sessions remain compatible while active retrieval stays isolated.
29. As a developer, I want resource retrieval to remain behind the existing reviewed Provider/Reader boundary, so that intent fixes cannot bypass approval and source checks.
30. As a developer, I want approved meal filtering to happen before SQL limiting, so that pending rows cannot consume the result window and cause a false empty result.
31. As a developer, I want Compose configuration to pass static validation, so that the documented local deployment can start.
32. As a developer, I want regression tests to assert response domain, task, phase and typed resources rather than exact model wording, so that harmless wording changes do not create false failures.
33. As a developer, I want a deterministic fixture path and a live-model smoke path to share the same external contract, so that fallback behavior remains testable without pretending to be model understanding.
34. As an interviewer, I want a new anonymous session to demonstrate meal clarification, exercise clarification, routine facts and unrelated chat, so that the basic capabilities are visible and repeatable.

## Implementation Decisions

- This is a P0/P1 stabilization batch focused on basic functionality. It refines the intent and routing portion of the existing health-chat stabilization proposal corresponding to GitHub issue #78; it does not reopen completed resource, plan, feedback, security or RAG batches.
- Reuse the existing health state machine and its highest test seam, the health chat API backed by the health orchestrator. Do not introduce a parallel conversation service.
- Extend the domain model with `OTHER` and route it as `CHAT` with no domain retrieval and no health resource display blocks. Existing `MEAL`, `EXERCISE`, `ROUTINE` and `COMPOSITE` semantics remain intact.
- Add a lightweight intent revision service modeled on the existing legacy revision behavior. Revision order is: risk precedence, explicit current-turn domain evidence, clarification-context inheritance, `ADJUST` previous same-domain resource requirement, correct `PLAN` domain, low-confidence clarification, then normal routing.
- An explicit current-turn domain signal overrides stale session context. A short clarification reply may inherit the active domain, task and phase only; it may not inherit unrelated slots.
- Keep `HealthSessionState` persistence backward-compatible. Store historical slots as currently supported, but create a domain-specific projected view immediately before clarification and retrieval.
- The projected slot sets are: meal slots for `MEAL`, `bodyParts/trainingGoal/difficulty/equipment` for `EXERCISE`, routine fields for `ROUTINE`, and no slots for `OTHER`. Retrieval and clarification consume only the projected view.
- Add a pure, I/O-free `HealthInputNormalizer` shared by model-result parsing and fallback extraction. It normalizes case/whitespace where applicable, aliases, multiple values and negation before legal-value validation.
- Canonical aliases are intentionally small and tied to existing dictionary values. Minimum exercise aliases are: chest group to “胸”, back group to “背”, thigh/calf/leg group to “腿”, shoulder to “肩”, arm to “手臂”, abdomen/core to “核心”, glute group to “臀”; beginner aliases to “入门”; fat-loss aliases to “减脂”; bodyweight aliases to “徒手”.
- Negation is a constraint, not a positive slot value. The minimum implementation must ensure “不用器械” does not become `equipment=器械`, and “不要练胸” does not become `bodyParts=胸`. If the current data contract cannot represent exclusion safely, retain the exclusion in the active query context and clarify rather than silently treating it as inclusion.
- Do not expand `diet_slot_option` or exercise database categories for synonyms. Normalization maps user language to existing canonical values. Unknown values are not forced into the nearest category.
- Use the same normalization and domain projection in LLM parsing and rule fallback. A model returning an accepted synonym must receive the same canonical result as a user sentence handled by fallback.
- For `ADJUST`, require previous resources of the selected domain. “换一批” without same-domain history becomes clarification or a bounded request for a fresh recommendation, but must not exclude resources from another domain.
- For `PLAN`, preserve the existing weekly-plan page/flow. Chat does not create a draft and no new conversational planning algorithm is added.
- Keep routine intentionally simple. Direct fact questions use keyword-to-fact lookup and return only `ROUTINE` resources; personalized schedule requests may use the existing clarification or plan entry, without new RAG or routine adjustment semantics.
- Keep meal retrieval strict and truthful. Add/retain `review_status='APPROVED'`, public-source and owner constraints in the SQL predicate before `ORDER BY/LIMIT`. Do not relax meal constraints or add data merely to increase result count.
- Keep response generation constrained by retrieved candidate IDs. The response Agent may explain selected resources but may not create candidates or change their resource type.
- Fix the invalid Compose `depends_on` shape using one valid YAML form while preserving the current optional Qdrant startup/degradation behavior. This is a deployment correctness fix, not a vector-store redesign.
- Do not introduce React, Vue, Vite, Node/npm, Chinese segmentation libraries, a general NLP framework, another model service, a database schema migration, Redis, or a new retrieval system. The current vanilla ES Modules frontend does not cause the cross-domain bug.
- Keep all new code comments and prompts in Chinese, consistent with repository conventions.

### Existing issue alignment

- GitHub #78 remains the broad interview-demo stabilization context. This local batch extracts the currently agreed minimum conversation/routing work from it and narrows the acceptance target to basic correct-domain behavior.
- Completed #43, #55–#59 and #60–#70 remain prerequisites and must not be reimplemented. Their session, typed-resource, Provider/Reader, vocabulary-read and resource-boundary contracts are reused.
- Completed #72–#77 remain unaffected: weekly-plan entry, feedback trace attribution, health evaluation and RAG evaluation are regression gates, not new work here.
- The old meal-only intent concepts (`MEAL_RECOMMENDATION`, `CLARIFY_NEEDED`, `MEAL_ADJUST`, `MEAL_PLAN`, `HEALTH_RISK`, `OTHER`) are behavioral references only. They must be mapped onto the current orthogonal model rather than reintroduced as a second enum.

## Testing Decisions

- The primary seam is `POST /api/v1/health/chat` through the existing health orchestrator. Tests drive complete turns and assert externally visible `responseType`, `domain`, `task`, `phase`, `missingSlots`, resource type and resource IDs. Exact natural-language wording is asserted only for stable safety or routing templates.
- Extend the existing orchestrator scenario tests for: generic clarification, meal follow-up, exercise follow-up, explicit domain switches, `ADJUST`, `OTHER`, routine facts, empty results, risk precedence and weekly-plan handoff.
- Add focused contract tests for the normalizer covering all minimum aliases, multi-value input, whitespace, unknown values, positive/negative equipment and body-part expressions, and idempotent canonical input.
- Add intent tests for model output normalization, fallback normalization, explicit-domain precedence, active clarification inheritance, low-confidence behavior and `OTHER` routing.
- Add module-level tests only where response-level assertions cannot prove the contract: meal query approval ordering, routine fact category matching and exercise canonical-tag matching.
- Extend the existing real-MySQL reviewed-reader/retriever gate to prove an unapproved row cannot consume the limited search window and an approved public breakfast remains reachable. No schema change is expected.
- Keep the architecture test that restricts direct resource Mapper dependencies. The new normalizer and revision service must remain free of database I/O.
- Validate `docker compose config --quiet` as a required static deployment check.
- Run `mvn -DskipTests compile`, focused tests, normal `mvn test`, and the MySQL gate when available. Qdrant gates are not required unless implementation unexpectedly changes vector-store code.
- Run a live DashScope smoke test only as a manual/acceptance gate. Assert structured parsing and domain/resource constraints, not model wording. Never include real keys in fixtures, logs or reports.
- Run the real browser acceptance script against the running same-origin frontend: meal request, exercise request with “胸肌/大腿/臀部”, routine caffeine fact, unrelated “推荐电影”, a meal→exercise switch and an exercise→meal switch. Record URL and observed result.
- A test is good when it proves the user cannot receive a cross-domain resource or irrelevant clarification from a new anonymous session, and when a failed or ambiguous understanding becomes a clear clarification instead of an invented recommendation.

## Out of Scope

- React or any frontend framework migration, frontend build tooling, route rewrite or visual redesign.
- Chinese word segmentation, general NLP, intent training, fine-tuning, a second LLM/provider, or production-grade semantic parsing.
- Full synonym coverage for every possible Chinese expression. Only the minimum canonical aliases and safe clarification behavior are required.
- User accounts, long-term profiles beyond existing functionality, Redis/distributed locks, queues, multi-instance deployment or new persistence schema.
- New exercise dosage fields such as duration, frequency, sets, repetitions, weight or heart rate. “训练一小时” may remain conversational context unless existing contracts already support it.
- Complex weekly planning, automatic multi-domain plan composition in chat, resource replacement, routine ADJUST, routine RAG or a new sleep-planning algorithm.
- Medical diagnosis or treatment advice. Existing risk rules and fixed blocking/advisory behavior remain the authority.
- Expanding the meal/exercise dataset, relaxing hard constraints, inventing candidates, or changing embedding/vector retrieval behavior.
- Rewriting the legacy diet chain. Existing `/api/v1/diet/**` compatibility behavior is a regression gate only.

## Further Notes

- “大腿”“胸肌（胸）”“臀部（臀）” are explicitly supported through canonical normalization. Other accepted inputs are the existing legal meal/exercise slot values and the minimum aliases listed above; unsupported or conflicting language must result in clarification rather than a guessed category.
- The requirement “不出现矛盾冲突或无关内容” is enforced at two boundaries: projected slots before clarification/retrieval, and typed display-block validation before response persistence. Prompt wording alone is not sufficient.
- The estimated implementation effort is approximately 3–4.5 person-days: 1 day for intent/normalization, 1 day for orchestration isolation, 0.5–1 day for routine/query/Compose boundaries, and 0.5–1.5 days for regression and browser acceptance. This estimate assumes no schema migration and preserves the current resource architecture.
- Completion means the basic flows work with fixture and live-model smoke coverage, not that natural-language understanding is universally correct. The deterministic fallback must be honest about low confidence and must prefer clarification over cross-domain guessing.
- Triage: ready-for-agent.
