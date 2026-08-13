# P1：实现三品类统一健康评估与可复现报告

- Type: task
- Status: open
- Triage: ready-for-agent
- Priority: P1
- Estimate: 2-3 天
- Blocked by: 03, 04
- GitHub: https://github.com/Leewwp/health_agent/issues/73

## Question

如何在已冻结的评估契约和精确反馈归因基础上，实现覆盖饮食、健身、作息、风险与计划的离线评估，并保留旧饮食评估的兼容入口？

## Scope

- 按决策票实现健康 Trace 解析、标注字段和指标聚合；
- 至少输出 domain/task、分领域槽位、风险、澄清、候选引用、计划规则、fallback、延迟和反馈指标；
- 精确消费 traceId 反馈，对旧数据回退结果显式标识为近似归因；
- 提供固定样本/fixture 和可重复运行的报告输出；
- 不让 LLM Judge 替代确定性指标，Judge 仅保留为可选观察维度；
- 更新后台评估展示或文档时保持旧接口兼容，并完成相应浏览器验收。

## Done when

固定健康样本可生成版本化报告，核心指标有手算期望值测试；旧饮食评估不回归；报告能明确区分精确反馈与兼容回退，且简历引用数字可追溯到报告版本。
