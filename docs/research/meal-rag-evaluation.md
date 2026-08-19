# 餐食 RAG 评估记录（33 号票；#77 扩展为 60 条六层查询与多指标消融）

> 固定标注查询集：`src/main/resources/diet/eval/labeled_meal_queries.json`
> （**60 条、六层各 10 条**：精确标签 / 自然语言 / 长尾表达 / 同义词 / 排除项 / 过敏原，
> 每条带 `stratum` 与稳定 sourceId 二元相关集合，`querySetVersion=1.1.0`）。
> 运行方式：`mvn spring-boot:run -Dspring-boot.run.arguments="--diet.rag.eval-run=true"`，
> 报告输出 `data/reports/rag_evaluation.json`（JSON 报告是唯一数字事实来源，本文档只引用该报告口径）。

## 指标口径（与代码 `RecallEvaluationService` 一致）

| 指标 | 公式（topK=3，位置从 1 开始） |
|---|---|
| Recall@3 | top3 命中真值数 / 真值总数（真值为空记 0） |
| MRR | top3 内首个命中的 1/rank，无命中记 0 |
| NDCG@3 | DCG/IDCG；DCG = Σ rel_i / log2(i+1)，IDCG 按真值全部排前 |
| Precision@3 | top3 命中真值数 / 3 |
| 硬约束命中率 | 排除项/过敏原不出现于 top3 的查询占比 |
| 降级分布 | 按原因统计：vector_store_unavailable / embedding_unavailable / no_vector_hits / 无 |
| P95 延迟 | 每次 retrieve 计时，升序第 ceil(0.95×N) 位（毫秒） |

## 消融设计（#77）

- **嵌入文本**：用户原话（`text` 非空）vs 槽位拼接（`text` 置空，走检索器槽位值排序拼接兜底）。
- **融合权重**：结构化分权重 0.3 / 0.5 / 0.7（语义分权重为 1-w）。权重经
  `HybridMealRetriever` 构造器测试接缝显式注入；生产 bean 保持默认 0.5
  （`diet.rag.fusion-weight`），评测不静默修改线上权重。
- 消融矩阵在 runner 侧组织，报告 `ablations` 字段记录全部变体（0.5+用户原话与生产 bean
  口径相同不重复执行）。

## 降级运行结论（2026-08-13，无 API key 环境，295 条审核餐食，querySetVersion 1.1.0）

> **本运行是降级验证，不是 Hybrid 效果数字**：未配置 `DASHSCOPE_API_KEY`，hybrid 对全部
> 60 条查询按设计降级为结构化检索。报告 `degradedRun=true` 已如实标注。对外效果只引用
> 零降级或明确分层说明的真实运行（配置真实 key 并生成向量后重跑，比较同一查询集的
> hybrid vs structured 差异）。

| 指标（structured = hybrid，因全部降级） | 数值 |
|---|---|
| 平均 Recall@3 | 0.351 |
| 平均 MRR | 1.000 |
| 平均 NDCG@3 | 1.000 |
| 平均 Precision@3 | 0.956 |
| 硬约束命中率 | 1.000 |
| 降级次数 | 60 / 60（embedding_unavailable） |
| P95 延迟 | ~4.6 ms（hybrid 降级路径） |

- 分层 Recall@3（structured）：精确标签 0.435 / 自然语言 0.317 / 长尾 0.309 / 同义词 0.275 /
  排除项 0.418 / 过敏原 0.351。分层差异来自真值集合大小：大相关集合（如「快速午餐」52 条）
  的 top3 命中上限低，小集合查询拉高平均。
- 硬约束命中率 1.000：排除项层（排除 175340/187115/287061/73679/112259/100332/103004/
  128908/100900/110711）与过敏原层（牛奶/麸质/鱼/甲壳类/鸡蛋/花生）全部未进入 top3。
- MRR/NDCG@3 为 1.000 属预期：真值即「满足槽位与硬约束的全体审核餐食」，结构化检索
  把槽位命中餐食排在首位，故首个命中恒成立；这两个指标在真实 embedding 运行中才具备
  区分度（语义重排会改变排序）。

## 运行方式与可复现性

## #88 证据口径（2026-08-18）

`RagEvaluationRunner` 现在在每条查询中记录 `structuredCandidateCount`、
`vectorCandidateCount`、`fusedCandidateCount`、`vectorStatus`、向量阶段延迟和整轮延迟，
并在顶层记录 `runClassification`（`REAL_HYBRID` / `PARTIAL_HYBRID` / `FALLBACK_ONLY`）、
Structured 对照差值和降级证据。`RetrievalEvidence` 位于检索公共返回接缝，故障时不会把
Structured 结果误报为向量命中。

仓库中的 `data/reports/rag_evaluation.json` 已于 2026-08-19 在本地 MySQL + Qdrant 1.17.0
环境重跑，当前为 `REAL_HYBRID`：60/60 条零降级，逐查询候选计数、向量状态和阶段延迟均已
记录。此前的 `FALLBACK_ONLY` 快照结论仍保留在下方，作为结构化故障降级回归背景。

### #88 真实 Hybrid 运行（2026-08-19）

运行身份：审核资源 `reviewed-2026-08-10-v1`，Qdrant collection
`meal_dashscope_qwen3.7-text-embedding_1024_v3-1024`，模型
`qwen3.7-text-embedding`，`v3-1024`，1024 维；固定查询集 60 条，topK=3。

| 指标 | Structured | Hybrid | 差值 |
|---|---:|---:|---:|
| 平均 Recall@3 | 0.351 | 0.351 | 0.000 |
| 平均 MRR | 1.000 | 1.000 | 0.000 |
| 平均 NDCG@3 | 1.000 | 1.000 | 0.000 |
| 平均 Precision@3 | 0.956 | 0.956 | 0.000 |
| 硬约束命中率 | 1.000 | 1.000 | 0.000 |
| P95 延迟 | 3.078 ms | 159.407 ms | +156.329 ms |

向量索引为 295/295 条，Hybrid 降级次数为 0，向量状态全部为 `AVAILABLE`。本次结果说明
真实 Qdrant 融合路径和硬约束二次校验可用；在这组标注数据上，融合没有带来 Recall 提升，
同时增加了向量查询延迟，不能表述为效果提升。

- 查询集版本、git commit、审核资源版本、embedding provider/model/version/dimension、
  collection、融合权重、runAt 全部记录在报告 `environment` 字段；报告同时带 `querySetVersion`
  与 `degradedRun`/`degradedRunNote` 标注。
- 复现真实效果数字：准备 MySQL 中与当前 embedding 身份匹配的
  `meal_item_embedding`，启用 `diet.vectorstore.mode=qdrant`、
  `diet.vectorstore.index-on-startup=true` 和 `diet.rag.eval-run=true` 后启动；若需要重建
  向量，再配置 `DASHSCOPE_API_KEY` 并启用 `diet.embedding.generate-on-startup=true`。
  报告 `runClassification=REAL_HYBRID` 且降级次数为 0 时，才可引用 Hybrid 效果数字。
- 向量生成：`diet.embedding.generate-on-startup=true`（需要真实 key）。
- 检索模式：`diet.rag.mode=hybrid`（默认）或 `structured`。
