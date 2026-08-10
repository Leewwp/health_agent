# 32 Agent 运行接口与健康聊天垂直闭环

- Type: task
- Status: open
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
