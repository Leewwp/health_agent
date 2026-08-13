# P2：扩充分层 RAG 评测并完成融合消融

- Type: task
- Status: open
- Triage: ready-for-agent
- Priority: P2
- Estimate: 2-3 天
- Blocked by: 01
- GitHub: https://github.com/Leewwp/health_agent/issues/77

## Question

如何把当前 10 条餐食查询扩充为足以判断长尾语义价值的分层评测集，并通过融合权重与查询文本消融给出可复现、不过度宣传的结论？

## Scope

- 将固定查询集扩充到约 50-100 条，至少分为精确标签、自然语言、长尾表达、同义词、排除项和过敏原；
- 增加 MRR、NDCG@3、Precision@3、P95 延迟和降级分布，保留硬约束命中率；
- 对结构化、槽位拼接 Embedding、用户原话 Embedding，以及 `0.3/0.7`、`0.5/0.5`、`0.7/0.3` 融合权重做消融；
- 固定数据、模型、向量维度、索引版本和运行参数，避免不可复现比较；
- 同步 `data/reports/rag_evaluation.json`、评估文档、README 和面试指导中的最新数据口径；
- 若整体提升不显著，按真实结果报告长尾子集、硬约束和降级价值。

## Done when

评测集与运行方式可重复，报告包含分层指标和消融对比，文档不存在旧报告口径冲突；所有对外表述均可由提交的报告直接验证。
