# 健康 Agent MVP 正式规格

- 状态：`ready-for-agent`
- 版本：`1.1`
- 日期：2026-08-10
- 适用项目：`health-agent`
- 实现原则：面试展示项目的最小可用闭环

## 1. 目标

将现有饮食推荐服务扩展为健康 Agent，保留原有饮食 Agent 的状态机和 AgentScope 调用方式，新增饮食、健身、作息三类单次能力和综合周计划。

交付重点是能清楚展示：

- Spring Boot + MyBatis + MySQL 的后端领域扩展；
- AgentScope/DashScope 的结构化意图理解和受约束解释；
- 可替换的 Agent 运行接口、严格输出契约、确定性降级与链路 Trace；
- 餐食 hybrid RAG 与结构化降级；
- 确定性健康档案、量化目标、计划校验和版本快照；
- vanilla hash 路由 + 原生 ES Modules 的前端拆分；
- 可复现的核心测试、浏览器验收和部署健康检查。

本规格是实现阶段的唯一真源。当前已提交代码仍以旧饮食链路为主；工作区存在 31 号基础设施的未提交代码草稿，但尚未经过 Maven、数据库和接口验收，不能视为已落地能力。健康领域能力仍属于待实现内容。

实施采用两级门槛：31-32 号先形成可离线测试、可真实调用、可降级和可追踪的 Agent 垂直闭环；33-36 号完成正式资源、RAG、周计划、前端和发布证据。前一门槛可用于阶段性面试演示，但只有后一门槛通过才可称为完整健康 Agent MVP。

## 2. MVP 范围

### 必须交付

- 饮食、健身、作息单次推荐；
- 版本化小型审核资源集，以及无真实 API key 时可复现主流程的固定夹具模式；
- 统一健康聊天入口和旧饮食 API 兼容入口；
- 最小健康档案、能量估算区间和计算依据展示；
- 周计划 `DRAFT -> ACTIVE -> ARCHIVED`、历史版本和档案快照；
- 草稿中移动已有餐食、动作、作息项目的日期/时间并保存备注；
- 类型化收藏、喜欢、不喜欢、采纳反馈；
- 动作浏览页、餐食浏览页、我的计划页和聊天详情抽屉；
- 餐食 hybrid RAG，Embedding 失败时结构化检索；
- 少量版本化风险规则、固定文案和确定性降级；
- Java 21、dev/prod 配置、匿名 Cookie、同源反代、admin 入口隔离和健康检查；
- 核心自动化测试、接口/浏览器冒烟和部署验收。

### 明确不做

- 真实注册登录、密码、JWT、RBAC、密钥管理平台和复杂 CSRF 框架；
- 多实例、Redis 分布式锁、消息队列、事件总线和长期行为追踪；
- 训练/作息指南向量检索；
- LLM 自由生成营养数值、训练剂量、风险结论、来源 URL 或动作禁忌；
- 伤病康复、孕产、医疗诊断、竞技训练、自动加重、姿势识别；
- 餐食/动作资源替换和拖拽后自动调用 LLM 重生成整周计划；
- 复杂偏好衰减、高级用户画像、完整反馈管理页；
- 大规模 Prompt 评测、真实用户安全运营和灾备演练。

## 3. 产品边界

- 一般健康信息面向 18 岁以上成年人。
- 具体入门训练计划只服务 18-64 岁、无明显症状、非医疗康复场景的一般健康用户。
- 65 岁以上、孕产、当前伤病、康复、胸痛/眩晕/活动相关疼痛、进食障碍和需要医疗干预的慢性病请求，不进入具体计划组合器，可返回固定提示或一般信息。
- 所有数值标记为估算，不是医疗处方；所有动作媒体标记为示意，不保证动作安全。

## 4. 总体架构

```text
HTTP Controller
  -> HealthOrchestrator
     -> SessionExecutionGuard
     -> IdentityProvider / requestId 幂等
     -> AgentContractModule -> AgentInvoker
        -> AgentScopeInvoker（真实 DashScope）
        -> FixtureAgentInvoker（测试/离线演示）
     -> IntentAgent -> Java 意图/槽位/风险校验
     -> ClarifyRuleService -> ClarifyAgent（仅生成追问文本）
     -> Domain Router
        -> MealModule：结构化过滤 + hybrid RAG + 排序
        -> ExerciseModule：资源筛选 + plan_ready 判断
        -> RoutineModule：作息事实查询
     -> WeeklyPlanComposer：确定性组合和校验
     -> RecommendResponseAgent / PlanResponseAgent：解释已确认结果
     -> RiskRuleService：候选前、组合时、输出后三层校验
     -> Persist + Trace
```

### Agent 责任

只保留四类 Agent：

| Agent | 输入 | 输出 | 不负责 |
|---|---|---|---|
| `IntentAgent` | 用户话、近期摘要、合法槽位选项、已知状态 | `domain/task/riskFlags/slots/preferenceSignals/confidence` | 不写库、不决定风险、不生成答案 |
| `ClarifyAgent` | Java 已计算的缺失字段 | 一句追问文本 | 不决定是否需要追问；失败时模板即为完整功能 |
| `RecommendResponseAgent` | 已排序候选资源/事实 | 资源 ID、理由、`speechText` | 不新增候选、不计算数值 |
| `PlanResponseAgent` | 已校验计划片段和来源 | 计划解释文本 | 不改计划、不改剂量、不改风险结论 |

不新增 RiskAgent、RankingAgent、PlanComposerAgent 或工具调用 Agent。所有 Agent 一次调用，失败立即确定性降级，不自动重试。

`EvaluationJudgeAgent` 只用于离线补充观察，不进入在线请求主流程，也不替代固定答案、Schema 校验和确定性断言。项目对外应称为“确定性编排的多角色 Agent 工作流”，不得描述为多个自治 Agent 自主规划或调用工具。

### Agent 运行接口

外部模型依赖的 seam 使用一个小型 `AgentInvoker` 接口，业务模块不得直接依赖 `ReActAgent`。至少提供：

- `AgentScopeInvoker`：调用 AgentScope/DashScope；
- `FixtureAgentInvoker`：在自动化测试和离线演示中返回版本化固定结果。

内部 `AgentContractModule` 负责角色 Prompt、输入/输出 DTO、`contractVersion`、`promptVersion`、超时、JSON 解析、枚举/必填字段校验、候选 ID 白名单校验、Trace 和降级。失败统一分类为 `TIMEOUT`、`UPSTREAM_UNAVAILABLE`、`INVALID_JSON`、`SCHEMA_VIOLATION`、`CANDIDATE_VIOLATION`、`MISSING_CONFIG`，并映射到确定性结果；不得用空 catch 丢失失败原因。

Trace 至少记录 Agent 角色、模型、Prompt/契约版本、耗时、解析状态、候选 ID、最终引用 ID 和 `fallbackReason`。健康档案等输入只保留必要摘要或脱敏值。

## 5. 领域模型

### 5.1 正交意图

```text
domain: MEAL | EXERCISE | ROUTINE | COMPOSITE
task:   CHAT | BROWSE | RECOMMEND | PLAN | ADJUST
phase:  START | CLARIFY | RETRIEVE | COMPOSE | RESPOND | PERSIST | BLOCKED
risk:   NORMAL | ADVISORY | BLOCK_PLAN
```

`SourceMode` 只属于旧餐食兼容层，不进入通用健康意图模型。`IntentAgent` 返回的 `riskFlags` 只是待 Java 规则确认的风险信号；最终风险等级、候选过滤和计划拒绝权只属于 Java。

### 5.2 类型化资源

首版资源类型为 `MEAL` 和 `EXERCISE`。作息事实使用结构化事实标识；周计划项目引用底层餐食或动作，不作为偏好资源类型。动作只要通过基础字段、来源和媒体状态检查即可浏览与单次推荐；`plan_ready` 仅作为自动周计划的资格条件。

统一资源引用：

```json
{
  "resourceType": "EXERCISE",
  "resourceId": 123,
  "name": "俯卧撑",
  "source": {"type": "DATASET", "name": "Gym visual"},
  "eligibility": {"planReady": true}
}
```

### 5.3 偏好反馈

动作包括 `FAVORITE`、`UNFAVORITE`、`LIKE`、`DISLIKE`、`ADOPT`。统一写入扩展后的 `recommend_feedback`：

- `DISLIKE`：同一资源在当前候选集中硬过滤；
- `FAVORITE`、`LIKE`、`ADOPT`：参与确定性重排；
- 简单浏览不形成反馈事件；
- 最近 100 条事件参与计算；
- 无历史反馈时使用原有结构化排序；
- 后续正向操作可以覆盖同一资源的不喜欢状态。

## 6. API 契约

### 6.1 统一聊天

`POST /api/v1/health/chat`

请求：

```json
{
  "sessionId": "optional-session-id",
  "requestId": "client-generated-id",
  "message": "我想安排周末的轻量训练",
  "context": {}
}
```

响应：

```json
{
  "sessionId": "session-id",
  "traceId": "trace-id",
  "responseType": "ANSWER",
  "domain": "EXERCISE",
  "task": "RECOMMEND",
  "riskFlags": [],
  "phase": "RESPOND",
  "speechText": "...",
  "displayBlocks": [],
  "nextAction": "WAIT_USER",
  "clarifyQuestion": null,
  "missingSlots": []
}
```

同一匿名身份下，同一 `sessionId + requestId` 重复提交直接返回已保存结果。

### 6.2 资源与反馈

```text
GET  /api/v1/health/meals?page=1&size=20
GET  /api/v1/health/exercises?page=1&size=20
GET  /api/v1/health/plans
POST /api/v1/health/feedback
```

反馈请求：

```json
{
  "resourceType": "EXERCISE",
  "resourceId": 123,
  "action": "FAVORITE",
  "sessionId": "optional",
  "planId": "optional",
  "planItemId": "optional"
}
```

资源列表采用 `page/size`，`size` 最大 50。不引入 cursor、复杂查询 DSL 或 GraphQL。

### 6.3 健康档案和计划

```text
GET  /api/v1/health/profile
PUT  /api/v1/health/profile
POST /api/v1/health/plans/drafts
GET  /api/v1/health/plans/{planId}
POST /api/v1/health/plans/{planId}/activate
POST /api/v1/health/plans/{planId}/edit
PATCH /api/v1/health/plans/{planId}/items/{itemId}
```

ACTIVE 计划编辑先创建新的 DRAFT。PATCH 只允许修改已有项目的日期、时间和备注；不允许修改资源营养、训练剂量和作息规则。

### 6.4 旧饮食兼容

现有接口继续工作：

```text
POST /api/v1/diet/chat
GET  /api/v1/diet/meals/**
POST /api/v1/diet/feedback
```

旧聊天响应继续返回餐食 DTO；旧 `itemId` 反馈由适配层补为 `resourceType=MEAL`。旧七槽位和 `diet_sessions` JSON 只在兼容层使用，不扩展为健康万能对象。

### 6.5 错误

新健康接口统一返回：

```json
{
  "code": "RISK_BLOCKED",
  "message": "当前情况不适合生成具体计划",
  "requestId": "request-id",
  "traceId": "trace-id"
}
```

旧接口保留原始响应格式。新接口至少区分参数错误、身份无效、资源不存在、风险拒绝、版本/幂等冲突和服务异常。

## 7. 数据模型与迁移

### 7.1 旧表

保留 `diet_messages`、`diet_sessions`、`diet_slot_option`、`diet_request_trace`、`meal_item` 和 `recommend_feedback`。当前 dump 作为 `V1__legacy_baseline`，不在新迁移中重复执行 destructive dump。

### 7.2 新表和扩展

```text
health_profile
health_profile_version
exercise_item
routine_fact
weekly_plan
weekly_plan_version
weekly_plan_item
meal_item_embedding
```

`meal_item` 增加营养、来源、媒体和审核字段；`exercise_item` 增加来源、媒体、难度、动作模式、风险标签、替代关系、审核状态和 `plan_ready`；`routine_fact` 保存适用范围、事实值、来源和版本。

`health_profile` 保存当前档案，`health_profile_version` 保存生成快照。`weekly_plan_version` 保存档案版本、规则版本、来源会话和事实来源；`weekly_plan_item` 保存 `resourceType/resourceId`、本地日期、开始/结束时间、时区、备注和计划参数。

`recommend_feedback` 增加 `resource_type`、`resource_id`、`plan_id`、`plan_item_id`、`source`；旧行回填 `resource_type=MEAL`。

`diet_request_trace` 增加 `request_id` 和保存响应 JSON，通过 `user_id + session_id + request_id` 唯一约束实现幂等。Trace 额外记录 Agent、Prompt、规则和降级信息。

### 7.3 迁移和导入顺序

```text
V1 旧饮食基线
-> V2 健康领域 DDL
-> V3 字典与作息事实 seed
-> 餐食/动作 ETL
-> Embedding 生成
-> 索引和验收
```

Seed 必须幂等。Embedding 失败不能阻塞结构化资源上线。正式资源导入前执行一次数据库备份；生产问题使用前向修复或恢复备份，不执行破坏性自动回滚。

当前资源状态：餐食 1,000 条只是离线候选，尚未完成中文映射、过敏原和份量口径核验；动作数据集来源已核验，但仓库没有动作文件、导入脚本和 `plan_ready` 元数据。Agent 垂直闭环可先使用版本化种子资源，不能将其冒充正式数据；完整 MVP 只要求可重建的审核子集，不要求首版导入全部候选。

建议完整 MVP 使用 100-300 条审核餐食、20-40 个 `plan_ready` 动作和 10-20 条作息事实。数量不是验收标准。没有明确再分发许可的媒体必须进入稳定无图状态，但不阻塞文本资源入库、推荐和 RAG 评估。

## 8. 确定性计算与计划规则

### 8.1 能量估算

必填档案：年龄、身高、体重、活动水平、主要目标。生理性别、时区、作息、训练经验、器材、禁忌和伤病为选填。

Mifflin-St Jeor：

```text
男性：10W + 6.25H - 5A + 5
女性：10W + 6.25H - 5A - 161
```

`W` 为 kg，`H` 为 cm，`A` 为年龄。活动系数只使用 `1.2 / 1.375 / 1.55`。目标调整：维持 `±5%`、减脂 `-5%~-15%`、增重 `+5%~+10%`。结果四舍五入到 50 kcal，标记为估算值。首版只计算每日能量区间，不计算复杂宏量营养。

生理性别缺失时计算两种结果形成更宽区间，不由 LLM 补值。安全上下限和风险拒绝使用 25 号规则契约。

### 8.2 周计划不变量

- 使用用户时区，缺省 `Asia/Shanghai`；一周为本地周一至周日；
- 同一匿名用户只能有一份 ACTIVE 计划；
- ACTIVE 版本不可原地修改；
- 作息和训练时间不得重叠；
- 同一主要训练部位默认不安排连续两天；
- 餐食校验时间窗口和日能量区间，不强制复杂餐训间隔；
- DRAFT 可以保存，硬错误不能保存/激活，警告可保存但不能激活；
- 未经确定性校验的计划不得持久化或激活；
- 健康档案变化不静默重算已激活计划，只标记档案版本较旧。

### 8.3 结果分类

```text
HARD_ERROR -> 拒绝保存或激活
WARNING    -> 可保存 DRAFT，不可激活
OK         -> 继续流程
DEGRADED   -> 使用确定性模板或返回信息不足
```

## 9. 风险和降级

风险等级为 `NORMAL`、`ADVISORY`、`BLOCK_PLAN`，多规则取最高等级。候选前过滤过敏/禁忌，计划组合校验能量、日程、资源资格和训练安排，LLM 输出后校验结构、资源 ID、数值、来源和风险文案。

首版拒绝具体计划的情况包括当前明显症状、伤病/术后/康复、孕产、进食障碍、需要医疗干预的慢病、诊断/治疗/药物请求、极端节食/快速减重、未满 18 岁，以及 65 岁以上具体训练计划请求。所有文案固定在 Java 模板中。

LLM 非法 JSON、字段越界、超时、无 API key 和服务异常均一次调用后立即降级：意图走关键词/默认澄清，澄清走模板，响应走模板理由，计划保留已校验结果并使用模板说明，风险使用固定提示。Embedding 失败回退结构化餐食检索。

## 10. 前端规格

生产前端迁移到独立 `frontend/`，不保留 `static/` 双副本。保留 vanilla hash 路由和原生 ES Modules：

```text
frontend/
  index.html
  assets/js/main.js
  assets/js/router.js
  assets/js/api.js
  assets/js/store.js
  assets/js/ui/detail-drawer.js
  assets/js/ui/resource-card.js
  assets/js/ui/feedback-control.js
  assets/js/ui/media-state.js
  assets/js/ui/toast.js
  assets/js/pages/chat.js
  assets/js/pages/meals.js
  assets/js/pages/exercises.js
  assets/js/pages/plans.js
  assets/js/admin/traces.js
  assets/js/admin/evaluations.js
  assets/css/app.css
```

页面包含聊天、餐食、动作和计划。餐食、动作、对话推荐和计划项目统一使用“详情抽屉”；桌面端右侧打开，移动端占满屏幕。列表统一有加载、空数据、接口失败重试和媒体失败占位。收藏乐观更新，失败回滚；用户导航不展示 admin，生产由 `ADMIN_TOKEN` 保护。

## 11. 身份和部署

- Nginx `/` 托管前端，`/api/**` 反代 Spring Boot；同源，不启用 CORS；
- HMAC 签名匿名 Cookie：`HttpOnly`、生产 `Secure`、`SameSite=Lax`；
- 写操作校验 `Origin`；不引入完整 Spring Security CSRF、JWT 或 RBAC；
- `DASHSCOPE_API_KEY`、数据库凭证、session secret 和 admin token 只从 prod 环境注入；
- prod 缺少必要配置时启动失败，dev 可以使用本地占位；
- 单实例 Nginx + Spring Boot + MySQL Compose；另一个项目使用独立数据库和网络；
- 提供 `/actuator/health` 或等价健康接口；CI 使用 Java 21，执行编译和核心测试；
- Flyway 迁移前执行 `mysqldump`，失败回退应用镜像或前向修复。

## 12. 验收矩阵

### 自动化

- `AgentInvoker` 两类适配器、角色输入/输出 Schema、候选越界和失败分类；
- 多品类意图、槽位、明示偏好和编排路由；
- 量化目标、时间冲突、资源引用、版本和 DRAFT/ACTIVE 不变量；
- 三档风险和确定性降级；
- 类型化反馈、旧饮食兼容、requestId 幂等；
- hybrid 与 structured-only 的 `Recall@3`、硬约束命中率和 Embedding 降级。

固定 Agent 场景至少覆盖三品类成功路由、缺失槽位后继续会话、风险拒绝、非法 JSON、未知枚举、候选越界、超时、无 API key 和候选为空。固定夹具测试用于稳定回归；真实 DashScope 冒烟用于证明集成，两者不能互相替代。

### 接口/部署冒烟

- 旧 `/api/v1/diet/**` 仍可用；
- 新聊天、资源、反馈、档案、计划和健康检查可调用；
- 非法 JSON、API key 缺失、Embedding 失败、候选为空、重复 requestId、Cookie 篡改、admin 未授权和 MySQL 不可用有固定结果；
- Java 21 编译和 prod 配置缺失失败可复现。

### 浏览器

- 桌面：聊天详情、动作筛选/收藏、计划 DRAFT 编辑/激活；
- 移动端 `390×844`：无横向溢出，详情抽屉和计划操作可用；
- 媒体 404、无图、慢接口、空数据不破坏布局；
- 详情显示来源、估算标记、动作署名和计划资格。

### RAG 判定

硬约束命中率必须 100%。不设牵强的绝对 Recall 门槛；hybrid 无可复现提升时不在简历和项目说明中宣称 RAG 带来效果提升。

## 13. 实施顺序

```text
31 基础设施与旧饮食兼容
  -> 32 Agent 运行接口与健康聊天垂直闭环
  -> 33 审核资源、浏览 API 与餐食 RAG
  -> 34 健康档案、计划与风险校验
  -> 35 前端模块和用户页面
  -> 36 核心验收、部署和运行手册
```

31-32 号通过后达到“Agent 主流程可演示”门槛，31-36 号全部通过后达到“完整健康 MVP”门槛。每张实现票必须在本票内补齐相应自动化或冒烟，不得把测试全部推迟到 36 号；发现需要新的领域取舍时，暂停实现并新建决策票，不在实现票中临时发明规则。

## 14. 决策追溯

核心决策来源：03（动作原型）、04/05/08/09/10/12/13/14/15/16/17/18/19/20/21（原有设计）、22（API）、23（Schema）、24（量化/计划）、25（风险）、26（Agent/降级）、27（身份/部署）、28（验收）、29（Java 21）、30（资源审计）。
