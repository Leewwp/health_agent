# 餐食 RAG 当前状态与后续任务

更新日期：2026-08-27

本文档是后续 RAG 审查和开发任务的入口。它区分已经完成的事实、可以写入简历的结论，以及尚未证明、不能直接宣传的假设。

## 当前边界

- RAG 只用于健康聊天中的餐食候选召回，不用于生成健康结论，也不用于训练计划和作息事实检索。
- MySQL 是事实真相源，保存审核状态、来源、餐次、过敏原、营养和排除关系。
- Qdrant 是可重建的语义索引，不承担最终约束判断。
- Structured 路径先执行硬约束；Hybrid 只在候选融合后再经过 MySQL 二次校验和确定性排序。
- 线上默认仍为 `diet.rag.mode=structured`。Hybrid/TwoStage 是实验路径。

## 已完成事实

### 语料与索引

- `current-corpus-v1` 已冻结，包含 295 条 `PUBLIC + APPROVED` 餐食。
- manifest 位于 `data/manifests/current-corpus-v1.json`，包含输入 CSV SHA-256、seed SQL SHA-256、sourceId 映射和每条内容的 canonical hash。
- DashScope collection：`meal_dashscope_text-embedding-v3_1024_v3-1024`，295 条，1024 维。
- MiniMax collection：`meal_minimax_embo-01_1536_minimax-1536`，295 条，1536 维。
- MiniMax `embo-01` 已完成真实 API smoke，响应 `base_resp.status_code=0`，向量维度为 1536。
- MiniMax 入库使用 `type=db`，查询使用 `type=query`；限流后可断点续跑。

### 路由与安全性

- 包含 `mealTime`、`allergen` 或有效 `excludeIds` 的查询强制走 Structured。
- 无强约束且包含“清淡、顶饱、饱腹、像妈妈做的、家常”等主观表达时，才允许进入语义实验路径。
- 已验证“不含乳糖的早餐”等混合查询不会因主观词触发 Hybrid。
- 60 条主评测和 12 条语义挑战中，Structured、Hybrid、TwoStage 硬约束违规均为 0。
- Trace 已记录 `route`、`actualRetriever`、`mode`、`degradationReason` 和检索延迟。

## 当前证据

### 60 条主评测

DashScope 和 MiniMax 使用同一查询集、同一 295 条语料和同一评测代码。结果为单轮真实运行，不是线上长期统计。

| 指标 | DashScope Structured | DashScope Hybrid | MiniMax Hybrid |
|---|---:|---:|---:|
| Recall@3 | 0.2646 | 0.2716 | 0.2776 |
| MRR | 0.9667 | 0.9500 | 0.9556 |
| NDCG@3 | 0.8547 | 0.8561 | 0.8629 |
| Precision@3 | 0.7778 | 0.7944 | 0.8056 |
| 硬约束命中率 | 1.000 | 1.000 | 1.000 |
| P95 延迟 | 9.12 ms | 247.36 ms | 479.83 ms |

原始报告：

- `data/reports/rag_evaluation.json`
- `data/reports/rag_evaluation_minimax.json`

MiniMax 相对同轮 Structured 的 Recall@3 提升 1.31 个百分点，但 P95 增加约 477ms，MRR 下降 1.11 个百分点。这个结果支持“语义召回有有限收益、成本很高”，不支持“MiniMax 显著提升生产质量”。

### 12 条语义挑战

12 条查询已人工完成 Top-5 标注。MiniMax 结果如下：

| 策略 | Recall@3 | Recall@5 | Recall@10 | 平均延迟 | 硬约束违规 |
|---|---:|---:|---:|---:|---:|
| Structured | 0.0333 | 0.0667 | 0.2167 | 4.46 ms | 0 |
| Hybrid | 0.1000 | 0.1500 | 0.2333 | 295.99 ms | 0 |
| TwoStage | 0.0333 | 0.0833 | 0.2000 | 143.58 ms | 0 |

样本量较小，且 TwoStage 汇总值混合了 7 条 Structured 和 5 条语义查询，不能直接代表语义子集收益。

## 面试可用结论

推荐表述：

> 我把 RAG 限定在餐食候选召回。MySQL 负责餐次、过敏原和排除项等事实约束，向量检索只补充“清淡、顶饱、家常”等模糊表达，最终仍由 Java 做硬约束校验和排序。通过固定语料和固定查询集对比 Structured、Hybrid 与 MiniMax，发现语义召回有小幅收益，但网络调用显著增加延迟，因此线上采用硬约束优先、Structured 默认，语义路径按查询类型路由。

不要表述为：

- “MiniMax 让召回率显著提升”；
- “Hybrid 已经替代结构化检索”；
- “RAG 保证了健康建议正确”；
- “12 条挑战集证明了生产效果”。

## 后续任务优先级

### P1：语义路径延迟与运行成本

目标：保留语义能力的实验价值，同时避免每次请求都承担约 200-500ms 的 embedding 成本。

- 对标准化 query、slots 和约束组合做短 TTL embedding 缓存。
- 为 embedding 和 Qdrant 设置明确超时，超时立即回 Structured，并在 Trace 中区分原因。
- 记录线上实际语义路由比例，再估算总体平均延迟，而不是只看挑战集平均值。
- 评估 Qdrant candidate limit、融合权重和二次 MySQL 查询的耗时占比。
- 保留批量索引的退避、限流和断点续跑；不要在启动时无条件重算全部向量。

验收建议：语义路由请求的质量不下降，P95 明显低于当前 MiniMax 479.83ms；非语义请求延迟和结果不变。

### P1：评测可信度

- 将语义挑战扩展到 20-30 条，保持人工标注质量，不盲目扩充语料。
- 运行至少 3 次，记录均值、P95 和波动范围。
- 按六个 stratum 分层报告，尤其单独检查 `long_tail` 和 `synonym`。
- 将 TwoStage 拆成“约束查询”和“纯语义查询”两组，避免汇总值掩盖路由效果。
- 对语义结果增加人工相关性等级，必要时计算 graded NDCG。
- 在评测报告中记录 git commit、工作树 dirty 状态、语料 hash、模型、维度、collection 和限流/降级信息。

验收建议：任何质量结论都能从固定语料、固定查询、固定配置和原始报告重新得到。

### P2：数据和检索质量

- 先检查 10 条长尾查询的失败原因，再决定是补充 aliases、tags、description 还是调整 embedding 文本。
- 不因“数据少”直接扩大到未审核数据；当前 295 条足以完成面试演示。
- 只有当新增数据有稳定来源、审核状态和可复现 manifest 时，才创建 `current-corpus-v2`。
- 检查候选数量很大时 Recall@3 偏低的指标解释，避免把真值集合过大的查询误判为模型失败。

### P2：可观测性和供应商扩展

- 当前 Trace 已足以证明路由、模式、降级和耗时；Jaeger 只在需要展示完整运行链路时再做。
- MiniMax 聊天模型（`MiniMax-M3`）与 MiniMax embedding 是两条独立工作，若要切换聊天模型需另做 OpenAI-compatible chat smoke 和契约验证。
- 不把 MiniMax 凭证写入 Git；实验配置与线上默认配置保持隔离。

## 当前暂不需要做的事

- 不把 Hybrid 改成线上默认。
- 不把训练和作息检索改成 RAG。
- 不为了提高单次 Recall 数字而无审核地增加数据。
- 不在质量评测结论尚未稳定前投入 Jaeger 全链路改造。

## 可复现命令

默认 Structured/DashScope 主评测：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.vectorstore.mode=qdrant --diet.rag.mode=hybrid --diet.rag.eval-run=true --diet.rag.eval-report-path=data/reports/rag_evaluation.json"
```

MiniMax 主评测：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.embedding.provider=minimax --diet.embedding.model=embo-01 --diet.embedding.dimensions=1536 --diet.vectorstore.provider=minimax --diet.vectorstore.model=embo-01 --diet.vectorstore.dimension=1536 --diet.vectorstore.version=minimax-1536 --diet.vectorstore.mode=qdrant --diet.rag.mode=hybrid --diet.rag.eval-run=true --diet.rag.eval-report-path=data/reports/rag_evaluation_minimax.json"
```

语义挑战评测：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.embedding.provider=minimax --diet.embedding.model=embo-01 --diet.embedding.dimensions=1536 --diet.vectorstore.provider=minimax --diet.vectorstore.model=embo-01 --diet.vectorstore.dimension=1536 --diet.vectorstore.version=minimax-1536 --diet.vectorstore.mode=qdrant --diet.rag.semantic-eval-run=true --diet.rag.semantic-eval-report-path=data/reports/semantic_challenge_v1_minimax.json"
```

报告生成后应检查：`runClassification=REAL_HYBRID`、`degradedCount=0`、collection 和向量维度匹配，并确认报告中的 `gitCommit` 与当前代码状态一致。
