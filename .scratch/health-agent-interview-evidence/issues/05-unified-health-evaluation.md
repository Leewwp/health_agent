# P1：实现三品类统一健康评估与可复现报告

- Type: task
- Status: resolved
- Triage: ready-for-human
- Priority: P1
- Estimate: 3-5 天（含 36 条样本编制与单人两遍复核）
- Blocked by: none（local 04 与 local 03 均已 resolved）
- GitHub: https://github.com/Leewwp/health_agent/issues/73

## Question

如何在已冻结的评估契约和精确反馈归因基础上，实现覆盖饮食、健身、作息、风险与计划的离线评估，并保留旧饮食评估的兼容入口？

## Scope

- 按决策票实现健康 Trace 解析、标注字段和指标聚合；
- #75 是字段、公式、Trace 事件、样本分层、报告身份和非目标的唯一契约来源；本票不得重新定义另一套指标口径；
- 在 #74 的 V8 迁移落地后使用 V9 新增 `evaluation_schema_version` 与 `expected_health_json`，保留旧标注列原义；
- 至少输出 domain/task、分领域槽位、风险、澄清、候选引用、计划规则、fallback、延迟和反馈指标；
- 精确消费 traceId 反馈，对旧数据回退结果显式标识为近似归因；
- 提供固定样本/fixture 和可重复运行的报告输出；
- 不让 LLM Judge 替代确定性指标，Judge 仅保留为可选观察维度；
- 更新后台评估展示或文档时保持旧接口兼容，并完成相应浏览器验收。
- 面试向最小实现不强制新增标注平台或重做后台页面：只要旧评估 API/UI 兼容、v2 runner 能生成版本化 JSON 与 Markdown 摘要即可；只有实际修改 UI 时才进入浏览器验收。

## Done when

固定健康样本可生成版本化报告，核心指标有手算期望值测试；旧饮食评估不回归；报告能明确区分精确反馈与兼容回退，且简历引用数字可追溯到报告版本。

## Implementation order

1. V9 标注字段与旧接口兼容适配；
2. 独立的 v2 gold/Trace 解析与指标内核，先用 #75 手算样例固定公式；
3. `DETERMINISTIC_FIXTURE` runner 与 36 条 REVIEWED JSONL；
4. 版本化 JSON 报告和 Markdown 摘要；
5. `TRACE_AUDIT` 与 #74 精确/回退反馈读取；
6. 最后运行 `LIVE_MODEL` 生成可引用证据，真实模型结果不作为普通 CI 门禁。

## Answer

已在提交 `6374494`（报告重生成见 `9b85a42`）完成 V9 迁移、health-eval-v2 内核、36 条 REVIEWED fixture 与版本化报告；domain/task=1.0、slotExact=0.9375、BLOCK_PLAN Recall=1 可由 JSON 复现。
