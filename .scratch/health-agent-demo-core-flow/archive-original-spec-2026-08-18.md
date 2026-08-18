<!-- Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V4 -->

# 健康 Agent 云端演示主流程稳定化规格（已归档）

Status: archived

> 2026-08-18：该版本工作量过重，已由同目录新的 `spec.md` 取代。保留全文用于追溯原始方案。

## Problem Statement

项目已经具备健康聊天、审核餐食与动作浏览、健康档案、每周个人计划、管理员 Trace 和 Compose 部署能力，但当前状态还不能稳定承担云端面试演示：对话与计划生成是两条断开的链，用户在聊天中表达的餐食偏好、训练需求和作息习惯不会成为计划草稿的生成依据；澄清 Agent 的纯文本 Prompt 与 JSON 解析契约冲突，导致系统先等待模型再固定降级；普通请求需要串行等待 Intent 与 Recommend/Clarify Agent，真实响应通常为 8–40 秒；用户界面缺少有效的阶段反馈，并暴露 session、trace、内部任务、版本和来源枚举等管理员信息。

现有管理员 Trace 页也不足以支持上述时延诊断。当前页面把筛选表单、八列表格和详情 JSON 放进两个较窄的通用 `.split` 栏位；长 trace/session ID、转义后的嵌套 payload 和缺少 `min-width: 0` 的中间容器会参与 Grid 最小内容宽度计算，导致详情栏撑宽和横向裁切。列表虽允许横向滚动，但关键状态、耗时和操作列默认位于不可见区域。详情默认展示整块 `traceJson`，其中 `inputPayload` / `outputPayload` 仍是 JSON 字符串，形成大量反斜杠和不可读长行；顶层 `SUCCESS` 也无法直接说明内部 Agent 是否超时、格式失败或确定性降级。截图中的样例 Trace 有 12 个事件，`REQUEST_FINISHED.latencyMs=21224`，但页面不能一眼回答耗时集中在哪个 Agent、实际使用哪个模型以及是否发生降级。

同时，计划详情被较宽的计划列表挤压，七日内容在不足的横向空间内强制排列；餐食卡片无法展示数据库已有的营养数据；餐食和动作必须点击小型“详情”按钮才能打开详情抽屉。这些问题会让面试官在首次浏览时直接感受到功能不完整、响应迟缓或页面存在明显缺陷。

本项目的交付目标不是生产级健康平台，而是部署到单台云服务器后，可以由面试官自行点击或由候选人现场操作，稳定展示完整核心功能的最小实现。核心优先级依次为：对话驱动的 Agent 计划生成闭环、可接受且有确定降级的回复速度、无明显缺陷的主要页面、可重复的云端部署与验收。

## Solution

提供一条混合式计划生成主流程：Agent 负责理解用户语言、归纳计划需求简报、识别调整意图和解释结果；确定性业务服务负责从正式审核资源中组合完整的每周个人计划，并执行风险、时间、资源与计划规则校验。语言模型不自由生成无法验证的餐食、动作或计划事实。

用户先在健康聊天中表达饮食偏好、训练目标与时段、作息习惯和硬约束。系统将这些信息累积为当前会话的计划需求简报，使用确定性模板追问缺失的必要信息，并在简报达到最小完整度后展示确认摘要和“生成计划”操作。用户确认后，前端携带当前会话标识调用现有草稿接口；计划服务读取该会话的计划需求简报，生成七天完整草稿，并将生成依据写入现有计划来源与版本快照。

演示首版将常见明确请求优先交给规则快路径，只有存在歧义或需要自然语言抽取时才调用模型。Clarify 不再调用语言模型。推荐卡片和计划草稿不等待非必要的模型润色；模型超时、格式错误或服务不可用时，系统使用已确认槽位和确定性模板完成响应，同时只在管理员 Trace 中记录降级原因。原有 Orchestrator + Worker Agent 原则继续保留，但升级为一个统一健康编排器加无状态理解/生成 Worker 和确定性领域模块，不为饮食、健身、作息复制三套持有状态的 Agent 会话。

管理员 Trace 页改造成面向诊断的主从工作台：查询接口只返回轻量摘要，选中后再加载完整详情；列表优先展示状态、总耗时、Agent 耗时、降级次数和时间；详情先展示阶段时间线和 Agent 调用摘要，再按需展开已解析的输入/输出，原始 JSON 降为次级视图。布局必须约束所有 Grid 子项和长文本，桌面、平板、手机均不得撑破页面。

第一里程碑完成“多轮对话 → 计划需求简报 → 确认 → 完整草稿 → 激活 → 查看”的闭环。第二里程碑增加围绕当前草稿的 MOVE 和 REPLACE：MOVE 调整已有项目日期或时间；REPLACE 使用审核资源替换已有餐食或动作，并按照现有周计划版本快照决策生成新版本。其他复杂调整通过重新生成草稿完成。

用户页面移除内部诊断字段，技术信息集中到现有管理员页面。计划页面改为窄计划选择区和宽详情区，并提供适合中等宽度和移动端的布局。餐食与动作整卡可打开详情抽屉，独立的收藏和反馈操作不触发抽屉；聊天卡片展示后端提供的结构化营养信息。

最终以单实例 Nginx、Spring Boot、MySQL 的 Compose 拓扑部署。Qdrant 不作为核心演示流程的必需依赖。应用具有明确的整轮响应预算、代理超时配合、健康检查、启动迁移、审核种子导入和真实 Chromium 验收证据。

## User Stories

1. As a 面试官, I want to open the cloud URL without installing software, so that I can inspect the project immediately.
2. As a 面试官, I want the main navigation and first page to render without visible errors, so that the demo feels complete.
3. As a 面试官, I want to ask the Agent for a weekly health plan, so that I can see the project's main Agent capability.
4. As a 健康聊天用户, I want to describe my food preferences conversationally, so that I do not need to fill a long planning form.
5. As a 健康聊天用户, I want to describe my training goal and preferred body parts, so that the weekly exercise schedule reflects my needs.
6. As a 健康聊天用户, I want to describe available equipment and difficulty, so that planned exercises are practical for me.
7. As a 健康聊天用户, I want to describe preferred training days and times, so that exercise sessions fit my schedule.
8. As a 健康聊天用户, I want to describe my usual sleep and wake times, so that the plan does not use a fixed generic routine.
9. As a 健康聊天用户, I want explicit exclusions and hard constraints retained, so that generated resources do not contradict them.
10. As a 健康聊天用户, I want the Agent to remember planning answers across turns in the current session, so that I do not repeat information.
11. As a 健康聊天用户, I want a new session to start a new plan brief, so that an unrelated old plan request does not leak into the new flow.
12. As a 健康聊天用户, I want the Agent to ask only for missing necessary information, so that planning remains short enough for a demo.
13. As a 健康聊天用户, I want clarification questions to appear promptly, so that a simple missing field does not require another slow model call.
14. As a 健康聊天用户, I want to see a concise summary of the collected plan brief, so that I can verify it before generation.
15. As a 健康聊天用户, I want to correct the brief before generation, so that a misunderstood preference does not become a full plan.
16. As a 健康聊天用户, I want a clear “生成计划” action when the brief is ready, so that I know how to continue.
17. As a 健康聊天用户, I want duplicate clicks on plan generation prevented, so that one action cannot create multiple drafts.
18. As a 健康聊天用户, I want the generated draft linked to the chat session that collected my needs, so that the plan uses the correct brief.
19. As a 健康聊天用户, I want the plan to contain seven days of meals, exercise and routine items, so that the result demonstrates a complete plan rather than a suggestion paragraph.
20. As a 健康聊天用户, I want all planned meals and exercises to come from reviewed resources, so that the Agent cannot invent unavailable content.
21. As a 健康聊天用户, I want plan generation to respect the current health profile and risk guards, so that personalization cannot bypass existing safety rules.
22. As a 健康聊天用户, I want a usable draft even when the language model times out, so that the main demo flow still completes.
23. As a 健康聊天用户, I want the plan page to open directly after generation, so that I can inspect the result without searching for it.
24. As a 健康聊天用户, I want to activate a valid draft, so that the complete draft-to-active lifecycle can be demonstrated.
25. As a 健康聊天用户, I want an invalid plan to remain a draft with an understandable message, so that validation failures do not create partial active data.
26. As a 健康聊天用户, I want to ask to move an existing plan item, so that I can demonstrate conversational refinement.
27. As a 健康聊天用户, I want to ask to replace a meal with another reviewed meal, so that the draft reflects changing preferences.
28. As a 健康聊天用户, I want to ask to replace an exercise with another eligible exercise, so that plan adjustment stays within the reviewed boundary.
29. As a 健康聊天用户, I want unsupported complex changes to produce a bounded explanation or regenerate option, so that the Agent does not pretend the change succeeded.
30. As a 健康聊天用户, I want replacement to preserve version provenance, so that the displayed plan and saved history remain consistent.
31. As a 健康聊天用户, I want explicit meal and exercise requests to return quickly, so that common interview interactions do not wait for unnecessary model calls.
32. As a 健康聊天用户, I want ambiguous requests to use the Agent only when needed, so that natural language support remains available without slowing every path.
33. As a 健康聊天用户, I want an understandable progress state during a slow plan operation, so that the page does not appear frozen.
34. As a 健康聊天用户, I want controls restored after timeout or failure, so that I can retry without refreshing the page.
35. As a 健康聊天用户, I want repeated submissions blocked while a request is running, so that latency cannot create duplicate writes.
36. As a 健康聊天用户, I want a normal concise fallback response instead of JSON or technical errors, so that degraded model behavior remains presentable.
37. As a 健康聊天用户, I want meal cards to show available calories and macronutrients, so that the displayed recommendation matches the underlying data.
38. As a 健康聊天用户, I want to click the body of a meal or exercise card to open details, so that the interaction target is obvious.
39. As a keyboard user, I want cards to expose a focusable detail action with Enter and Space support, so that the drawer is accessible without a mouse.
40. As a 健康聊天用户, I want feedback and favorite buttons to remain independent from the card detail action, so that liking a resource does not open its drawer.
41. As a 健康聊天用户, I want the plan detail area to receive most of the desktop width, so that seven-day content is readable.
42. As a 健康聊天用户, I want a usable plan selector on medium and mobile widths, so that the list cannot squeeze plan details.
43. As a 健康聊天用户, I want the seven-day view to reflow when space is insufficient, so that text and times are not visibly clipped.
44. As a 健康聊天用户, I want user-facing source names and media attribution retained, so that useful provenance remains visible.
45. As a 健康聊天用户, I do not want to see session UUIDs, trace IDs, internal task names, raw enums or implementation versions, so that the product reads like a user application.
46. As an 管理员, I want full trace, session, model, latency and fallback information in the administrator view, so that removing it from the user page does not reduce diagnosability.
47. As an 管理员, I want degraded Agent latency recorded, so that slow invalid responses can be diagnosed accurately.
48. As an 管理员, I want the administrator API protected in production, so that cloud visitors cannot read diagnostic data.
49. As a developer, I want one high-level scenario test to cover the core conversation-to-plan lifecycle, so that refactoring internal services does not require rewriting many tests.
50. As a developer, I want tests to assert externally visible contracts rather than exact model wording, so that harmless copy changes do not break the suite.
51. As a developer, I want deterministic fixture tests and a bounded live-model smoke test, so that CI remains stable while the actual integration is still verified.
52. As a developer, I want the reviewed Provider and Reader boundaries preserved, so that plan work cannot bypass resource approval rules.
53. As a developer, I want existing profile, risk, plan transaction and version invariants preserved, so that the demo repair does not regress completed work.
54. As a deployer, I want database migrations and reviewed seed import to run idempotently, so that a fresh cloud server can start without manual SQL repair.
55. As a deployer, I want application and proxy timeouts aligned to one end-to-end request budget, so that Nginx cannot return 504 while the application continues working.
56. As a deployer, I want Qdrant to remain optional for the core demo, so that an unavailable vector store cannot block chat, plan or browsing.
57. As a deployer, I want health checks and automatic container restart behavior, so that the demo can recover from a process failure.
58. As a candidate, I want a documented, repeatable interview script, so that I can demonstrate the same successful path under time pressure.
59. As a candidate, I want screenshots and Trace evidence from the deployed URL, so that I can verify the release before an interview.
60. As a candidate, I want secrets excluded from commits and screenshots, so that deploying the demo cannot expose credentials.
61. As an 管理员, I want the Trace list to show status, total latency, Agent latency and degradation count without horizontal hunting, so that I can identify abnormal requests quickly.
62. As an 管理员, I want a phase timeline ordered by `stepOrder`, so that I can see whether time was spent in routing, retrieval, an Agent call or persistence.
63. As an 管理员, I want each Agent event to show its effective model, latency, token usage, parse status and fallback reason, so that model and contract failures are distinguishable.
64. As an 管理员, I want nested input/output payloads rendered as expandable structured JSON rather than escaped strings, so that traces remain readable.
65. As an 管理员, I want long trace/session identifiers truncated with copy actions and accessible full values, so that identifiers cannot break the layout.
66. As an 管理员, I want raw Trace JSON available as a secondary copy/download view, so that human-readable diagnosis does not remove low-level evidence.
67. As an 管理员, I want selecting a list row to fetch only that Trace detail, so that a 50-row query does not transfer every large payload.
68. As a keyboard or mobile administrator, I want the Trace workbench, event expansion and label form usable without horizontal page scrolling, so that debugging is not desktop-width dependent.

## Implementation Decisions

### Product and architecture boundaries

- Optimize for a stable single-instance cloud demo, not a production-grade multi-user platform. The priority order is core plan flow, response speed and deterministic fallback, visible UI correctness, then secondary polish.
- Use the established domain terms：计划需求简报、混合式计划生成、每周个人计划、计划调整和详情抽屉。Do not introduce a second planning vocabulary or another plan aggregate.
- Preserve the orthogonal intent model. Planning uses `COMPOSITE + PLAN`; conversational plan refinement uses `ADJUST`. Risk flags and conversation phase remain independent dimensions.
- Keep the existing health orchestrator as the single conversation state machine. Do not create a second chat or planning orchestrator.
- Preserve the useful part of Orchestrator + Worker Agent: Workers return typed results and never read or mutate `HealthSessionState`; the orchestrator owns state, routing and persistence. Do not interpret “multi-Agent” as one stateful Agent per health domain.
- Treat the legacy `AgentFactory` path and the health `AgentContractModule -> AgentInvoker` path as two different implementations. The health path currently creates a fresh stateless `ReActAgent` per invocation rather than using the legacy session-level `AgentFactory`; update architecture documentation accordingly and do not add another session Agent cache merely for symmetry.
- A one-shot structured classification or wording call does not need ReAct reasoning, tools or memory. Prefer a direct chat-model/typed-generation seam, or reuse a role-bound stateless invoker, while retaining contract validation, Trace and deterministic fallback. Agent construction overhead is secondary to network/model latency and must not be presented as the primary cause without measurements.
- Store one current plan brief inside the existing health session state under a dedicated namespace. Do not create a new plan-brief table, repository, history page or independent lifecycle.
- A plan brief contains only the minimum generation inputs: meal preferences and exclusions, training goal, body parts, difficulty or equipment when supplied, preferred training days and time range, usual sleep and wake time, and applicable hard constraints. Existing health-profile facts remain in the health profile rather than being duplicated into the brief.
- A plan brief has an externally visible completeness state sufficient to distinguish collecting, ready for confirmation and confirmed. New-session behavior clears or isolates the current plan brief according to the existing anonymous-session contract.
- Agent responsibilities are natural-language understanding, brief field extraction, ambiguity detection, adjustment-command extraction and optional explanation. Deterministic Java services own candidate selection, plan composition, risk checks, time checks, resource validity, version rules and persistence.
- The model may select only from candidate identifiers supplied by the reviewed resource boundary. It may not create meal IDs, exercise IDs, nutrition facts, risk facts or schedule invariants.
- Clarification wording is deterministic and based on missing plan-brief fields. Remove the Clarify model call from the critical path. The old text-versus-JSON contract mismatch must be eliminated rather than hidden.
- Clear meal, exercise, routine and plan phrases use deterministic intent fast paths. The model remains available for ambiguous or compound language. Rule behavior must be evaluated against the existing health intent regression set to prevent cross-domain routing regressions.
- Routine fact questions and explicit single-domain browse/recommend phrases are eligible for zero-LLM fast paths. Ambiguous, compound or correction-heavy language uses one structured understanding call. Domain retrieval remains sequential after intent only when its inputs genuinely depend on model output.
- Recommendation and plan APIs return usable structured results without waiting for nonessential model prose. Explanation may use a bounded model call only when it cannot delay or invalidate the structured result; otherwise use deterministic Chinese copy.
- Bind model selection to an explicit role such as `LIGHT` / `MAIN`, not to a hard-coded historical model name. `AgentScopeInvoker` must not use `"qwen-max".equals(invocation.modelName())` as the routing decision. Environment overrides and future model renames must preserve the intended role.
- Trace must record the effective model that actually handled the request, not merely the requested configuration string. Add a regression test that assigns distinct fake main/light models and proves Intent and Recommend routes select the correct role under arbitrary model names.
- Use separate generation budgets and parameters by role. Intent uses deterministic JSON output, low temperature and a small completion cap; optional recommendation explanation has a larger but bounded cap. Use endpoint-supported JSON response/schema constraints where available.
- The current OpenAI-compatible adapter performs a blocking, non-streaming HTTP request and returns one completed response. This is acceptable for the first non-SSE milestone only when it runs within the bounded worker/deadline design; document that it increases perceived latency and limits request-thread throughput. Do not describe the returned `Flux` as token streaming.
- Model timeout, malformed output and upstream failure produce a normal bounded user response and an administrator-visible degraded Trace. No technical fallback reason is rendered to users.
- Use an application-level end-to-end deadline for each chat turn. Allocate remaining time to optional Agent calls instead of granting every sequential Agent an independent full timeout. Configure Nginx read timeout slightly above the application deadline.
- Target behavior for the demo is: deterministic clarification within 1 second under normal load; explicit resource recommendation within 3 seconds when the rule/DB path applies; Agent-dependent turns normally within 8–12 seconds; complete plan generation normally within 10–15 seconds; an end-to-end upper bound near 20 seconds followed by deterministic completion or bounded failure.
- Do not implement SSE in this batch. Frontend progress states are request-specific and honest, but the HTTP contract remains a normal same-origin request/response flow.

### Conversation-to-plan contract

- A planning conversation updates and returns a user-facing plan-brief summary. It does not write a weekly plan before explicit confirmation.
- When the brief is ready, the chat response provides a typed action that the frontend renders as “生成计划”. Do not infer plan creation from arbitrary affirmative text in the first milestone.
- The generation action calls the existing weekly-plan draft endpoint with the current health-session identifier, week start and timezone. The frontend must not omit the session identifier.
- Draft creation reloads the authoritative session and plan brief on the server. It must not trust a client-supplied copy of preferences or constraints.
- The plan composer accepts the plan brief in addition to quantified targets and calendar inputs. It uses preferences to filter/rank reviewed meals, training inputs to choose eligible exercises and days, and routine inputs to place sleep and wake periods.
- When optional brief fields are absent, documented deterministic defaults may be used. Required fields must be clarified before the “生成计划” action is enabled.
- The generated draft records the source session and a snapshot of the effective planning inputs alongside existing profile, resource, fact and rules provenance. Prefer extending existing provenance JSON or snapshot structures; introduce a migration only if current persisted structures cannot represent this safely.
- A generation request is idempotent for the same user action. The frontend disables duplicate submission, and the backend uses the existing request-id/idempotency convention where applicable.
- Successful generation returns the draft identifier and a typed navigation action. The frontend navigates to the plan page and selects the new draft.
- Plan activation continues to use existing transaction, row-lock, ACTIVE uniqueness, validation, profile-risk and version-snapshot rules.

### Plan adjustment contract

- Implement plan adjustment only after the generation milestone passes its acceptance gate.
- The supported commands are `MOVE` and `REPLACE`. Unknown commands, ADD, REMOVE, dosage changes and whole-week rearrangement return a bounded unsupported-adjustment response with an option to regenerate.
- `MOVE` modifies the date or time of one existing draft item and reuses existing week-boundary, overlap and zero-duration validation.
- `REPLACE` identifies one existing plan item and one replacement intent, obtains reviewed candidates through the existing resource Provider, applies risk and eligibility checks, and replaces the resource only after validation.
- Preserve ADR-0005: a resource replacement creates a new plan version/snapshot rather than silently overwriting provenance. This requirement supersedes the earlier rough idea of overwriting every draft change. Simple date/time movement may continue to use the existing draft patch behavior.
- An active plan is never edited in place. Existing active-to-draft editing behavior remains the entry point for subsequent adjustment.
- One conversation operates on at most one explicitly selected current draft. Switching drafts requires an explicit UI action or typed plan identifier, not inference from old conversation history.

### User and administrator presentation

- User chat does not render full session identifiers, trace identifiers, internal domain/task/phase labels, raw source enums or implementation versions.
- User resource details retain friendly source name, media attribution, nutrition, instructions and applicable safety information. Internal IDs, raw status fields and database/version metadata remain administrator-only.
- Reuse the existing administrator Trace and evaluation routes. Do not build a user-account or role-management system. Production administrator APIs remain protected by the existing admin token boundary.
- Fix degraded Trace recording so completed malformed Agent responses retain latency and model metadata. This is an observability fix and must not leak raw sensitive content.
- Extend the Agent invocation result and Trace event contract to retain effective model, input/output/total token counts when supplied by the provider, latency for both parsed and degraded completed responses, parse status and fallback reason. `TIMEOUT` without a completed response records elapsed wait time. Null token usage remains valid when the provider omits usage, but the UI must render it as “未提供” rather than implying zero.
- Derive an administrator-facing diagnostic outcome independently from the request persistence status. A request may be `SUCCESS` because deterministic fallback returned a valid response while its Agent outcome is `DEGRADED`; show both instead of recoloring the top-level status.
- Split Trace list and detail contracts. Time-range/session list APIs return a lightweight `TraceSummary` without `traceJson` and `responseJson`; the selected-trace endpoint returns the complete detail. Summary fields include trace ID, shortened session display value, request status, event count, total duration, aggregated Agent duration, Agent call count, degraded count, label state and creation time.
- Replace the Trace page's generic two-column `.split` and eight-column table with a dedicated diagnostic workbench. Desktop uses a bounded 320–380 pixel result rail and `minmax(0, 1fr)` detail canvas, or an equivalent full-width result list above a wide detail canvas. Medium and mobile widths stack the two regions; the selected detail becomes the primary region after activation.
- Render each result as a compact selectable row/card with status, short Trace ID, total duration, degradation indicator and timestamp. Full trace/session IDs use `overflow-wrap: anywhere`, a visible copy control and an accessible full-value label; they are not allowed to determine column width.
- Detail hierarchy is summary first, evidence second: request status and diagnostic outcome; total/Agent/non-Agent duration; ordered phase timeline; Agent call cards; candidate/risk/route events; labeling form; raw JSON. Highlight the critical path and any call exceeding its role budget.
- Parse `traceJson` once and recursively parse `inputPayload` / `outputPayload` only when they contain valid JSON. Render payloads in collapsed disclosure sections with formatted keys and values. Preserve the original redacted strings for copy/download and never execute or inject payload HTML.
- Raw JSON is a secondary tab/disclosure, not the default detail body. Provide “复制原始 JSON” and optional client-side download; keep redaction guarantees. Large payload panels have their own bounded vertical scroll, optional wrap toggle and `max-width: 100%`.
- Add Trace-specific containment rules rather than weakening global layout: every workbench grid child, detail wrapper, `details`, summary block and JSON panel has `min-width: 0`; long prose uses `overflow-wrap: anywhere`; code panels use controlled wrapping or internal horizontal scrolling. The root page must never acquire horizontal scrolling.
- Keep the label form visible without forcing the administrator to scroll through raw JSON first. It may follow the event timeline, live in its own tab, or use a desktop sticky side panel; on mobile it remains in normal document flow.
- Trace interactions require keyboard-visible row selection, native disclosure semantics, explicit loading/error/empty states, and a non-color-only degradation indicator. Selection and filter state should survive detail rendering within the current route.
- Extend the chat display-resource contract with structured nutrition fields. Populate them from the reviewed meal candidate already used for the response; do not add one browser request per card.
- Make the non-action area of meal and exercise cards activate the existing detail drawer. Use a native focusable control or equivalent semantics without nesting interactive feedback buttons. Enter and Space open details; Escape and existing drawer focus behavior remain supported.
- Feedback and favorite controls stop propagation and remain separately focusable. Activating them must not open the drawer.
- Replace the generic plan-page split with a plan-specific layout. Desktop uses a 280–320 pixel bounded selector/list and a flexible detail area. Medium widths use a compact selector or stacked layout before seven-day columns become unreadable. Mobile uses a readable reduced-column or day-focused presentation.
- Do not redesign the entire visual system. Preserve the existing colors, typography, cards, navigation and vanilla ES Modules architecture.

### Delivery phases and effort

- Phase 0 — acceptance baseline, 0.5–1 person-day: freeze the demonstration script, capture current timings and screenshots, define request IDs and the release evidence checklist for this batch.
- Phase 1 — correctness and latency critical path, 2–4 person-days: remove Clarify LLM, repair its contract tests, replace string-based model routing with role-based routing, record effective model/token/degraded latency, add safe intent fast paths, add end-to-end deadline/fallback, align proxy timeout and add frontend request states.
- Phase 2 — conversation-to-plan generation, 4–7 person-days: add the session plan brief, multi-turn collection and summary, typed generation action, explicit session propagation, plan-composer consumption, provenance snapshot and generation/navigation UI.
- Phase 3 — visible UI defects, 3–5 person-days: nutrition contract, user/admin information separation, whole-card detail interaction, plan-page layout, Trace diagnostic workbench and desktop/mobile browser regression.
- Phase 4 — optional conversational refinement, 2–4 person-days: MOVE extraction and execution, REPLACE candidate selection/versioning, unsupported-command behavior and adjustment browser flow.
- Phase 5 — cloud release, 2–3 person-days: Compose/proxy configuration, fresh-database startup, secret injection, health/restart verification, model prewarm, deployed Chromium acceptance and evidence capture.
- The first releasable milestone is Phases 0–3 and 5, estimated at 10.5–19 person-days. Phase 4 is implemented only after that milestone is stable, bringing the full estimate to approximately 12.5–23 person-days. Estimates assume no unexpected schema redesign or cloud-provider networking issue.
- Execute phases in order except that the isolated visual fixes in Phase 3 may proceed alongside Phase 2 when they do not touch the same frontend modules. Do not parallelize changes that both modify the health orchestrator, session state or weekly-plan service.

### Cloud deployment

- Deploy one application instance behind Nginx with MySQL using the existing Compose topology. Qdrant remains optional and is not required for the core acceptance script.
- Production startup requires model credentials, session secret and administrator token through environment configuration. No real key is committed, printed in evidence or rendered in the browser.
- Keep Flyway migrations and reviewed-resource seeding idempotent. A fresh database must reach health `UP` without manual SQL execution.
- Add or verify container restart policy, dependency health checks and application health endpoint routing. The public user flow must not expose actuator internals beyond the intended health endpoint.
- Prewarm one bounded Agent request after deployment or before the interview session when the upstream permits it. Prewarm failure must not make the application unhealthy if deterministic paths remain available.
- Document one command sequence for build/start, one rollback procedure to the prior image, and one browser acceptance script. Do not introduce Kubernetes, Redis, queues or multi-instance coordination.

## Testing Decisions

- The primary automated seam is one high-level API scenario through the health chat controller/orchestrator and weekly-plan controller/service. The scenario covers anonymous identity, multi-turn plan-brief collection, ready confirmation, draft generation with the same session, plan read, activation and externally visible failure behavior. Internal helper calls are not asserted.
- Add a second high-level adjustment scenario only in Phase 4. It covers MOVE, REPLACE, unsupported commands, resource eligibility, version provenance and active-plan copy-to-draft behavior.
- Extend existing health full-path regression and orchestrator scenario tests rather than creating a second end-to-end harness. Use existing weekly-plan controller tests, plan service tests, MySQL transaction tests, Agent contract tests, Trace tests and architecture boundary tests as prior art.
- A good test asserts observable response type, typed action, brief summary/completeness, draft contents, plan status, HTTP status, persisted version/provenance and absence of duplicate writes. It does not assert private method calls, Prompt formatting internals or exact non-safety prose.
- Add focused deterministic tests for plan-brief merge, correction, new-session isolation, required-field completeness, defaults, explicit exclusions and backward-compatible deserialization of old sessions.
- Add contract tests proving Clarify no longer performs a model call, returns the correct missing-field question and cannot produce `INVALID_JSON` degradation.
- Add deadline tests using controllable fake Agent latency. Prove an optional Agent cannot exceed the remaining turn budget, timeout returns a bounded user result, controls can be retried safely and one request does not write twice.
- Add an invoker routing test with non-legacy arbitrary model names. Prove an Intent call reaches the configured light-role model, a Recommend call reaches the configured main-role model, and the Trace's effective model matches the model that executed rather than the requested alias.
- Add intent fast-path regression samples for explicit meal, exercise, routine and weekly-plan language, including the already fixed “这周/本周健身计划” forms. Compare the deterministic path with the existing intent evaluation set and retain ambiguity fallback.
- Add display-contract tests proving reviewed meal nutrition reaches chat cards and that user DTOs omit administrator-only fields.
- Keep the health resource read-boundary architecture test unchanged. New plan composition and replacement code must access meals, exercises and routine facts only through the existing Provider/Reader seams allowed by ADR-0010.
- Keep plan-validation tests for seven-day bounds, cross-midnight overlap, zero-duration, risk blocking, resource plan eligibility, ACTIVE uniqueness and activation recheck. Add cases where brief-derived times exercise those same rules.
- Extend real-MySQL integration coverage for source-session/brief provenance, generation rollback, idempotent duplicate generation, REPLACE version creation and failed adjustment with no partial items or versions.
- Frontend behavior is accepted in real Chromium against the running same-origin application. Required interactions are: collect and correct a brief, generate a draft once, inspect all seven days, activate it, open meal and exercise drawers by clicking card bodies, operate favorite/feedback without opening drawers, resize to desktop and mobile layouts, observe timeout recovery and confirm user pages contain no trace/session/internal enum text.
- Trace frontend acceptance uses a deterministic fixture containing at least 12 events, two Agent calls, one degraded completed response, null token usage, nested JSON payloads, a 20,000-character truncated payload and full-length trace/session IDs. At 1440, 1024, 768, 414, 375 and 320 CSS pixels, the document has no horizontal overflow; summary actions remain visible; event order is complete; long values stay inside their panels; the label form is reachable by keyboard.
- Add frontend unit-level tests where practical for recursive safe payload parsing, duration aggregation, identifier shortening and copy values. Malformed nested JSON remains visible as escaped text and never becomes HTML.
- Add API/mapper tests proving list queries do not select or serialize `trace_json` / `response_json`, detail queries still return both, unauthorized requests remain rejected in protected mode, and a `SUCCESS + DEGRADED` trace produces the expected summary counters.
- Run a bounded live-model smoke after deterministic tests. The smoke proves at least one ambiguous plan-brief extraction succeeds with the configured model and that timeout/error still returns a usable deterministic path. Do not assert exact wording or require the live model in ordinary CI.
- Required local gates are compile, focused tests, normal full suite, real-MySQL gate and Compose static validation. Qdrant integration is required only if vector code changes; otherwise its gates may remain skipped according to the existing contract.
- Required deployed gates are fresh database startup, health `UP`, reviewed seed counts, same-origin API access, administrator API rejection without a token, the four-step interview script, screenshots at each major step and Trace review showing no user-visible technical failure.
- Performance evidence records client-observed duration and server Trace duration for clarification, explicit recommendation, ambiguous Agent turn and plan generation. A pass requires no request beyond the application end-to-end deadline and no proxy 504.
- Release evidence must distinguish model success from deterministic degradation. A top-level `SUCCESS` is not evidence that no internal Agent fallback occurred.

## Out of Scope

- Production-grade accounts, login, roles, tenant isolation or a complete administrator identity system.
- Independent persisted Plan Brief aggregates, Plan Brief history, reuse across sessions, rollback or management pages.
- Multiple drafts controlled concurrently from one conversation.
- LLM free-form generation of complete plan items, resource identities, nutrition values, medical facts or schedule rules.
- ADD, REMOVE, dosage, sets, repetitions, weight, intensity, whole-day rearrangement, whole-week conversational regeneration and arbitrary natural-language plan editing.
- Full undo/redo history for draft edits. Existing plan activation and version snapshots remain the durable history boundary.
- SSE, WebSocket streaming, token-by-token rendering or background job infrastructure.
- Qdrant as a required runtime dependency, vector-store redesign, embedding migration or RAG evaluation changes unless a discovered regression directly blocks the core flow.
- Frontend framework migration, Node/npm build tooling, broad design-system rewrite or native mobile application.
- Redis, distributed locks, queues, multiple application replicas, autoscaling, Kubernetes or zero-downtime orchestration.
- New health domains, medical diagnosis, treatment advice or expansion of chest-pain behavior. Existing risk behavior remains a regression gate but is not redesigned.
- Automatic long-term cleanup of anonymous demonstration identities or plans.
- Dataset expansion, bulk resource curation or relaxation of reviewed-resource eligibility.
- Cloud account creation, DNS purchase, certificate procurement or entry of real production credentials by an agent. The implementation may document these human-owned steps.

## Further Notes

- The core interview script is: open the chat → describe meal, exercise and routine needs over several turns → review the plan brief → generate the draft → inspect the seven-day plan → activate it. Phase 4 adds one MOVE and one REPLACE demonstration.
- The demo should include a short preset prompt sequence so the candidate can reproduce the flow under interview pressure, but the application must also accept equivalent free-form language through the same contracts.
- The first delivery checkpoint intentionally favors deterministic completeness over prose variety. A concise valid plan produced in 10–15 seconds is preferable to a more personalized response that sometimes takes 30–40 seconds or fails JSON parsing.
- Existing ADRs remain authoritative. In particular, risk checks stay deterministic and layered, reviewed-resource boundaries stay intact, and resource replacement follows weekly-plan version provenance.
- The plan-brief session representation is an explicit MVP compromise. If the project later needs multiple concurrent planning conversations, cross-session brief reuse or independent brief approval, it should be promoted to a separate versioned aggregate in a future ADR rather than gradually expanding the session JSON without a boundary.
- No new ADR is required for this batch because the selected MVP boundaries are deliberately reversible and reuse existing architecture. The REPLACE behavior follows the existing versioned-plan ADR rather than overriding it.
- Completion means the cloud URL can repeatedly execute the core script from a fresh anonymous session, with no obvious layout defect, no user-visible internal metadata, no duplicate writes, no request exceeding the configured deadline and a usable fallback when the model is unavailable.
- Current Trace UI evidence is sufficient to rank, but not fully attribute, the latency problem: the supplied capture shows 12 events and a 21,224 ms completed request; the local application was not running during specification review, so the exact per-Agent split must be captured in Phase 0 rather than inferred from the total.
- Ranked latency hypotheses and falsifiable checks are: (1) sequential Agent calls dominate—sum of `AGENT_CALL.latencyMs` approaches total duration and removing Clarify/model prose reduces the same fixture below budget; (2) one upstream model/endpoint dominates—one Agent event accounts for most duration while DB/rule phases stay small; (3) the 15-second per-call timeout dominates degraded turns—durations cluster near the configured timeout and fall with role-specific budgets; (4) non-Agent work or session-lock waiting dominates—the gap between total and Agent duration remains large after instrumentation; (5) Agent construction/adapter overhead dominates—direct typed-model invocation materially improves a no-network controlled benchmark. Test in this order and change one variable at a time.
- Trace UI audit findings are: critical—long nested payloads and missing Grid containment can expand the page beyond its intended canvas; major—the raw JSON dump is the primary view and hides the causal timeline; major—the eight-column table is placed in a narrow master pane; major—list APIs carry full detail payloads and the first selected row reuses that oversized summary object; major—top-level `SUCCESS` masks Agent degradation; minor—full identifiers lack truncation/copy affordances and consume the visual hierarchy. The remediation above preserves the existing brand, navigation and vanilla ES Modules rather than introducing a broad redesign.
- A tight browser reproduction command must be added in Phase 0 because the application was unavailable at review time. It should start from a deterministic Trace fixture, open `#/admin/traces`, select the known trace, and fail when `document.documentElement.scrollWidth > document.documentElement.clientWidth`, when the final event is absent, or when summary/label controls are outside the reachable layout. The same command becomes the regression gate after implementation.
