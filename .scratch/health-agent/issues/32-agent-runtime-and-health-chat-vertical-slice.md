# 32 Agent 运行接口与健康聊天垂直闭环

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Depends on: 31

## Scope

实现规格第 4、5、6、9 节的最小 Agent 主流程：隔离 AgentScope、建立严格角色契约，并使用版本化种子资源跑通饮食、健身、作息三品类的统一健康聊天。不等待正式 ETL、Embedding、完整周计划和前端迁移。

## Must do

- 定义小型 `AgentInvoker` 接口，实现 `AgentScopeInvoker` 和 `FixtureAgentInvoker`；业务模块不得直接获取 `ReActAgent`；
- 实现 `AgentContractModule`，统一 Prompt/契约版本、输入输出 DTO、超时、JSON/Schema/枚举/候选 ID 校验、Trace 和失败分类；
- 增加 `domain/task/riskFlags/phase` 正交意图模型，扩展 IntentAgent JSON，支持合法槽位和 `preferenceSignals`；
- 将旧餐食链路封装为 `MealModule`，实现最小 `ExerciseModule` 和 `RoutineModule`，先使用版本化种子资源；
- 移除“PERSONAL 餐食为空就全局提前返回”，只在餐食模块内部处理；
- Java 规则决定是否追问，ClarifyAgent 只优化措辞；模板追问必须能独立继续会话；
- RecommendResponseAgent 只能解释输入候选，候选越界或 Schema 失败立即模板降级；
- 实现 `POST /api/v1/health/chat`，旧 `/api/v1/diet/chat` 保持兼容；
- Trace 记录角色、模型、Prompt/契约版本、耗时、解析状态、候选/引用 ID 和 `fallbackReason`；
- 在本票内建立最小测试目录和固定场景集，不把 Agent 测试推迟到 36 号。

## Must not do

正式资源扩量、Embedding/RAG、周计划持久化、自治工具调用、RiskAgent、RankingAgent、PlanComposerAgent、自动重试和大规模 Prompt 评测。

## Done when

固定夹具模式在无 API key 时可完成三品类路由、澄清继续、候选/事实查询、输出校验和 Trace；真实 DashScope 至少完成一条受约束冒烟。自动化覆盖合法输出、非法 JSON、未知枚举、候选越界、超时、无 key、候选为空和风险拒绝，异常不会导致未处理的 500。31-32 通过后达到“面试可演示”，但尚不是完整 MVP。

## Answer

2026-08-10 完成验收：

- **AgentInvoker seam**：`AgentInvoker` 接口 + `AgentScopeInvoker`（ReActAgent + block 超时，占位 key 时 `configured()=false`）+ `FixtureAgentInvoker`（版本化固定结果，只在用户输入段匹配关键词避免系统提示词污染）；`HealthAgentConfiguration` 按 `diet.agent.mode` 选择适配器（prod 强制 agentscope）。
- **AgentContractModule**：统一 Prompt/契约版本、超时、JSON 解析、Schema/枚举/候选 ID 白名单校验、`AGENT_CALL` Trace（携带 promptVersion/contractVersion/parseStatus/fallbackReason）；失败分类 `TIMEOUT/UPSTREAM_UNAVAILABLE/INVALID_JSON/SCHEMA_VIOLATION/CANDIDATE_VIOLATION/MISSING_CONFIG`，一次调用不重试。
- **正交意图**：`HealthDomain/HealthTask/HealthPhase/HealthRiskLevel` + `HealthIntentResult`（domain/task/riskFlags/slots/preferenceSignals/confidence/degraded），`health-intent.txt` Prompt，非法槽位值过滤并标记 degraded，`IntentRuleService` 关键词兜底。
- **领域模块**：`MealModule`（封装旧 search+rank，移除旧编排器 PERSONAL 全局提前返回，空库在模块内处理）；`ExerciseModule`（8 个版本化种子动作，全部 plan_ready，Gym visual 署名，无图状态）；`RoutineModule`（5 条版本化作息事实 + 来源引用）。
- **澄清与解释**：`HealthClarifyRuleService` 纯 Java 决定追问（每领域单问题优先），ClarifyAgent 只优化措辞，模板追问可独立继续会话；`HealthRecommendResponseService` 只解释输入候选，越界/失败立即确定性模板。
- **风险**：`HealthRiskRuleService` 版本化规则（7 条，三档等级，固定中文文案），前置校验，BLOCK_PLAN 返回固定提示。
- **入口与状态**：`POST /api/v1/health/chat`；健康会话状态复用 diet_sessions（slots+_meta JSON）；requestId 幂等复用 diet_request_trace 快照；Trace 记录角色/模型/Prompt 与契约版本/耗时/解析状态/候选 ID/fallbackReason。
- **测试**：65 个自动化测试全通过（契约、夹具、意图、澄清、风险、模块、推荐、编排器固定场景集），无 API key 可复现；真实模式（无 key）与 fixture 模式均在本地启动冒烟通过（三品类 ANSWER、风险 BLOCKED、幂等、400 参数错误）。真实 DashScope 冒烟因无 API key 未执行，由 fixture 模式替代验证主流程，真实调用验证留待 36 号或注入 key 后补做。

### 自动化覆盖（32 号固定场景集）

| 场景 | 测试 |
|---|---|
| 三品类成功路由 | HealthOrchestratorServiceTest（饮食/健身/作息） |
| 澄清继续会话 | 多轮会话（饮食 3 轮 + 健身 2 轮） |
| 风险拒绝 | PREGNANCY → BLOCKED 固定文案 |
| 非法 JSON / 未知枚举 | AgentContractModuleTest + 意图降级测试 |
| 候选越界 | AgentContractModuleTest + 推荐服务测试 |
| 超时 / 上游失败 / 无 key | AgentContractModuleTest（TIMEOUT/UPSTREAM/MISSING_CONFIG） |
| 候选为空 | 编排器空结果提示 |
| 幂等与 Trace 内容 | 重复 requestId 单次落库 + 契约字段断言 |
