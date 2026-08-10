# 26 Agent、Embedding 与确定性降级契约

- Type: grilling
- Status: resolved
- Blocked by: 04, 10, 15, 19, 20

## Question

冻结各类 Agent 和检索适配器的输入/输出契约：跨品类意图、澄清、餐食/动作/作息响应、周计划解释、embedding 检索分别返回哪些结构化字段、资源 ID、来源和版本？

还需决定 schema 校验失败、JSON 非法、超时、无 API key、embedding 不可用、候选为空和规则拒绝时的重试次数、超时预算、确定性降级结果、用户文案及 Trace 记录。LLM 只能解释候选、事实、计算结果和通过校验的计划片段；这个边界要能被 adapter、Prompt 版本和自动化测试直接验证。

## Answer

2026-08-10 已按“沿用原项目 Agent 设计的最小实现”确认，并在面试适用性复核后补充可测试的外部 seam：

- 保留四类 Agent 职责：`IntentAgent` 识别 `domain/task/riskFlags/slots/preferenceSignals`；`ClarifyAgent` 生成 Java 已判定缺失字段的追问；`RecommendResponseAgent` 解释已排序的餐食/动作/事实候选；`PlanResponseAgent` 解释已通过校验的周计划。`ClarifyAgent` 只是措辞增强，模板追问必须独立完成主流程。
- 不新增 `RiskAgent`、`RankingAgent`、`PlanComposerAgent` 或工具调用 Agent。槽位合并、澄清判断、候选召回、排序、计划组合、风险校验和降级都由 Java 确定性服务完成。
- 外部模型依赖定义小型 `AgentInvoker` 接口，至少提供真实 `AgentScopeInvoker` 和测试/离线演示用 `FixtureAgentInvoker`。业务模块和测试不直接依赖 `AgentFactory` 或 `ReActAgent`。
- 内部 `AgentContractModule` 统一处理 Prompt、`contractVersion`、`promptVersion`、输入/输出 DTO、超时、JSON 解析、Schema/枚举/候选 ID 校验、Trace 和降级。Agent 输出使用严格 JSON；资源只能引用输入候选中的 `resourceType/resourceId`。Agent 不生成营养数值、训练剂量、风险结论或来源 URL。
- 保持现有“一次调用、失败立即降级”策略，不增加自动重试编排。意图失败使用关键词/默认澄清，澄清失败使用 Java 模板，推荐失败使用模板理由和固定文案，计划解释失败保留已校验计划并使用模板，风险拒绝使用固定文案。
- Embedding 只用于餐食候选召回；Embedding 不可用时回退结构化检索。动作、作息和计划不接入向量检索。候选为空时返回空状态，不让 Agent 编造候选。
- JSON 非法、字段越界、超时、API key 缺失和服务异常都视为 Agent 失败，分别使用 `INVALID_JSON`、`SCHEMA_VIOLATION`、`CANDIDATE_VIOLATION`、`TIMEOUT`、`MISSING_CONFIG`、`UPSTREAM_UNAVAILABLE` 等类型化原因。失败原因和降级类型写入现有 Trace，不阻断旧饮食链路的可用性。
- `EvaluationJudgeAgent` 只做离线补充观察，不进入在线主流程，也不能替代固定答案、Schema 校验和确定性断言。项目统一描述为“确定性编排的多角色 Agent 工作流”，不宣称自治规划和自主工具调用。

### Agent 输入输出边界

```text
用户输入
  -> IntentAgent: 结构化意图、槽位、明示偏好、置信度
  -> Java 槽位/风险/候选/计划规则
  -> ResponseAgent: 候选或已校验计划
  -> 结构化解释、资源 ID、固定文案
```

### 最小验收范围

1. `AgentInvoker` 契约测试同时覆盖 AgentScope 和固定夹具两个适配器；固定输入输出测试覆盖合法 JSON、非法 JSON、未知枚举、候选越界 ID 和缺失字段。
2. 降级测试覆盖 API key 缺失、超时、调用异常、候选为空、Embedding 失败和风险拒绝，并断言 `fallbackReason`。
3. 不做真实 LLM 压测、大规模 Prompt 评测、自动重试策略、工具调用评估或模型安全研究。
4. 任何 Agent 解释都必须在输出前后经过资源引用和规则校验；模型文本不能成为事实源。
5. Trace 记录角色、模型、Prompt/契约版本、耗时、解析状态、候选 ID、引用 ID 和降级原因；健康档案只记录必要摘要或脱敏值。
