# 05 — Qdrant 可选 Hybrid RAG 真实证据

Status: ready-for-agent

GitHub: [#88](https://github.com/Leewwp/health_agent/issues/88)

**What to build:** 在不让 Qdrant 阻塞核心演示的前提下，完成一次可复核的真实索引、Hybrid 餐食查询和评估证据，使简历中的 Qdrant/RAG 技术点真实可讲。

**Blocked by:** None — can start immediately.

- [ ] 保持餐食 RAG 边界：MySQL Structured 与 Qdrant Vector 融合，动作和作息不扩展向量检索。
- [ ] Qdrant 未配置、不可用或超时时稳定降级 Structured Retrieval，聊天、计划、浏览和健康检查仍可用。
- [ ] 以现有 2026-08-12 的 295/295 Qdrant 实跑作为历史基线，不重复建设 VectorStore；使用当前 60 条 `querySetVersion=1.1.0`、当前 embedding 身份真实创建/复用 collection、索引审核餐食并执行 Hybrid 查询。
- [ ] Trace 或证据记录 Structured、Vector、Fused 候选数量、最终结果、向量存储状态、降级原因和延迟。
- [ ] 运行现有 Qdrant 集成门控和分层评估，报告 Recall@3、MRR、NDCG@3、Precision@3、P95 与降级分布。
- [ ] 替换当前 `data/reports/rag_evaluation.json` 的 `degradedRun=true` 口径，报告明确区分本次真实 Hybrid、structured 对照与故障 fallback；收益微小时不写“显著提升”，历史 10 条查询数字不冒充当前 60 条口径。
- [ ] 更新发布证据和面试说明，能够解释为何 Qdrant 可选而 Structured 是硬约束及故障降级路径。
