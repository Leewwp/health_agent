# 健康助手计划引导、严格路由与日常聊天规格

Status: ready-for-agent

本规格记录 2026-08-31 交互审查中确认的问题、历史背景、产品决策和本轮实施边界。它承接 ADR-0014、ADR-0016、ADR-0018，以及 `health-agent-week-template-routing-repair` 下的 01-05 票据；如果实现与本规格冲突，应优先回到本规格和 ADR-0018 重新核对，而不是新增关键词例外。

2026-08-31 晚间对两条 P0 实例做了独立的二次根因复核（逐跳静态追踪 + 数据库 trace 重放，全部结论均有 file:line 证据），本规格已并入复核结论，并修正初版两处不准确描述：其一，"能陪我聊天吗"未被社交短语拦截与 6 字长度限制无关（长度检查 6≤6 通过），真实机制是封闭致谢词表 + 整句精确匹配不含该句；其二，该实例的触发点不是歧义仲裁（本次仲裁低置信 0.6<0.65 已被拦下），而是澄清续轮对陈旧会话状态的零条件继承。仲裁信任仍是一个实存的同类接缝，保留为根因之一。

## Problem Statement

用户在健康 Agent 中遇到以下连续问题：

1. 用户输入“修改当前计划”后，系统回复“我会先为当前已启用的训练计划创建编辑副本……进入计划页后调整并保存”，但没有明确说明这是用户需要点击的页面操作，还是 Agent 会在聊天中完成修改。用户也不知道哪些内容可以继续在聊天中表达。
2. 用户在已有训练计划的澄清中选择“新建”后，系统显示“已为你新建一份训练计划简报。请直接补充当前字段”，其中“简报”和“当前字段”是内部术语；用户不知道从哪里开始，也不知道要补充哪些内容。
3. 在同一条新建训练计划流程中，用户随后输入“周一到周三”，系统从 `EXERCISE + PLAN` 跳到 `EXERCISE + RECOMMEND`（回复“这次训练想侧重增肌、减脂还是耐力？”，trace 标注任务 RECOMMEND）。这是 P0 级任务污染：一个合法的计划字段短答不能触发无关的单次推荐流程。
4. 用户首次询问“你是谁？能帮我做什么”或“你能帮我做什么”时，系统给出僵硬的任务澄清（且响应 meta 展示了陈旧的 领域：MEAL · 任务：ADJUST 标签），未说明健康助手身份、能力范围和下一步可说的自然语言。
5. 用户输入“能陪我聊天吗”时，系统因继承两天前遗留的 `MEAL + ADJUST` 澄清状态直接返回餐食推荐（“根据你的需求，为你推荐了香菇酱油鸡、姜丝蒸虾、快手清蒸红鲷鱼”）。无明确任务证据的日常聊天不得调用资源检索或生成推荐。
6. 日常对话请求期间，前端等待态显示“正在等待推荐结果”，即使本轮是聊天或能力介绍，也会错误暗示系统正在推荐。

这些问题不是孤立文案错误，而是同一边界问题的不同表现：计划需求简报、单次推荐和日常对话没有在所有路由入口共享同一套最终安全判定；模型仲裁结果仍可能绕过当前轮任务证据；澄清续轮可以零条件继承陈旧任务状态；新建计划消费后的生命周期没有被连续回归覆盖；内部状态术语泄露到用户界面。

## Root Cause Findings（2026-08-31 二次复核，逐跳证实）

- **RC-1 “新建”消费不转移生命周期（问题 3 的使能者）**：`HealthOrchestratorService.consumePlanClarifyReply` 的 redo 分支（`HealthOrchestratorService.java:809-821`，MEAL 侧 `:810-813`）只重置简报内容并返回 `clarify` 响应，生命周期转移传的是空 Map；统一持久点不会补任何转移。当会话残留该侧 `GENERATED`/`PAUSED` 生命周期（本例来自此前同会话生成过训练计划）时，下一轮被 `HealthBriefRouter` 优先级 6（`HealthBriefRouter.java:175-180`）判为 `inactive("LIFECYCLE_GENERATED")`，活跃简报门槛失效，字段答案落入通用意图链。**复现前提是“该会话曾生成过计划”**：全新会话（无 lifecycle 键）会被 `task==PLAN` 推导补上 OPEN（`HealthSessionService.readLifecycle` 与 `HealthBriefRouter.lifecycleOf` 双份推导），因此缺陷时隐时现——从全新会话起步的测试必然假绿，这正是多轮修复未根除的机制。
- **RC-2 澄清继承覆写任务（问题 3 的症状决定者）**：`HealthIntentRevisionService.revise:141-146` 在 `phase==CLARIFY` 且领域属推荐域、且（解析出槽位或短答 ≤12 字）时，无条件执行 `task = state.task()==ADJUST ? ADJUST : RECOMMEND`，完全不看 `state.task()==PLAN`。`PLAN→RECOMMEND` 是确定性覆写，该链路全程零模型调用——即使意图模型完美也会发生。
- **RC-3 澄清续轮零条件继承（问题 5 的直接根因）**：`HealthIntentRevisionService.continueBeforeAgent:57-62` 对 `phase==CLARIFY` 且推荐域的会话，**对输入内容零检查**（无字段解析要求、无长度限制）直接继承 `state.domain()+state.task()`，confidence=1.0，来源 `STATE_CONTINUATION`。真实复现中“能陪我聊天吗”继承了 08-29 遗留两天的 `MEAL+ADJUST`；历史槽位（晚餐/清淡）恰好满足最低澄清要求、ADJUST 按设计跳过推荐预检、`DETERMINISTIC_FAST_PATH` 直出推荐，连响应 Agent 都未调用。
- **RC-4 澄清响应重新武装继承陷阱**：能力问句那轮仲裁低置信失败后的澄清分支刻意保留旧域（keepIntent = 旧 domain/task）并把 `phase` 固化为 CLARIFY 持久化——澄清本身成为下一轮任意输入入继的触发条件；响应 meta 还把陈旧的 领域/任务 标签展示给用户。澄清挂起状态无任何时效/轮数边界。
- **RC-5 仲裁 authoritative 无硬证据复核（未触发但实存的同类入口）**：`AmbiguityArbitrationAgentService` 允许模型返回 RECOMMEND；`revise(..., arbitrationAuthoritative=true)`（`HealthIntentRevisionService.java:77-90`）信任其 domain/task，ADR-0016 模糊短句降级对 authoritative 路径失效；若模型对某个聊天句给出 ≥ 阈值置信度的 RECOMMEND 判定，将直达检索。本次实例未走此路径（低置信被拦），但必须与 RC-3 一并封堵。
- **RC-6 前端接缝**：等待文案固定“正在等待推荐结果”（`frontend/assets/js/pages/chat.js:114`）；`MODIFY_CURRENT_PLAN` 被渲染为按钮（`frontend/assets/js/ui/plan-actions.js:25-27`）但 `handleClick` 无对应分支（`chat.js:312-323`），点击无效（死按钮）；`taskFocus` 等伪槽位 key 进入 missingSlots 渲染通道，未知 key 经兜底逻辑渲染为不可点击的“其他偏好” chip（前端 `health-slot-labels.js` 与后端 `HealthSlotLabels` 的兜底分支），语义误导。
- **RC-7 硬证据词表多处漂移**：adjust 词表在 `IntentRuleService` 与 `HealthIntentRevisionService:112` 两份口径不同；编排器持有私有确认短语与追加词表；`HealthTaskEvidence` 含过宽裸字（如“菜”“餐”）。没有唯一所有者，也没有漂移守卫测试。
- **RC-8 作息启发式可劫持裸时间字段**：`HealthBriefRouter` 优先级 2 的“活动词×时间词”领域切换（`:146-152`）在活跃 OPEN 简报中仍可触发；supplement-chip 注入的“训练时段：”前缀（`chat.js:336-341`）与裸时间字段答案（无“改为”等修改表达）同类可被劫到 ROUTINE，与问题 3 同级的未报告接缝。
- **附带事实**：澄清选项与手打文本同走 POST message，前端除 `alternative` payload 外不注入任务提示；生产路径中意图 Agent 已被受限仲裁取代（`recognizeWithArbitration` 仅在无仲裁服务时才调用 IntentAgent），部分轮次（快路径、续轮）根本不发生模型调用。

## Historical Context

此前修复批次已经处理过以下相关问题：

- ADR-0014 统一了每周个人计划、编辑副本、四态生命周期和批量项目编辑；普通聊天不直接修改已保存计划。
- ADR-0016 引入显式健康任务路由、无确认计划简报和推荐前预览，要求新会话没有明确任务词时不能猜测进入推荐。
- `health-agent-week-template-routing-repair` 01 号票据将“还可以补充”改为由结构化简报动态计算，避免固定字段提示。
- 02 号票据建立了 `HealthBriefRouter` 的计划上下文优先规则，规定“修改表达 + 可解析字段”优先留在计划上下文，真作息问题才进入 ROUTINE。
- 03 号票据移除了目标周作为用户必填条件，保留不可见内部周锚点。
- 04 号票据将综合计划改为训练优先的餐训时间适配，并保留最终 Guard。
- 05 号票据加入一次受约束的歧义任务仲裁，规定仲裁只能返回受限候选，低置信或冲突时必须澄清。
- 当前 ADR-0018 相关自动化测试覆盖了完整计划后的训练时间修改、真作息问句、已生成简报重开、裸“餐食计划”澄清、日期表达和仲裁调用次数；但没有覆盖“已有训练计划 → 新建 → 周一到周三”的连续链路，也没有覆盖“日常聊天 → 继承陈旧澄清状态 → 资源检索”的最终安全闸门。已有的 GENERATED 不捕获回归对已完结流程是正确的，但没人意识到“新建”消费会把一个**新收集流程**留在这个状态；唯一的澄清消费回归是 MEAL 侧且会话生命周期为 OPEN，未测 GENERATED/PAUSED 前置——这是历史漏测的直接原因。

## Solution

从用户视角，系统需要提供五条互不混淆的体验：

1. **开始新建计划**：选择“新建”后，系统明确说“接下来开始新建一份训练计划/餐食计划”，从第一项必要条件开始逐项确认。用户输入“周一到周三”“下午六点到七点”等字段值时，系统继续留在计划制定流程。
2. **修改当前计划**：明确说明当前启用计划不会被直接覆盖，系统会创建编辑副本并打开计划页；用户在计划页完成项目级修改并保存。聊天中的“训练时间改为……”等字段修改只更新当前会话需求，重新生成计划草稿。
3. **单次推荐**：只有当前轮有明确推荐/替代推荐硬证据（共享词表、结构化动作，或意图模型与确定性判定双一致）时，才允许检索资源或生成推荐。
4. **日常聊天**：没有明确计划、推荐、调整或作息任务证据时，进入健康助手 CHAT 通道。CHAT 可以介绍身份、能力和使用方式，回答一般健康常识，但不检索餐食/动作、不生成计划、不修改计划。
5. **澄清续轮**：推荐/调整澄清中，只有本轮回答能解析为待澄清字段值、或含共享任务证据时，才继承上下文继续收集；普通聊天语句（“能陪我聊天吗”“你是谁”）打断继承进入 CHAT，不检索任何资源；陈旧澄清状态随时间/轮数失效，不得无限期重新武装。

## User Stories

1. As a first-time user, I want to know that the system is a health assistant, so that I understand what kind of help is available.
2. As a first-time user, I want to ask “你能帮我做什么” and receive a capability-oriented answer, so that I can choose a useful next request.
3. As a user, I want the assistant to explain that it supports meal recommendations, exercise recommendations, meal plans, training plans, composite plans and routine guidance, so that it does not incorrectly claim to be only a chat assistant.
4. As a user, I want to ask “能陪我聊天吗” without triggering resource retrieval, so that ordinary conversation remains ordinary conversation.
5. As a user, I want general health questions without task keywords to stay in CHAT, so that unrelated history does not hijack the current request.
6. As a user, I want risky medical or treatment questions to remain protected by the existing risk rules, so that the CHAT Agent cannot make unsafe medical claims.
7. As a user, I want to explicitly start a training plan and hear “接下来开始新建一份训练计划”, so that I know a new plan creation flow has begun.
8. As a user, I want the first training-plan question to name the first required condition, such as training goal, so that I know exactly what to answer.
9. As a user, I want the first meal-plan question to name the first required condition, such as meal times, so that I do not need to understand internal brief terminology.
10. As a user, I want “当前字段” and “简报” hidden from normal user-facing guidance, so that internal state-machine vocabulary does not confuse me.
11. As a user, I want to answer a new training-plan question with “周一到周三”, so that the training days are recorded without changing the task to recommendation.
12. As a user, I want to answer with “下午六点到七点”, so that the training time is recorded without changing the task to routine advice.
13. As a user, I want any valid plan-field continuation in an active `PLAN + OPEN` flow to stay in that flow, so that the next question remains relevant.
14. As a user, I want an invalid or ambiguous plan-field answer to produce a specific correction request, so that the system does not guess or start another task.
15. As a user, I want a true routine question such as “什么时候训练合适” to remain `ROUTINE + RECOMMEND`, so that plan context does not suppress a genuine routine request.
16. As a user, I want a clear recommendation phrase such as “推荐今晚的清淡晚餐” to start meal recommendation only after task validation, so that recommendations are intentional.
17. As a user, I want a clear alternative phrase such as “换一批动作” to remain an adjustment request, so that previous results are handled by the existing alternative flow.
18. As a user, I want a task phrase and the intent model to agree on both domain and task, so that a model hallucination cannot start a different workflow.
19. As a user, I want a model mismatch, low-confidence result or model failure to produce a clarification instead of execution, so that uncertainty is visible and safe.
20. As a user, I want a plan continuation to use the plan-field parser rather than the generic intent model, so that short field values cannot be reclassified by unrelated model heuristics.
21. As a user, I want “修改当前计划” to explain that an editing copy will be created and the enabled plan remains unchanged, so that I understand the persistence boundary.
22. As a user, I want a visible action such as “打开计划编辑” after choosing modification, so that the next step is actionable and not hidden in prose.
23. As a user, I want to express chat-based field changes such as “训练时间改为晚上八点”, so that the current plan requirements can be revised before regenerating a draft.
24. As a user, I want a changed plan brief to show the generation entry again, so that I know how to apply the revised requirements.
25. As a user, I want the enabled plan to remain untouched until I explicitly generate/save a new draft, so that an accidental message cannot overwrite my current plan.
26. As a user, I want the waiting state to say “正在生成回答”, so that the indicator remains correct for chat, clarification, recommendation and plan responses.
27. As a user, I want the response trace to explain whether a rule gate, intent model, clarification or fallback made the decision, so that P0 routing regressions are diagnosable.
28. As a maintainer, I want all plan continuation, task gating and chat routing to use shared seams, so that a future keyword fix cannot make the three paths drift again.
29. As a maintainer, I want tests to cover the complete new-plan continuation chain, so that a green isolated unit test cannot hide a broken multi-turn state transition.
30. As a maintainer, I want the future plan-item replacement flow separated from ordinary chat and plan creation, so that a high-risk write feature does not expand the P0 change unexpectedly.
31. As a user, I want an ordinary chat sentence during a recommendation/adjustment clarification to be treated as CHAT instead of being inherited as a field answer, so that stale session state can never make the system recommend food when I just want to talk.
32. As a user, I want capability questions such as “你能帮我做什么” to receive a capability answer that neither displays nor inherits a stale domain/task label, so that the assistant does not appear to be stuck in an old task.
33. As a user, I want clicking a supplement chip such as “训练时段” and then answering a time to stay in the plan flow, so that time words cannot reroute me to routine advice.
34. As a user, I want a clarification state left over from days ago not to be inherited by my current message, so that old sessions cannot hijack new input.
35. As a user, I want explicit recommendation/plan phrases to still work when the intent model is unavailable, because deterministic hard evidence alone is sufficient to start the task safely.
36. As a user, I want clicking the “修改当前计划” action button to open the plan editing copy, so that the displayed action is not a dead button.

## Implementation Decisions

### 计划引导与生命周期

- Keep `PlanBrief` and `MealPlanBrief` as internal plan-demand state. They must not be presented as the generated weekly plan result.
- Use “接下来开始新建一份训练计划/餐食计划” as the visible transition after a confirmed new-plan choice. Follow it with the first concrete missing requirement; never say “补充当前字段” without naming the field.
- Keep the existing distinction between a plan demand flow and a saved weekly plan. A new plan flow collects requirements, generates a draft, and only later participates in plan lifecycle operations.
- Preserve the existing enabled-plan safety boundary. “修改当前计划” creates or opens an editing copy in the plan page; it does not silently mutate the `ENABLED` plan.
- Make the modification action visible and actionable in the chat UI. The action label should describe opening plan editing, and its click path must invoke the existing editing-copy operation（`MODIFY_CURRENT_PLAN` 与 `APPEND_TO_CURRENT_PLAN` 同语义：打开计划页编辑副本）。
- Keep natural-language field changes in the current plan context. A valid field change updates the session plan demand, reopens a generated/paused side where allowed, and returns the generation entry; it does not directly write plan items.
- **澄清消费是显式生命周期转移点（封堵 RC-1）**：用户在“修改/新建”澄清中选择“新建”的同一轮，必须原子写入“对应侧 lifecycle=OPEN + 空简报 + intent=(对应域, PLAN)”，MEAL/EXERCISE 两侧同规；实现落点在 `consumePlanClarifyReply` 的 redo 分支补生命周期转移，而不是依赖下一轮推导补救。消费轮清空旧推荐槽位属预期（新计划从零收集），但必须与生命周期转移同轮原子生效。
- **单一生命周期事实源**：`HealthBriefRouter.lifecycleOf` 与 `HealthSessionService.readLifecycle` 的双份“task==PLAN 推导 OPEN”收敛为单一实现，避免两处口径漂移。

### 任务入口与闸门

- **任务入口合取规则（修正初版“硬关键词 + 意图 Agent 双必须”的表述，消除与 ADR-0018 确定性直路由/无模型降级模式的矛盾）**：最终任务闸门紧贴任何资源检索或计划处理器之前、且在所有确定性路由与模型/仲裁修正之后执行，要求当前轮存在下列任一“可执行证据”：
  1. 共享硬证据词表命中（确定性关键词/结构化短语）；
  2. 活跃 `PLAN + OPEN` 续轮且字段值可由计划解析器解析（解析器接管，不调用通用意图模型）；
  3. 结构化动作证据：`alternative` payload、后端下发的澄清/任务动作点击、`recommendationPreflightPending` + 确认短语；
  4. 本轮实际调用了意图/仲裁模型，且其 domain 与 task 与确定性判定双一致（仅领域一致不够）。
  规则：前三类证据单独即可执行——保留 ADR-0018 的确定性直路由与无模型降级能力；**模型单独判定（无任何确定性证据）不得启动任务**，降级为 CHAT 或澄清；模型与确定性判定不一致、或置信度低于阈值时澄清而不执行；无模型轮（快路径、续轮、降级）以“确定性判定 + 最终闸门”替代双一致，不因缺少模型结果而拦截合法确定性请求（封堵 RC-5，同时不回退降级模式）。
- **澄清继承保真（封堵 RC-2）**：`phase==CLARIFY` 的状态继承（`continueBeforeAgent` 与 `revise` 的两处同型分支）必须保持 `state.task()`，禁止把 `PLAN` 覆写为 `RECOMMEND/ADJUST`；推荐式任务改写仅在 `state.task() ∈ {RECOMMEND, ADJUST}` 时成立。
- **澄清续轮继承边界（封堵 RC-3/RC-4）**：推荐/调整域的澄清续轮仅当本轮输入解析出待澄清字段值、或命中共享任务证据词表时才继承上下文；显式聊天/能力问句（“能陪我聊天吗”“你是谁”“你能帮我做什么”）必须打断继承进入 `OTHER + CHAT`；**继承不豁免最终闸门**；澄清挂起状态设时效边界（跨会话日期或超过既定轮数后失效），不得无限期重新武装。
- **共享硬证据词表唯一所有者（封堵 RC-7）**：词表由 routing/intent 层的共享判定统一拥有；收编现有漂移副本（`IntentRuleService` 与 `HealthIntentRevisionService` 的两份 adjust 词表、编排器私有确认短语与追加词表、`HealthBriefRouter` 推荐词表、`HealthTaskEvidence`），审计并收窄过宽裸字（“菜”“餐”），全量回归验证收窄无破坏；新增词表漂移守卫测试。聊天逃生词定位为结构化条件下的短路优化，归同一所有者维护，不构成独立关键词例外清单（与 ADR-0018 “不得再增加关键词例外”一致）。
- **作息启发式不劫持裸字段值（封堵 RC-8）**：活跃 `PLAN + OPEN` 简报中，训练日/时间窗等字段的裸值答案（含 supplement-chip 注入的“训练时段：”前缀组合文本）必须留在 PLAN；真作息证据（睡眠/作息/咖啡因/显式“什么时候训练合适”问句）保持 `ROUTINE + RECOMMEND`；“训练时间改为……”在活动计划上下文中保持计划字段修改（延续 ADR-0018 对 brief-supplement-loop 优先级条款的废止）。
- Generated or paused plan demands must not capture arbitrary bare field values. They may reopen only on explicit plan modification/reopen evidence（含“新建”澄清消费这一显式重开证据）, consistent with ADR-0018.

### CHAT 通道与澄清响应契约

- Add a dedicated CHAT response service and prompt. It may explain the assistant identity and supported functions and answer general health questions, but it may not retrieve meal/exercise resources, generate plans, modify plans or invent medical diagnoses/treatment.
- The CHAT prompt must explicitly state that the assistant supports recommendations and plan creation, avoiding the contradictory “I am only a chat assistant” response.
- Keep existing risk evaluation before final CHAT output. Risk blocking and advisory behavior remain owned by the existing risk rule catalog and risk service.
- **能力问句与澄清响应契约（封堵 RC-4/RC-6）**：能力问句与聊天语句的澄清/回答响应不得继承展示旧 domain/task 标签；`taskFocus`/`domain`/`side` 类伪槽位 key 不得进入 missingSlots 渲染通道——澄清选项应以后端下发的结构化 action 表达（携带领域/任务语义，点击即合法任务证据）；前端未知槽位 key 不得兜底渲染为可误解的中文标签（“其他偏好”）。

### 其他

- Change the frontend waiting copy to “正在生成回答”. Task-specific result wording belongs in the completed response, not in the generic pending indicator.
- Extend trace data with hard-evidence result, model agreement result, final gate decision, downgrade reason, and 澄清继承判定与打断原因. Existing raw intent, revised intent and arbitration source remain available.
- No database migration is required for this P0 specification. Session lifecycle and brief JSON may be extended only if needed to make the new-plan `OPEN + PLAN` continuation durable and backward compatible.
- Keep all existing week-template rules: no user-facing target-week requirement, hidden internal week anchor, no date-driven semantic changes, and no reopening of superseded behavior.
- Treat “chat recommendation followed by replacing a specific plan item” as a future capability. It must have a target plan item identity, ambiguity confirmation, typed resource candidate action, editing-copy binding, optimistic version checking, resource qualification checks and transactional persistence before implementation.

## Testing Decisions

- Test externally observable behavior at the highest available seam. The primary seam is the health chat orchestration boundary with deterministic fakes for intent, arbitration, resource and response services; lower-level parser tests supplement it but do not replace multi-turn orchestration tests.
- Add a complete training flow regression: **前置必须种子该侧 `GENERATED` 生命周期（模拟“曾生成过计划”的会话）**，然后 existing enabled training plan → explicit new-plan choice → visible new-plan guidance → “周一到周三” and “下午六点到七点”. Assert every response remains `EXERCISE + PLAN`, no resource blocks are returned, the brief fields are updated, and the generation entry appears only when complete. 从全新会话起步会因 `task==PLAN` 推导掩盖状态缺口而假绿——这是历史漏测机制，不得再犯。
- Add the equivalent meal flow regression（同样种子 `GENERATED`/`PAUSED` 前置）and ensure the visible copy uses “开始新建一份餐食计划” rather than “餐食计划简报”.
- Add a negative regression for the same flow where a day/time field cannot trigger `RECOMMEND`, `ADJUST` or `ROUTINE`, even when the generic intent model returns a conflicting domain/task；并覆盖 `MEAL_NEW_VS_MODIFY` 澄清后回答不含修改/新建词的残余入口（消费返回 null → 落通用链）也不得进入推荐。
- Add **澄清继承保真负向**：`PLAN` 澄清状态下的短答不得被改写为 `RECOMMEND/ADJUST`（RC-2 的直接回归）。
- Add **最小聊天回归（RC-3 直接复现）**：会话状态 `(MEAL, ADJUST, CLARIFY)` + 输入“能陪我聊天吗” → 断言不调用 `MealModule.recommendMeals`（或等价资源模块）、无资源块、响应为 CHAT。同型用例覆盖 `(EXERCISE, RECOMMEND, CLARIFY)` 与仲裁返回高置信 RECOMMEND 的情况（RC-5）。
- Add **澄清时效回归**：跨会话日期/超过既定轮数的陈旧澄清状态不再被继承。
- Add active-plan chat escape tests for “能陪我聊天吗”“你是谁”“你能帮我做什么”. Assert `OTHER + CHAT`, no resource retrieval, no recommendation cards and no plan-brief mutation.
- Add fresh-session tests for capability questions and ordinary health chat. Assert the CHAT response names supported recommendation and plan capabilities and never says the assistant is unable to perform them；能力问句响应不携带陈旧 domain/task 标签。
- Add gate agreement tests for: hard evidence alone executes（无模型降级模式）; hard evidence plus matching model result executes; hard evidence plus mismatching model result clarifies; **model-only recommendation without any deterministic evidence downgrades to CHAT/clarification**; low confidence and model failure never execute; `alternative` payload、预检确认短语、`PLAN+OPEN` 字段续轮三类白名单在无模型时仍执行。
- Add continuation exception tests showing valid plan field values do not require a generic intent-model call, while new task entry does require both gates.
- Add true routine counterexamples in and out of plan context: “什么时候训练合适”“训练时段建议”“晚上几点后停止锻炼”. Assert `ROUTINE + RECOMMEND` and routine-only resources；同时补**裸时间字段/supplement-chip“训练时段：”前缀在 OPEN 简报中保持 PLAN** 的回归（RC-8）。
- Add generated/paused lifecycle regressions: ordinary “周二” does not reopen a generated brief; explicit “训练时间改成……” reopens the correct side and returns the generation entry.
- Add action-contract tests for “修改当前计划”: the response contains an actionable edit-copy action, the frontend handles the action（`MODIFY_CURRENT_PLAN` 点击导航到计划页编辑副本）, and the enabled plan remains unchanged.
- Add **词表漂移守卫测试**：共享硬证据词表与各收编点（adjust/确认短语/追加词表/推荐词表/任务证据）的一致性由测试固定，防止再次分叉。
- Add dedicated CHAT response service contract tests for valid JSON, malformed output fallback, capability wording, no-resource constraints and risk-safe wording.
- Update frontend module tests for pending text, new-plan action labels, visible guidance, structured brief summaries, absence of internal terminology, 伪槽位 key 不渲染误导标签.
- Add（轻量）并发回归：澄清消费转移 OPEN 与并发生成回写 GENERATED 的 `mergeLifecycle` 交互不互相覆盖错误状态。
- Preserve existing tests for dynamic supplementable guidance, internal week anchor, training-priority meal scheduling, candidate scarcity, plan lifecycle, recommendation preflight and ADR-0018 arbitration. Any changed expectation must state the superseding decision rather than delete coverage.
- Run Maven unit tests, frontend tests, and the MySQL-gated suite when the environment is available. Keep Qdrant/live-model gates independent.
- Perform real-browser acceptance against the running same-origin application. Verify: new training plan flow, “周一到周三” continuation, ordinary chat, capability question, recommendation keyword gate, loading text and no horizontal overflow on desktop/mobile. Record URL, inputs, clicks and visible results. 验收输入必须包含与快捷问题同义的自由输入（如“给我推荐一份餐食”“给我推荐一个训练动作”），证明任务入口不依赖快捷问题。
- Add a trace inspection case proving that a rejected model recommendation for “能陪我聊天吗” records the hard-evidence absence and final CHAT decision, making future regressions diagnosable.

## Out of Scope

- Do not implement chat-driven replacement of a specific plan item in this specification.
- Do not directly mutate an `ENABLED` plan from a chat message.
- Do not redesign the plan page editor, resource picker, weekly grid or plan lifecycle API.
- Do not remove `PlanBrief`/`MealPlanBrief` from session storage or rename internal Java types solely for copy changes.
- Do not reintroduce user-facing target-week or week-start requirements.
- Do not broaden routine facts or invent a universal best training time.
- Do not allow the CHAT Agent to perform meal/exercise retrieval, plan generation, plan editing, diagnosis or treatment.
- Do not require every active-plan field continuation to call the generic intent Agent; continuation remains a parser/state-machine responsibility.
- Do not replace the shared routing seam with new per-module keyword exceptions.
- Do not redesign the recommendation preflight / alternative / additionActions contracts; they are only incorporated into the final gate's evidence whitelist.
- Do not alter the old `/api/v1/diet/**` chain, reviewed-resource read boundaries, anonymous identity model or unrelated media/data work.

## Further Notes

- The highest-risk regression is not a single keyword miss. It is a state transition in which a confirmed new-plan choice leaves the next turn without a durable active plan-demand context. The implementation must persist and reload the corresponding `OPEN + PLAN` state before testing the next user message.
- **缺陷时隐时现的机制**：全新会话（无 lifecycle 键）会被 `task==PLAN` 推导掩盖 RC-1 的状态缺口，任何新建链路回归必须种子非 OPEN 生命周期前置，否则假绿。
- The final task gate must run after both deterministic routing and model/arbitration revision, immediately before any recommendation retrieval or plan handler. Earlier checks alone are insufficient because later revision can reintroduce a model-selected task.
- **两条 P0 实例的复现链全程零模型调用**：最终闸门与继承边界必须是确定性代码，不能依赖模型自觉（提示词约束只是概率性防线）。
- 生产路径中意图 Agent 已被受限仲裁取代（无仲裁服务时才调用 IntentAgent）；规格中的“意图模型一致性”在实现上指仲裁/意图模型输出的 domain+task 一致性，无模型轮以确定性判定替代第二合取项。
- “能陪我聊天吗” is a mandatory P0 canary. It must remain CHAT after a recommendation, after a plan clarification, after an arbitration attempt and after a generated-plan response.
- “周一到周三” is a mandatory P0 canary immediately after selecting “新建训练计划”. It must never produce recommendation resources, even if the input contains time/day words that overlap routine heuristics.
- The future item-replacement feature should reuse the current editing-copy and batch-item write contracts where possible, but it needs a new explicit target-selection contract. A natural-language date alone is not a stable item identity when multiple resources share the same day.
- Recommended future replacement flow: identify date and resource type, ask the user to select a specific item when ambiguous, create or bind an editing copy, run a constrained recommendation, expose a typed “replace this item” action, then save with expected-version and qualification checks.
- No code repair was intentionally started after the user confirmed this specification request. The only domain-model change from the preceding analysis is the clarified glossary distinction between plan results, plan-demand briefs and the plan creation flow.
