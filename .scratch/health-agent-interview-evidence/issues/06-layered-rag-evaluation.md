# P2：扩充分层 RAG 评测并完成融合消融

- Type: task
- Status: resolved
- Triage: ready-for-human
- Priority: P2
- Estimate: 2-3 天
- Blocked by: local 01 ([GitHub #76](https://github.com/Leewwp/health_agent/issues/76))
- GitHub: https://github.com/Leewwp/health_agent/issues/77

## Question

如何把当前 10 条餐食查询扩充为足以判断长尾语义价值的分层评测集，并通过融合权重与查询文本消融给出可复现、不过度宣传的结论？

## Scope

- 将固定查询集从 10 条扩充到 60 条：精确标签、自然语言、长尾表达、同义词、排除项和过敏原六层各 10 条；每条使用稳定 `sourceId` 标注二元相关集合并记录 `stratum`，足以计算 MRR/NDCG@3/Precision@3；
- 增加 MRR、NDCG@3、Precision@3、P95 延迟和降级分布，保留硬约束命中率；
- 对结构化、槽位拼接 Embedding、用户原话 Embedding，以及 `0.3/0.7`、`0.5/0.5`、`0.7/0.3` 融合权重做消融；
- 权重通过 evaluator/检索器测试接缝显式注入，生产 `HybridMealRetriever` 默认继续保持 `0.5/0.5`；评测结果本身不静默修改线上权重，若要采用新默认值需另行形成代码决策；
- 固定数据、模型、向量维度、索引版本和运行参数，避免不可复现比较；
- 同步 `data/reports/rag_evaluation.json`、评估文档、README 和面试指导中的最新数据口径；
- 若整体提升不显著，按真实结果报告长尾子集、硬约束和降级价值。

## Done when

评测集与运行方式可重复，报告包含分层指标和消融对比，文档不存在旧报告口径冲突；所有对外表述均可由提交的报告直接验证。

## Evidence contract

- JSON 报告是唯一数字事实来源；`docs/research/meal-rag-evaluation.md`、README、release evidence 和面试材料只引用同一报告版本。
- `LIVE_MODEL` 报告至少记录 Git commit、query-set version、审核资源版本、embedding provider/model/version/dimension、collection、融合权重和运行时间。
- 无 API key/Qdrant 的降级运行只能证明降级正确性，不能作为 Hybrid 效果数字；对外效果只引用零降级或明确分层说明的真实运行。

## Answer

已在提交 `6374494`（报告重生成见 `9b85a42`）完成 60 条六层查询集、分层指标、降级分布及三组融合权重/查询文本消融；`data/reports/rag_evaluation.json` 为唯一数字事实来源，线上默认权重仍为 0.5。
