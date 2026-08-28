# 作息有据文档与 RAG 未来方向

Type: research
Status: ready-for-agent
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
