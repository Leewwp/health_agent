# 05 — Qdrant 可选 Hybrid RAG 真实证据

Status: resolved

GitHub: [#88](https://github.com/Leewwp/health_agent/issues/88)

**What to build:** 在不让 Qdrant 阻塞核心演示的前提下，完成一次可复核的真实索引、Hybrid 餐食查询和评估证据，使简历中的 Qdrant/RAG 技术点真实可讲。

**Blocked by:** None — can start immediately.

- [x] 保持餐食 RAG 边界：MySQL Structured 与 Qdrant Vector 融合，动作和作息不扩展向量检索。
- [x] Qdrant 未配置、不可用或超时时稳定降级 Structured Retrieval，聊天、计划、浏览和健康检查仍可用。
- [x] 使用当前 60 条 `querySetVersion=1.1.0`、当前 embedding 身份复用 collection，索引审核餐食并执行 Hybrid 查询。
- [x] 证据记录 Structured、Vector、Fused 候选数量、最终结果、向量存储状态、降级原因和延迟。
- [x] 运行 Qdrant 集成门控和分层评估，报告 Recall@3、MRR、NDCG@3、Precision@3、P95 与降级分布。
- [x] `data/reports/rag_evaluation.json` 已替换为当前真实 Hybrid 口径，并区分 Hybrid、Structured 对照和故障 fallback；没有把微小收益写成显著提升。
- [x] 更新发布证据和评估说明，解释 Qdrant 可选、Structured 硬约束和故障降级路径。

## Answer

2026-08-19 在本地 MySQL + Qdrant 1.17.0 完成真实运行：295/295 条向量索引成功，60/60 条查询为 `REAL_HYBRID`，降级 0；Structured 与 Hybrid Recall@3 均为 0.35075，硬约束命中率均为 1.0，Hybrid P95 为 159.407 ms。结果写入 `data/reports/rag_evaluation.json`，提交于 `00c36b0`；报告元数据、发布证据和评估说明已同步。
