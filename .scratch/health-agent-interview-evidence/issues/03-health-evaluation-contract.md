# P1：冻结统一健康链路评估与标注契约

- Type: grilling
- Status: open
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
