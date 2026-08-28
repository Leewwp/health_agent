# 作息有据文档与 RAG 未来方向

Type: research
Status: resolved / ready-for-human（research-complete，本轮不实现）
Blocked by: none
Priority: P2
GitHub: https://github.com/Leewwp/health_agent/issues/107

## Goal

评估将常见作息问题的核验文档、主题索引、来源版本和可选 RAG 接入现有事实链路的工作量；本轮不实现。

## Acceptance

- 盘点已有咖啡因、睡眠、午睡和晚间训练来源，区分结构化事实与解释性文档。
- 定义来源、版本、主题、引用和失效策略，确保回答可追溯。
- 评估复用现有资源 Provider、Reader、RAG 评估和 Trace 的最小接缝。
- 给出“结构化事实优先、文档证据补充、无证据不猜测”的路由建议。

## Notes

本轮只保持当前已核验事实，例如咖啡因睡前约 6 小时和训练时段无统一最佳时间；不新增固定停止锻炼时刻。

## 研究产出（2026-08-28）

研究文档：[`research/routine-grounded-docs-future.md`](../research/routine-grounded-docs-future.md)。内容覆盖：

- 咖啡因、睡眠、午睡、晚间训练四类主题的已有来源盘点（正式库 15 条 topic 事实 + fixture 5 条，逐条带来源与 ref_id），并区分结构化事实与解释性文档；
- 来源/版本/主题/引用/失效策略设计（来源命名沿用现状、事实集版本号、关键词映射与 topic 同步版本化、ref_id 引用、撤回/复核失败删除语义）；
- 复用 `HealthResourceProvider`、Reader 边界、health-eval-v2/分层 RAG 评估与 `AgentTraceService` 的最小接缝评估（不需要向量库以外的新基础设施）；
- "结构化事实优先、文档证据补充、无证据不猜测"的路由契约；
- 明确本轮不实现：不建 `routine_doc`、不接入文档语料、不启用向量检索、不为"停止锻炼"写固定时刻。
