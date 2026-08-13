# P1：冻结统一健康链路评估与标注契约

- Type: grilling
- Status: resolved
- Triage: ready-for-human
- Priority: P1
- Estimate: 0.5 天
- GitHub: https://github.com/Leewwp/health_agent/issues/75

## Question

统一健康评估应采用什么标注模型、Trace 事件读取规则、指标定义和报告结构，才能覆盖三品类而不破坏旧饮食评估兼容性，并让每个简历数字都可从固定样本和报告复现？

## Decisions required

- `domainAccuracy`、`taskAccuracy`、分领域槽位指标的标注字段与空值语义；
- 风险阻断、澄清必要性、候选引用合规和计划规则通过率的计算口径；
- fallback 分类分布、P50/P95 延迟和用户反馈采纳率的聚合边界；
- 旧 `expected_intent/expected_slots/expected_clarify_action` 的兼容或迁移策略；
- 固定评测集的最小规模、分层方式、报告格式和版本身份；
- 哪些指标进入简历，哪些只用于诊断。

## Done when

票内形成可直接交给实现任务的契约：字段、事件、公式、缺失值、版本、兼容策略和验收样例均明确，无需实现者继续猜测产品口径。

## Answer

### 1. 目标与运行模式

- 新契约命名为 `health-eval-v2`，服务于两个互不混算的模式：
  - `BENCHMARK`：仓库内版本化固定样本，用于可复现回归与面试证据；
  - `TRACE_AUDIT`：抽样读取真实 Trace，用于诊断，不作为简历数字来源。
- 最小评估单位是一轮请求对应的一条 Trace；多轮样本以 `caseId + turnIndex` 关联。
- 不将规则指标、LLM Judge 与用户反馈混成对外总分。Judge 只作单列观察项；简历指标必须来自版本化 BENCHMARK 报告。

### 2. 数据集、标注与兼容

- BENCHMARK 使用仓库内 JSONL，每条至少包含 `datasetId`、`datasetVersion`、`caseId`、`turnIndex`、`caseType`、输入、初始上下文和 gold label。
- TRACE_AUDIT 保留旧 `expected_intent`、`expected_slots`、`expected_clarify_action` 原义；实现时新增可空 `evaluation_schema_version varchar(32)` 与 `expected_health_json JSON`。不迁移、不改写、不伪造旧健康标注。
- `expected_health_json` 只保存需要人工判断的事实：

```json
{
  "schemaVersion": "health-eval-v2",
  "expectedDomain": "MEAL",
  "expectedTask": "RECOMMEND",
  "expectedSlots": {
    "mealTime": ["晚餐"],
    "taste": ["清淡"]
  },
  "expectedRiskLevel": "NORMAL",
  "expectedResponseType": "ANSWER",
  "expectedMissingSlots": []
}
```

- 字段缺失表示该样本不评估对应指标；显式空对象/数组表示 gold 明确应为空。
- 第一版不支持多个可接受答案。无法唯一判断的样本标记 `excludedReason=AMBIGUOUS_INPUT`，保留诊断但不进入准确率分母。
- 只有一名标注者：BENCHMARK 采用间隔两遍复核并记录 `labeledAt`、`reviewedAt`、`reviewStatus=REVIEWED`；不声称多人标注或标注者一致性。TRACE_AUDIT 单次标注即可。

### 3. 指标与分母

- `domainAccuracy = domain 完全匹配数 / 有 expectedDomain 的有效样本数`。
- `taskAccuracy = task 完全匹配数 / 有 expectedTask 的有效样本数`。
- `domainTaskExactMatch` 要求两者同时完全匹配；`COMPOSITE` 是正式领域值。
- 槽位同时输出：
  - `slotExactMatch`：全部已标注槽位和值完全一致；
  - `slotMicroPrecision/Recall/F1`：将 `(slotName, normalizedValue)` 作为事实统计；
  - 总体及分品类 F1。gold 未提供 `expectedSlots` 时不纳入；`expectedSlots={}` 表示系统不应产生槽位。
- 风险按 `NORMAL / ADVISORY / BLOCK_PLAN` 三级分类，输出 accuracy、各级 precision/recall/F1 和混淆矩阵，重点单列 `BLOCK_PLAN Recall` 及其样本数。
- 澄清输出：
  - `clarifyDecisionAccuracy`：是否应澄清；
  - `missingSlotF1`：仅 gold 要求澄清的样本比较缺失槽位集合；
  - gold 为 `BLOCKED` 的样本不进入澄清分母；自然语言问法只允许 Judge 单列观察。
- `candidateCitationCompliance = 最终展示 ID 全部属于本轮候选集的样本数 / 产生资源卡片的有效样本数`，明细记录违规 ID；不解析自然语言实体。
- 计划复用 `PlanValidationService`，只输出 `planValidationPassRate`、`planHardErrorCountByRule` 和 `planRulesVersion`，不复制规则或生成计划质量总分。
- fallback 使用互斥主分类：`NONE`、`INTENT_RULE_FALLBACK`、`RESPONSE_TEMPLATE_FALLBACK`、`EMBEDDING_UNAVAILABLE`、`VECTOR_STORE_UNAVAILABLE`、`NO_VECTOR_HITS`、`REQUEST_FAILED`；同一 Trace 多原因时主类取最严重项、明细保留全部原因。风险阻断、正常澄清和无候选不是 fallback。
- 延迟只使用请求总时长，分别报告正常响应与 `REQUEST_FAILED` 的 P50、P95、max 和有效样本数；不建设性能平台或宣称生产 SLA。
- 用户反馈只使用 #74 的精确 trace 归因：
  - `adoptionRate = ADOPT / 具有精确 traceId 归因的有效推荐反馈`；
  - `positiveRate = (LIKE + ADOPT) / 精确归因的 LIKE/DISLIKE/ADOPT`；
  - 单列 `exactAttributionCount` 与 `legacyFallbackCount`；
  - FAVORITE/UNFAVORITE 不进入满意度，旧 session 回退不进入比例分母。
- 所有报告必须同时给出总样本数、状态分布和每个指标的有效分母。缺少 gold 或结构化事实时指标为 `null`，不得算 0。

### 4. Trace 事实读取

- domain/task/意图降级：`INTENT_RECOGNIZED`；
- 风险等级：`RISK_ASSESSED`；
- 最终槽位：`SLOTS_MERGED`；
- responseType/missingSlots/displayBlocks：优先 Trace 行 `response_json`，缺失时回退 `RESPONSE_READY`；
- 检索候选：`CANDIDATES_RETRIEVED`；
- 餐食检索降级：`MEAL_RETRIEVED`；
- 响应模板降级：`RESPONSE_AGENT_RESULT`；
- 请求失败与总延迟：Trace 行 `status`、`duration_ms`；
- 用户反馈：`recommend_feedback.trace_id`。

评估器不得解析自然语言文案来猜测领域、风险或澄清结果。缺少必需结构化字段时记 `MISSING_TRACE_FACT`，对应指标为 `null`。

### 5. 最小样本与执行档位

- 第一版 BENCHMARK 共 36 条：饮食 12、健身 8、作息 6、计划/综合 6、风险阻断 4。饮食至少覆盖自然语言、排除项与过敏原；其他品类覆盖正常、澄清与风险路径。扩大到 50-100 条属于 #77，不阻塞 #73。
- 同一 runner 提供两个分开报告的档位：
  - `DETERMINISTIC_FIXTURE`：固定 Agent/resource fixture 与规则，无 API key、MySQL、Qdrant 也可运行，作为普通回归；
  - `LIVE_MODEL`：记录明确模型、审核 DB 与向量环境，生成真实面试证据，不作为普通 CI 门禁。
- 计划样本使用 `caseType=PLAN_VALIDATION`，以固定 `HealthProfile + PlanItemDraft + ResourceCatalog` 直接调用 `PlanValidationService`，不强行从聊天 Trace 推断。

### 6. 报告、版本与验收

- 版本化 JSON 是唯一机器事实来源；Markdown 只做摘要和解释，不维护另一套数字。
- JSON 至少记录 `schemaVersion`、dataset ID/version、run mode、Git commit、resource provider mode/version、rules version、main/embedding model、使用 RAG 时的向量索引身份、样本总数、各指标有效分母、指标、分组指标、fallback、延迟、反馈和 case 明细。
- 最小手算验收包含：餐食正常推荐（domain/task/slot/candidate）、健身风险阻断（`BLOCK_PLAN Recall=1`）、正确澄清及 missing slots、无 HARD_ERROR 的计划校验、精确 trace ADOPT 进入 adoption 分母，以及无 trace 旧反馈只进入 `legacyFallbackCount`。

### 7. 明确非目标

- 不做独立评估微服务、复杂标注平台、在线持续采样、多标注者一致性、置信区间平台、重复显著性实验、模型横向比较或细粒度阶段性能平台。
- 简历可以压缩表达和突出真实分层结果，但样本量、准确率、提升率等可验证数字必须能由提交的报告直接复现。
