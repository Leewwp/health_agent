# 健康 Agent 产品体验与资源能力增强规格

Status: superseded for plan lifecycle and current-assignment semantics by `.scratch/health-agent-unified-plan-editor/spec.md` and ADR-0014. Its recommendation, catalog, chat-layout and feedback requirements remain historical context only; do not implement its independent MEAL/EXERCISE current-assignment model.

Status: ready-for-agent
Type: spec
Date: 2026-08-20

## Problem Statement

当前健康 Agent 的健身动作推荐实际主要消费约 30 条 `APPROVED/plan_ready` 动作，虽然本地已有 1,324 条完整动作目录，但大量动作停留在展示种子状态，无法参与推荐和周计划。用户因此可能得不到满足部位、器材或目标的动作。

聊天页面的固定区域和消息滚动关系不理想；推荐结果没有明确的“换一批”入口；反馈按钮的用户含义不清；计划中的“激活”没有可感知的产品作用；计划项目详情不完整；计划缺少删除草稿和取消当前安排的能力。

意图识别还会把“今晚想吃得清淡一点”错误拆成只识别清淡、继续追问餐次。训练简报提示过于冗长，并且当前只支持一个训练时间窗口。

## Solution

将完整动作目录通过可复现的脚本或离线模型批量补全，除真正损坏且无法补全的数据外，开放浏览、单次推荐和计划生成。在线 Agent 继续只能引用候选白名单，训练剂量和风险由确定性规则控制。

将用户反馈收敛为“收藏”和“减少推荐”。收藏只表示个人资源管理，不隐式提高排序；减少推荐默认作用于当前资源，只有用户明确表达类别意图时才形成类别级偏好。

新增“换一批”替代推荐：保留当前需求和上下文，累计排除当前会话已展示的同类资源，不重复返回旧结果；候选耗尽时不静默放宽条件。

将计划生命周期与当前安排关系分离。餐食和训练分别可以有一份当前安排，聊天读取当天安排作为软上下文；用户当轮明确表达优先，临时偏离不修改计划或长期偏好。用户可以删除草稿、取消当前安排，历史版本默认保留。

聊天页面在桌面端使用固定工作区和独立内容滚动，在移动端只保留消息区域滚动。计划周表直接展示重要参数，点击项目打开详情抽屉；餐食不生成制作方法。

意图处理采用“规则提取候选 + 歧义检测 + Agent 仲裁 + Java 合法值校验”。无歧义的多槽位请求不调用模型；出现否定、转折、时间从句、冲突或指代时才调用模型。训练多时段和餐食/训练多项目单元进入后续里程碑。

## MVP Boundary

本轮以面试演示的完整主流程为交付目标，不以一次性实现所有增强能力为目标：

- **P0 必须完成**：聊天固定布局、餐食/动作单次推荐、“换一批”不重复、收藏与减少推荐、计划周表参数、计划资源详情抽屉、草稿删除、当前安排取消、“今晚清淡”规则修复，以及固定浏览器演示脚本。
- **P1 尽量完成**：餐食与训练独立当前安排、当天软上下文、简单歧义规则与 Agent 降级、完整动作目录浏览。
- **P2 可延后**：1324 条动作全部取得周计划资格、离线模型批量补全、类别级偏好、复杂多从句仲裁、全面覆盖率评分和历史反馈迁移。

P0 必须保持可演示，即使模型、Qdrant 或大目录增强不可用也要走确定性降级。任何 P1/P2 失败不得阻塞 P0 发布。动作目录浏览扩容与周计划资格扩容必须使用独立开关和独立验收。

## Runtime Contracts

### Current Assignment

计划生命周期和当前安排是两个概念。`weekly_plan.status` 继续表示计划生命周期；当前安排独立建模为 `(userId, planScope) -> planId/versionNo`，推荐采用独立关系表，而不是复用用户级 `ACTIVE` 唯一约束。餐食和训练各自最多一条引用，可以并存。

- 只有已确认/ACTIVE 版本可以设为当前安排；操作必须幂等，并校验用户与计划归属。
- 取消当前安排只删除对应引用，不修改计划状态、不删除版本；重复取消的响应需在 API 中固定。
- 计划被删除、归档或版本替换时，引用必须在同一事务中清理或指向明确的新版本，禁止悬空引用。
- 聊天按计划时区计算本地日期项目；当前安排低于本轮明确请求、临时排除和风险规则。
- 设为当前、取消、删除草稿之间必须有并发保护；越权、状态不允许和版本冲突返回稳定错误码。

### Alternative Recommendation

“换一批”复用健康聊天幂等请求通道，动作必须携带 `resourceType`、`baseTraceId`、`addedExclusions` 和 `allowRepeat`。服务端按 `(sessionId, resourceType)` 保存有界排除集合，默认最多保留 50 个资源 ID。

- 保留已确认槽位、当前任务和请求级临时约束；新增排除只作用于当前请求及后续同类替代推荐。
- 新会话、明确重置或新任务上下文时清除对应集合；跨领域聊天不清除另一品类集合。
- 候选耗尽返回稳定的 `CANDIDATES_EXHAUSTED` 领域结果，并提供“放宽条件”和“重复已展示结果”两个显式 action；服务端不得静默放宽。
- `allowRepeat=false` 为默认值；只有用户明确选择重复结果才允许旧资源。按钮点击和自然语言“换一批”走同一语义。

### Temporary Constraints

否定、转折和时间从句不能只压缩成普通槽位。仲裁结果至少区分当前槽位、历史事实和请求级临时约束：`excludedResourceRefs`、`excludedCategories`、`negatedSlots`、`effectiveDate`、`scope=REQUEST_ONLY`。历史事实只用于解释和仲裁，不写入长期偏好或当前安排。

### Performance Budget

演示环境目标：规则推荐 P95 小于 1 秒；需模型的澄清/仲裁和计划生成显示等待状态，应用截止时间控制在 15 秒内；分页和详情接口不得一次返回完整动作目录；详情抽屉先显示计划快照，再异步加载资源详情。超时、重复点击、取消和重试必须有稳定前端状态。

## User Stories

1. As an adult user, I want to browse the complete exercise catalog, so that I can discover movements beyond the small initial subset.
2. As an adult user, I want a valid exercise from the catalog to be recommended even when it is not in the original 30-item subset, so that my body-part and equipment requirements have a better chance of being satisfied.
3. As a user, I want exercise data to show whether attributes were automatically completed, so that the demonstration does not imply manual professional review.
4. As a user, I want the system to exclude only irreparably damaged exercise records, so that incomplete metadata does not unnecessarily shrink the recommendation pool.
5. As a user, I want exercise recommendations to respect my requested body part, equipment, training goal and difficulty when those fields are available, so that a larger catalog does not reduce relevance.
6. As a user, I want a deterministic fallback when an automatically completed field is unknown, so that the system remains usable without inventing unsafe constraints.
7. As a user, I want to see a data import report, so that the project can demonstrate catalog coverage and completion quality.
8. As a user, I want to save a meal or exercise as a favorite, so that I can find it again as a personal resource.
9. As a user, I want “减少推荐” to affect the selected resource, so that one unwanted item is less likely to return.
10. As a user, I want to say “少推荐这类徒手动作” or an equivalent category request, so that the system can reduce a clearly identified class of resources.
11. As a user, I want favorite state to remain independent from recommendation tendency, so that saving an item does not silently change ranking.
12. As a user, I want recommendation feedback to have visible state and a reversible path where applicable, so that I understand what the system recorded.
13. As a user, I want a “换一批” command below recommendation results, so that I do not need to phrase an adjustment manually.
14. As a user, I want “换一批” to preserve meal time, taste, body part and other confirmed requirements, so that alternatives remain relevant.
15. As a user, I want alternatives not to repeat resources already shown in the current session, so that the command provides real variety.
16. As a user, I want to add a new exclusion while requesting alternatives, so that I can refine the same recommendation flow.
17. As a user, I want an explicit message when candidates are exhausted, so that the system does not silently weaken my requirements.
18. As a user, I want to explicitly request relaxed constraints or old results again, so that relaxing or repeating is my decision.
19. As a user, I want to set a meal plan as my current meal arrangement, so that today's meal conversation can use it as context.
20. As a user, I want to set a training plan as my current training arrangement, so that today's exercise conversation can use it as context.
21. As a user, I want meal and training current arrangements to coexist, so that choosing one does not archive or disable the other.
22. As a user, I want the assistant to mention today's planned meal or workout before offering nearby alternatives, so that I understand how the recommendation relates to my plan.
23. As a user, I want a temporary request such as “今天不想吃沙拉” to override today's plan only for this request, so that a short-term change does not become a permanent dislike.
24. As a user, I want a temporary request such as “今天不练腿” to override today's training context only for this request, so that the assistant respects my current state.
25. As a user, I want an explicit replacement confirmation before a plan item changes, so that casual chat does not mutate my saved plan.
26. As a user, I want to delete an unwanted draft plan, so that accidental generations do not accumulate forever.
27. As a user, I want to cancel a current meal or training arrangement without deleting its plan history, so that I can stop using it while retaining the record.
28. As a user, I want historical plans to remain available by default, so that version history and demonstration traceability are preserved.
29. As a user, I want the chat heading, quick questions and usage tips to remain visible while I work, so that I do not need to scroll to switch context.
30. As a desktop user, I want the message list and side content to scroll within a stable workspace, so that the composer remains easy to reach.
31. As a mobile user, I want only the message stream to scroll, so that nested scroll areas do not trap touch gestures.
32. As a user, I want to click “换一批” directly from the recommendation response, so that adjustment is discoverable.
33. As a user, I want the weekly plan table to show meal calories and key training parameters, so that I can act without opening another page.
34. As a user, I want to open a meal or exercise from the plan in a detail drawer, so that the plan remains the center of my workflow.
35. As a user, I want exercise details to include steps, target muscles, equipment, difficulty and cautions, so that I can perform the movement with context.
36. As a user, I want meal details to include nutrition, serving size, ingredients and allergens, so that I can judge suitability.
37. As a user, I do not want automatically generated cooking instructions, so that the product avoids presenting invented preparation guidance as source truth.
38. As a user, I want “今晚想吃得清淡一点” to infer dinner and light taste together, so that the assistant does not ask an already answered question.
39. As a user, I want a multi-clause request with ambiguity to be checked by the Agent, so that negation, time references and focus are interpreted correctly.
40. As a user, I want ordinary clarification prompts to contain one concise standard example, so that the assistant is not verbose.
41. As a user, I want natural Chinese time and meal expressions to remain supported, so that concise input does not reduce parsing quality.
42. As a project reviewer, I want multi-window training and grouped meals/workouts explicitly tracked as future work, so that the current scope is clear.

## Implementation Decisions

- Use a catalog normalization/import seam before the resource Provider. It produces stable exercise metadata, a completion version, an import report and an explicit rejection list.
- The catalog import may use deterministic scripts and bounded offline model completion because this is a demonstration project. Online model calls cannot create resource metadata, risk rules or training doses.
- Resource eligibility is separated into catalog visibility, single recommendation eligibility and plan eligibility. The default goal is to promote all non-damaged exercises through all three after normalization and deterministic checks.
- MVP 先独立开放完整目录浏览和单次推荐；周计划资格扩容属于可选增强，必须通过单独资格开关、抽样检查和回退白名单后才启用。
- Exercise candidates are filtered before the Training Plan Agent prompt. The full 1,324-item catalog is never sent as one prompt.
- Plan parameters such as sets, reps and duration remain generated by deterministic rules and are shown as plan parameters, not model-authored facts.
- Keep the typed resource identity `(resourceType, resourceId)` for feedback, exclusion and plan references.
- Replace the current four ambiguous user-facing feedback controls with Favorite and Reduce Recommendation. Favorite state is independent and does not boost ranking. Resource-level Reduce Recommendation remains the default; category-level preference requires explicit category language.
- Add an explicit alternative-recommendation action to the health chat response. It carries session identity, current task context and an exclusion set. Exclusions accumulate per session and resource type with a bounded retention policy.
- Candidate exhaustion is a domain response, not a silent fallback. The response offers explicit relaxation or repeat-old-results actions.
- Keep a soft current-assignment context for MEAL and EXERCISE independently. The context is selected by local date and user timezone and is lower priority than the current request.
- Separate plan lifecycle from current-assignment references. Draft deletion is allowed only for drafts and is confirmed; canceling a current assignment only removes the reference; archived history is retained by default.
- Preserve the highest existing seams: HealthOrchestratorService for context and intent flow, MealModule/ExerciseModule for retrieval and ranking, WeeklyPlanService for lifecycle mutations, ReviewedMealReader/ReviewedExerciseReader for detail access, and the frontend chat/plans modules for interaction.
- Add resource-by-ID detail reads through the reviewed reader boundaries. Plan item responses keep plan parameters and resource identity; the drawer loads meal/exercise details on demand.
- The plan table shows meal calories, meal time, exercise body part, duration, sets and reps. It does not generate meal cooking instructions.
- Desktop chat uses a fixed workspace with independently scrollable content; mobile chat uses one message scroll region and compact expandable side content.
- Intent handling is rule-first. Run Agent arbitration only when ambiguity signals are detected: conflicting values, negation, contrast, multiple temporal clauses, plan/reference resolution, low confidence or session-state conflict.
- Normalize common expressions such as “今晚/晚上” to dinner in the domain-specific normalizer. Keep all extracted slots and validate them against the legal slot dictionary.
- Keep a single concise clarification example for normal prompts; show additional format guidance only after ambiguity or repeated parse failure.
- Do not implement grouped meal occasions, grouped workout sessions or multiple same-day training windows in this specification. Their future model should be based on explicit meal/workout units rather than resource ID arrays.

## Delivery Guardrails

- 每个任务必须标注优先级、数据库迁移需求、外部依赖、预估复杂度和失败时的降级路径。
- P0 任务完成后先采集浏览器证据，再开始 P1/P2；不得为了补齐增强能力延迟主流程验收。
- 规格验收以“可重置的固定演示脚本”为最终用户侧证据，自动化测试覆盖契约和失败路径。

## Testing Decisions

- Tests must assert external behavior at the highest available seam, not private helper implementation details.
- Exercise catalog tests cover import counts, rejected-record reasons, completion-version metadata, normalized labels, plan eligibility and coverage by body part/equipment/difficulty.
- Health orchestrator integration tests cover current-assignment context, request precedence, temporary exclusions and alternative recommendation behavior.
- MealModule and ExerciseModule tests cover typed exclusion, no-repeat alternatives, candidate exhaustion and category-level preference only after explicit category language.
- WeeklyPlanService tests cover draft deletion, current-assignment cancellation, scope-independent current references and forbidden deletion of active/history plans.
- Reviewed reader/controller tests cover meal and exercise detail by ID, missing-resource behavior and detail drawer fallback behavior.
- Frontend tests cover fixed chat layout semantics, responsive scroll regions, recommendation-level “换一批”, optimistic feedback state and plan cleanup confirmations.
- Existing prior art to extend includes HealthOrchestratorService tests for ADJUST exclusion, PreferenceService folding tests, WeeklyPlanService lifecycle tests, browser acceptance for chat/plans/detail drawers, and architecture tests enforcing reviewed reader boundaries.
- Add at least one real MySQL migration/integration path for current-assignment references and draft deletion. Keep external model and Qdrant paths gated as in the existing suite.
- Browser verification is required for desktop and mobile chat scrolling, recommendation alternatives, plan details and delete/cancel confirmations against a running application.

## Out of Scope

- Professional manual review of every exercise record.
- Claims that automatically completed metadata is medically accurate or production-safe.
- Generated meal cooking methods or recipe instructions.
- Persistent meal/workout replacement from ordinary chat without explicit confirmation.
- Deleting archived history by default.
- Automatic relaxation of recommendation constraints.
- Multiple meal items per meal occasion or multiple exercises per workout session.
- Multiple training windows per day, intensity-specific windows or same-day multi-session scheduling.
- Reminders, check-ins, calendar sync, wearable integration or long-term execution tracking.
- Replacing the vanilla ES module frontend with a framework or build pipeline.

## Further Notes

- The existing `ACTIVE` database invariant is user-global and conflicts with independent meal/training current arrangements; implementation must introduce scope-aware current references rather than silently reusing that invariant.
- The existing session state replaces same-type last resources. The alternative recommendation feature requires changing this to bounded cumulative session exclusions.
- The exercise catalog ADRs and current-arrangement ADRs in `docs/adr/` record the hard-to-reverse trade-offs behind this specification.
- This specification is intentionally a product/architecture handoff. It does not authorize implementation of all tickets in one change; implementation should proceed in dependency order and update this spec with acceptance evidence.
