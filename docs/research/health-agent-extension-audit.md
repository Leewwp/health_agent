# 健康 agent 扩展设计审计与 GitHub 对照研究

**调查日期：** 2026-08-10
**范围：** 本地 `CONTEXT.md`、`docs/adr/`、`.scratch/health-agent/`、现有 Java/MySQL/提示词/前端实现，以及 GitHub 一手仓库页面和源码。

## 结论先行

当前方案的**架构方向是正确的，但还没有真正融入现有 agent 工作流**。

- 设计层面已经覆盖了饮食、健身、作息、综合周计划、健康档案、风险分层、受控 RAG、版本快照和模块边界。核心方向是 `domain + task + riskFlags + phase`，并将 `MealModule`、`ExerciseModule`、`RoutineModule` 与 `WeeklyPlanComposer` 分开，这与现有饮食状态机“可复用骨架、领域实现隔离”的目标一致。
- 代码层面仍是饮食专用实现：`Intent` 只有饮食枚举，`SlotBundle` 只有 7 个饮食字段，`DietOrchestratorService` 的 579 行链路直接调用餐食检索/排序/餐食 Agent，数据库也只有餐食、会话、Trace 和反馈相关表。健身和作息请求目前不能获得各自的资源、槽位、澄清和响应结构。
- 因此，当前状态应判断为：**规格已完成，31 号只有未提交、未运行验收的本地代码草稿；健康领域编排仍未实现，后续必须按 ADR-0008 的模块边界推进。**

最需要先处理的两个风险：审计时发现 `application.yml` 中配置过非占位 DashScope key，该 key 曾出现在工作区/历史中，仍需在 DashScope 侧轮换。另一个风险是 31 号草稿尝试加入匿名 Cookie 和生产 Header 隔离，但尚未接纳、运行或验收，不能作为已提交能力继续叠加健康 API。

## 方案覆盖度

| 能力 | 文档/issue 覆盖 | 当前代码覆盖 | 判断 |
|---|---|---|---|
| 跨品类意图 | [issue 04](../../.scratch/health-agent/issues/04-intent-mode-design.md) 决定 `domain/task/riskFlags/phase` | [Intent.java](../../src/main/java/com/diet/enums/Intent.java) 仍只有 `MEAL_*`、`HEALTH_RISK`、`OTHER` | 设计正确，未实现 |
| 健身槽位 | [issue 05](../../.scratch/health-agent/issues/05-fitness-slot-dictionary.md) 定义部位、器材、训练目标 | [SlotBundle.java](../../src/main/java/com/diet/model/SlotBundle.java) 只有 `mealTime`、`mood`、`scene`、`healthGoal`、`cuisine`、`taste`、`convenience` | 未实现 |
| 作息槽位/事实 | [issue 08](../../.scratch/health-agent/issues/08-routine-slot-facts-design.md) 定义时间类型和事实表 | SQL 没有 routine facts 表，Java 没有时间区间/作息模型 | 未实现 |
| 健康档案/量化目标 | [issue 13](../../.scratch/health-agent/issues/13-health-profile-targets.md) 定义档案和确定性公式 | 没有 profile 表、服务、Controller 或公式；`ChatRequest.context` 只是通用字段，当前链路未消费 | 未实现 |
| 领域编排 | [ADR-0008](../../docs/adr/0008-extend-meal-flow-with-domain-modules.md)、[issue 15](../../.scratch/health-agent/issues/15-multidomain-orchestration-boundaries.md) 定义三个模块和周计划组合器 | 只有 `DietOrchestratorService`，且直接注入 `MealSearchService`、`MealRankService`、`MealPlanService` 等餐食服务 | 未实现 |
| 周计划生命周期 | [issue 16](../../.scratch/health-agent/issues/16-weekly-plan-lifecycle.md) 定义草稿/激活/归档/版本快照 | 没有计划聚合、DDL、Mapper、Controller 或激活/替换 API；现有 `MealPlanService` 只是按餐次挑餐 | 设计清楚，未实现 |
| 风险分层 | [ADR-0006](../../docs/adr/0006-layered-health-risk-validation.md)、[issue 19](../../.scratch/health-agent/issues/19-health-risk-plan-validation.md) 定义候选前、组合时、LLM 后三层校验 | [RiskGuardService.java](../../src/main/java/com/diet/service/risk/RiskGuardService.java) 只有最终文本关键词扫描，且固定返回饮食文案 | 仅有旧版末端 Guard |
| RAG/来源 | [ADR-0004](../../docs/adr/0004-rag-boundary.md)、[issue 10](../../.scratch/health-agent/issues/10-rag-design.md) 定义首版餐食混合召回，指南证据检索延期 | 没有 `EmbeddingClient`、`MealRetriever`、向量索引或指南检索器；现有检索是 MySQL `JSON_OVERLAPS` | 设计清楚，未实现 |
| 动作展示与前端 IA | [issue 03](../../.scratch/health-agent/issues/03-exercise-display-prototype.md)、[issue 18](../../.scratch/health-agent/issues/18-frontend-module-information-architecture.md) 已确定卡片、详情抽屉、收藏、计划资格标记和原生模块边界 | 生产前端仍只有饮食 hash 路由和餐食卡片；原型与信息架构已完成 | 设计完成，生产实现未开始 |

## 与现有工作流的适配性

### 可以保留的骨架

现有链路的抽象顺序适合复用：

```text
会话串行/幂等
  -> 意图分类
  -> 请求槽位与长期档案合并
  -> 领域澄清
  -> 领域候选/事实检索
  -> 确定性过滤与排序
  -> Agent 解释
  -> 风险与结构校验
  -> 持久化
```

这正是 [ADR-0008](../../docs/adr/0008-extend-meal-flow-with-domain-modules.md) 的合理部分：不要重写对话入口，也不要把所有领域字段塞进现有餐食类型。

### 必须改变的边界

1. 外层应从 `DietOrchestratorService` 演进为健康编排器，先得到 `domain/task/riskFlags`，再交给领域模块。`MealModule` 可以包住当前餐食检索、排序、餐食响应和多餐逻辑；健身和作息应有独立的输入/输出类型。
2. `SlotBundle` 应保留为餐食模块内部类型，不应继续加 `trainingGoal`、`equipment`、`sleepTime` 等字段。建议使用 `MealSlots`、`ExerciseSlots`、`RoutineSlots`，综合计划只消费各模块产生的计划片段。
3. `task=PLAN` 不能继续等同于当前 `MEAL_PLAN`。综合计划需要 `domain=COMPOSITE`，局部替换需要 `task=ADJUST`，并用资源类型/领域标识具体调整对象。
4. `SourceMode` 是餐食库的概念，不应成为所有健康请求的共同必填项。当前 [DietOrchestratorService.java](../../src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java#L176) 会拒绝没有 `sourceMode` 的请求，新增健身/作息后应改为餐食模块或餐食请求的字段。
5. LLM 只能从候选动作、事实、计算结果和已通过校验的计划片段中生成解释。动作难度、风险、剂量、能量目标、来源和资源 ID 都应由规则/结构化数据决定。
6. 现有业务服务直接获取 `AgentFactory`/`ReActAgent`，外部模型调用缺少可替换 seam。应定义小型 `AgentInvoker`，由 AgentScope 和固定夹具两个适配器实现；内部契约模块统一负责 Prompt/契约版本、解析、校验、Trace 和失败分类，使测试不依赖真实 DashScope。

## 具体冲突与遗漏

### 文档内部需要先冻结的事项

- 03 和 18 已进入 Decisions so far；原型验收与前端信息架构已经完成，但生产前端实现仍属于后续实施切片。
- 餐食数据现已统一为：脚本默认生成 1,000 条离线候选池，issue 09 的最终 `meal_item` 先导入满足字段、来源和安全门槛的审核子集；媒体不合格时清除外链并使用稳定无图状态，数量不作为验收门槛。
- ADR-0004 与 issue 10 现已统一：首版只实现餐食候选混合召回，训练/作息指南证据检索延期，不参与数值或风险决策。
- 17、18、22-28 已将资源身份、前端交互、API、schema、量化目标、风险、Agent 降级和验收门槛收敛为实现契约；issue 05 的枚举 seed、issue 30 的资源审计缺口和实现期的最终字段/夹具已转入 31-36 号实施票据，不再是待决策项。

### 高优先级

1. **密钥配置与部署决策冲突。** [application.yml](../../src/main/resources/application.yml#L16) 审计时曾包含非占位 DashScope key；当前已改为环境变量注入，但该 key 曾进入工作区/历史，仍需在 DashScope 侧轮换。issue 21 明确要求 key 只通过部署环境注入。
2. **身份迁移只有未验收草稿。** 31 号本地草稿尝试由 API 拦截器统一解析 HMAC HttpOnly Cookie，并保留开发 `X-User-Id` 调试回退；后续必须先审查和运行验证，不能把草稿状态写成已实现事实。
3. **个人餐食前置检查会拦截非餐食能力。** [DietOrchestratorService.java](../../src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java#L226) 在意图识别之前检查 `PERSONAL` 餐食库是否为空。用户即使只询问健身或作息，也会被引导去录入餐食；这是新增领域接入后会直接触发的流程冲突。

### 中高优先级

4. **意图和提示词仍只理解饮食。** [intent.txt](../../src/main/resources/diet/prompts/intent.txt#L1) 只列出 `MEAL_*`，并明确 slots 只能是七个餐食字段；[IntentAgentService.java](../../src/main/java/com/diet/service/intent/IntentAgentService.java#L127) 也只解析这七个字段。即使 LLM 输出健身或作息字段，当前解析器也会丢弃。
5. **澄清规则会把所有领域当成餐食。** [ClarifyRuleService.java](../../src/main/java/com/diet/service/clarify/ClarifyRuleService.java#L21) 只要求 `mealTime` 和饮食 `healthGoal`，默认问题也是“这顿……”。健身应澄清部位/器材/训练目标，作息应澄清时间、时长和工作日约束，综合计划还要先补齐档案和硬约束。
6. **会话持久化格式是餐食专用。** [SessionStateService.java](../../src/main/java/com/diet/service/session/SessionStateService.java#L103) 固定读写七个字段和 `lastRecommendations` 餐食 ID 列表。它无法区分动作 ID、作息事实、计划片段、档案版本和风险标记；直接扩展 JSON 会导致语义混用和替换逻辑错误。
7. **数据库和响应 DTO 没有新资源边界。** 当前 SQL 只有 `meal_item`、`diet_slot_option`、`diet_sessions`、`diet_messages`、`diet_request_trace`、`recommend_feedback`；[MealResponse.java](../../src/main/java/com/diet/model/MealResponse.java#L18) 也只能表达餐食卡片。动作 GIF/步骤/审核状态、作息事实来源、健康档案版本、周计划快照都没有落点。
8. **风险 Guard 仍是饮食关键词替换。** [RiskGuardService.java](../../src/main/java/com/diet/service/risk/RiskGuardService.java#L22) 只扫描医疗/极端节食/绝对化/特殊人群关键词，命中后返回“饮食角度”的固定文案。它没有候选前过敏/伤病过滤、计划时序和恢复校验、作息冲突校验，也不能区分“拒绝具体计划”和“允许但提示”。
9. **当前餐食规划不能升级为周计划。** [MealPlanService.java](../../src/main/java/com/diet/service/plan/MealPlanService.java#L37) 只是将餐次拆开、每餐取一个餐食；[DietOrchestratorService.java](../../src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java#L366) 仍按 `MEAL_PLAN` 生成餐食响应。它没有七天日历、训练安排、作息时间、能量预算、激活/归档或局部替换版本。

### 中优先级

10. **RAG 仍停留在设计。** issue 10 已正确限制 RAG 的边界，但仓库没有 Embedding 依赖、检索接口、离线索引、引用 DTO 或降级实现。实现前不能把“有可靠信源”写成已有能力。
11. **测试验收目标与仓库现实存在落差。** issue 20 要求多品类编排、计划不变量、风险规则和混合检索自动化测试，但 `src/test` 不存在。它是未来验收规格，不是当前质量保证；应先把接口和规则模块抽出来，再按风险优先级加测试。
12. **部署方案仍有未接纳的组件。** 31 号草稿包含 Flyway 和 Actuator，Redis、OpenAPI、Embedding/RAG 仍未引入。不要把工作区草稿或设计当成已提交运行时事实。
13. **幂等只有未验收草稿。** 31 号草稿为 `ChatRequest` 和 `diet_request_trace` 增加了 `requestId`/响应快照；必须验证迁移唯一约束、并发重复提交、数据库异常和 Trace 保留策略后才能接纳。

## 推荐的落地顺序

1. 完成 31 号运行前置验证：导入旧库、执行 Flyway V2、启动 Actuator，并验证 Cookie、admin token、旧饮食接口和 requestId 幂等。
2. 实现 32 号 `AgentInvoker`、角色契约、固定夹具和外层 `HealthIntent`/领域路由；使用版本化种子资源跑通 `MealModule`/`ExerciseModule`/`RoutineModule`，并移除全局餐食库前置判断。31-32 通过后形成可面试演示的 Agent 主流程。
3. 按 30 号资源门槛完成 33 号审核资源子集、分页浏览 API 和餐食 hybrid RAG；媒体不合格时使用无图状态，资源和 RAG 不回头改写 32 号 Agent 契约。
4. 实现 34 号健康档案、确定性量化目标、`WeeklyPlanComposer` 和计划聚合/版本表，先生成 DRAFT，经过确定性校验后才能激活；首版只支持已有项目的日期/时间移动和备注，资源替换延期。
5. 实现 35 号前端模块和用户页面，完成桌面与移动端主流程的真实浏览器验收。
6. 由 36 号汇总并从干净环境复现各阶段已经建立的自动化、接口/浏览器冒烟、部署检查和运行手册；不把核心测试首次推迟到 36 号。指南证据检索继续留到后续阶段。
7. 以 `.scratch/health-agent/spec.md` 作为唯一实现真源，按 31-36 号票据顺序推进，所有新领域取舍先回到决策票，不在实现票中临时发明规则。

## 本轮文档与规划审计（2026-08-10）

本轮检查确认，现有 ADR、领域词汇、研究报告和实施计划已经覆盖主要方向；此前缺少的 API/DTO、DDL 与迁移顺序、量化目标公式、风险规则、Agent 降级契约、身份安全验收和发布证据矩阵，已由 22-28 号决策票收敛。

因此已发布 `.scratch/health-agent/spec.md` 作为正式规格，并由 31-36 号票据拆分为基础设施、Agent 垂直闭环、审核资源/RAG、档案/计划/风险、前端和交付验收六个实施切片。17、18、22-28 和 30 号已完成，29 号 Java 21 基线保持开放；安全规则、Agent 能力和部署安全均按面试 MVP 最小实现控制范围。31-32 是面试可演示门槛，31-36 才是完整 MVP；资源正式导入受 30 号审计约束，但不再阻塞 32 号 Agent 主流程。

另外确认两个容易被遗漏的交付状态：README 已按当前已提交饮食能力和未来健康规划分开说明；31 号工作区草稿曾通过源码级 `javac --release 21`，但本轮不提交开发代码，也没有完成标准 Maven、MySQL、Flyway、应用启动和接口验收，后续必须先独立审查。

## GitHub 对照研究

以下结论来自 GitHub 仓库 README、源码和仓库 API 页面；star 数会变化，仅作规模信号，不代表质量评级。

### 1. LangGraph 多 agent 医疗助手：可借鉴外层路由

仓库：[souvikmajumder26/Multi-Agent-Medical-Assistant](https://github.com/souvikmajumder26/Multi-Agent-Medical-Assistant)（调查时约 948 stars）。

其 [agents/agent_decision.py](https://github.com/souvikmajumder26/Multi-Agent-Medical-Assistant/blob/main/agents/agent_decision.py) 使用 `AgentState` 保存消息、当前 agent、输入类型、置信度和校验状态，通过 LangGraph `StateGraph` 做条件路由；输入 Guardrail 先于路由，RAG 置信度不足时可转 web search，并在部分场景使用人审中断/恢复。这个结构与本项目希望保留的“统一入口 + 明确状态 + 条件路由 + Guard”相似。

**适合借鉴：** 将 `domain/task/riskFlags/phase` 放到统一状态，明确路由和降级边；把 Guardrail 和业务路由分开；让 Trace 记录路由、置信度和降级原因。
**不适合照搬：** 它是医疗影像/诊断研究系统，包含 CV 模型、web search 和人审流程；对本项目一般成年人的饮食/训练/作息 MVP 过重，而且其 agent 决策并不能替代本项目所需的确定性候选和计划规则。

### 2. LifeSync：可借鉴领域 Facade、策略和结构化计划管道

仓库：[beyzadegirmenci/LifeSync](https://github.com/beyzadegirmenci/LifeSync)（调查时约 2 stars）。

其 [WellnessPlanFacade.js](https://github.com/beyzadegirmenci/LifeSync/blob/main/backend/src/facades/WellnessPlanFacade.js) 统一处理 `diet`/`exercise` 计划生成，使用 [planPromptBuilder.js](https://github.com/beyzadegirmenci/LifeSync/blob/main/backend/src/utils/planPromptBuilder.js) 生成按日/周/月的结构化 JSON prompt，再由 [planValidator.js](https://github.com/beyzadegirmenci/LifeSync/blob/main/backend/src/utils/planValidator.js) 解析、校验、重试；`StrategyFactory` 还按 beginner/intermediate/advanced 选择策略。

**适合借鉴：** 计划生成入口用 Facade，训练等级用策略对象，LLM 输出使用固定 schema、重试和显式 `planType`。
**必须改进：** 该项目的 validator 会补齐/循环复制空单元，主要验证表格形状；exercise prompt 仍让 LLM 自由选择动作和训练内容，作息只有输入字段，没有本项目要求的事实表、风险分层和 `plan_ready` 资源资格。因此只能借鉴代码组织，不能把它的计划验证当作安全校验。

### 3. GymCoach：可借鉴训练计划的资源约束和人工确认

仓库：[Julien-Au/gymcoach](https://github.com/Julien-Au/gymcoach)（调查时约 18 stars）。

其 [program-system-prompt.ts](https://github.com/Julien-Au/gymcoach/blob/main/lib/prompts/program-system-prompt.ts) 要求模型返回严格的 program JSON，并提供肌群、器材、组数、次数、RIR、休息等结构化字段；[program-exercise.ts](https://github.com/Julien-Au/gymcoach/blob/main/lib/schemas/program-exercise.ts) 用 Zod 做范围和关系校验。其 [coach-system-prompt.ts](https://github.com/Julien-Au/gymcoach/blob/main/lib/prompts/coach-system-prompt.ts) 要求教练在既有计划内调参，不私自换动作/改 split；调整以 `<adjustments>` 输出，交给用户审核和显式接受。提示词还把 readiness、睡眠质量、肌肉酸痛、疲劳和有氧训练作为输入信号。

**适合借鉴：** 动作使用资源 ID/精确名称，训练参数用 schema 约束；草稿先由用户确认；调整不能自动覆盖计划；将睡眠/疲劳作为训练上下文而不是让 LLM 自由推断安全结论。
**不适合照搬：** 它面向已有训练记录的增肌/力量训练，包含 RIR、负重和进阶算法；本项目第一版应继续遵守 issue 14 的一般成年人入门活动边界，不引入自动加重或竞技处方。

### 4. Sleep Specialist：可借鉴作息回答的引用形式

仓库：[zobi-logs/Sleep_Specialist_Chat_Assistant](https://github.com/zobi-logs/Sleep_Specialist_Chat_Assistant)（调查时约 0 stars）。

README 和 [icd11_sleep.py](https://github.com/zobi-logs/Sleep_Specialist_Chat_Assistant/blob/main/icd11_sleep.py) 展示了“FAISS 检索睡眠资料 + LLM 回答 + 每次带来源”的最小闭环，并包含来源数据摄取脚本。

**适合借鉴：** 事实回答要保留来源元数据，并在没有匹配资料时降级为信息不足，而不是由模型补写。
**不适合照搬：** 该仓库规模小，依赖外部 PDF/FAISS，脚本中还把第三方 API 凭证写在源码里；本项目首版应坚持 issue 08/10 的结构化作息事实和餐食混合召回边界，指南证据检索只作为后续扩展。

### 5. Healthcare Assistant：可借鉴按业务主题拆 Crew，但不宜复制其自由研究模式

仓库：[Dharm3438/Healthcare-Assistant](https://github.com/Dharm3438/Healthcare-Assistant)（调查时约 25 stars）。

其 [initialize_crew.py](https://github.com/Dharm3438/Healthcare-Assistant/blob/main/initialize_crew.py) 用 CrewAI 分别定义 disease、diet、exercise、medical query 等 Crew，每个 Crew 通常是“多个搜索任务 -> reporting agent”，[config/agents.yaml](https://github.com/Dharm3438/Healthcare-Assistant/blob/main/config/agents.yaml) 也按医学研究、营养、理疗和报告角色分开。

**适合借鉴：** 领域角色、工具和输出责任可以分开；不同领域不必共用一个万能 prompt。
**不适合照搬：** 它把疾病研究、饮食恢复和理疗计划放在医疗场景，依赖多种实时搜索工具，主要生成 Markdown 报告；代码还存在训练/配置复用不一致等迹象，不能作为本项目安全计划生成的实现基准。

## 最终判断

你的方案**正确融入项目的方式**是“保留外层状态机，拆出领域模块，新增综合计划组合器”，而不是把 `SlotBundle` 和 `DietOrchestratorService` 扩成健康万能对象。文档已经覆盖了这个方向，尤其是 ADR-0002、ADR-0006、ADR-0008 和 issues 04/15/16/19；当前主要缺口是实现、迁移兼容、数据 schema 和验收。

GitHub 项目可以提供三类实现参考：

1. 用 LangGraph 项目参考状态图和条件路由的表达方式；
2. 用 LifeSync 参考 Facade/策略/结构化计划输出的代码组织；
3. 用 GymCoach 参考动作资源约束、严格 schema、草稿确认和局部调整；用 Sleep Specialist 参考来源引用。

不要直接复制这些项目的“LLM 生成即计划”路径。对本项目而言，资源计划资格、健康档案公式、风险规则、计划不变量和版本快照必须先于 LLM，LLM 只负责在已验证结果上做解释和表达。

## 来源索引

- 本地领域词汇：[CONTEXT.md](../../CONTEXT.md)
- 关键架构决策：[ADR-0002](../../docs/adr/0002-orthogonal-health-intent-model.md)、[ADR-0004](../../docs/adr/0004-rag-boundary.md)、[ADR-0006](../../docs/adr/0006-layered-health-risk-validation.md)、[ADR-0008](../../docs/adr/0008-extend-meal-flow-with-domain-modules.md)
- 本地 tickets：[issue 04](../../.scratch/health-agent/issues/04-intent-mode-design.md)、[05](../../.scratch/health-agent/issues/05-fitness-slot-dictionary.md)、[08](../../.scratch/health-agent/issues/08-routine-slot-facts-design.md)、[10](../../.scratch/health-agent/issues/10-rag-design.md)、[12](../../.scratch/health-agent/issues/12-identity-data-isolation.md)、[13](../../.scratch/health-agent/issues/13-health-profile-targets.md)、[15](../../.scratch/health-agent/issues/15-multidomain-orchestration-boundaries.md)、[16](../../.scratch/health-agent/issues/16-weekly-plan-lifecycle.md)、[19](../../.scratch/health-agent/issues/19-health-risk-plan-validation.md)、[20](../../.scratch/health-agent/issues/20-quality-acceptance-baseline.md)、[21](../../.scratch/health-agent/issues/21-deployment-resource-delivery.md)
- GitHub 检索页：[health assistant multi-agent](https://github.com/search?q=health+assistant+multi-agent&type=repositories)、[nutrition recommendation agent](https://github.com/search?q=nutrition+recommendation+LLM+agent&type=repositories)、[fitness coach LLM](https://github.com/search?q=fitness+coach+LLM+agent&type=repositories)、[healthcare multi-agent](https://github.com/search?q=healthcare+multi-agent&type=repositories)
