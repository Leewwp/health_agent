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

仓库中的 `data/reports/rag_evaluation.json` 已于 2026-08-27 在本地 MySQL + Qdrant 1.17.0
环境重跑，当前为 `REAL_HYBRID`：60/60 条零降级，逐查询候选计数、向量状态和阶段延迟均已
记录。此前的 `FALLBACK_ONLY` 和 2026-08-19 快照结论仍保留在下方，作为历史回归背景；对外
数字以报告中的最新 `runAt` 为准。

### #88 真实 Hybrid 运行（2026-08-27）

运行身份：审核资源 `reviewed-2026-08-10-v1`，Qdrant collection
`meal_dashscope_text-embedding-v3_1024_v3-1024`，模型
`text-embedding-v3`，`v3-1024`，1024 维；固定查询集 60 条，topK=3。

| 指标 | Structured | Hybrid | 差值 |
|---|---:|---:|---:|
| 平均 Recall@3 | 0.2646 | 0.2716 | +0.0070 |
| 平均 MRR | 0.9667 | 0.9500 | -0.0167 |
| 平均 NDCG@3 | 0.8547 | 0.8561 | +0.0014 |
| 平均 Precision@3 | 0.7778 | 0.7944 | +0.0167 |
| 硬约束命中率 | 1.000 | 1.000 | 0.000 |
| P95 延迟 | 9.121 ms | 247.361 ms | +238.240 ms |

向量索引为 295/295 条，Hybrid 降级次数为 0，向量状态全部为 `AVAILABLE`。本次结果说明
真实 Qdrant 融合路径和硬约束二次校验可用；Recall@3 有约 0.007 的绝对提升，但 MRR 略降，
且 P95 增加约 238 ms。该差异来自一次新的真实运行，不能据此宣称稳定收益；仍需结合
`semantic-challenge-v1` 人工标注和两段式路由分层结果判断是否保留语义路径。

- 查询集版本、git commit、审核资源版本、embedding provider/model/version/dimension、
  collection、融合权重、runAt 全部记录在报告 `environment` 字段；报告同时带 `querySetVersion`
  与 `degradedRun`/`degradedRunNote` 标注。
- 复现真实效果数字：准备 MySQL 中与当前 embedding 身份匹配的
  `meal_item_embedding`，启用 `diet.vectorstore.mode=qdrant`、
  `diet.vectorstore.index-on-startup=true` 和 `diet.rag.eval-run=true` 后启动；若需要重建
  向量，再配置 `DASHSCOPE_API_KEY` 并启用 `diet.embedding.generate-on-startup=true`。
  报告 `runClassification=REAL_HYBRID` 且降级次数为 0 时，才可引用 Hybrid 效果数字。
- 向量生成：`diet.embedding.generate-on-startup=true`（需要真实 key）。
- 检索模式：`diet.rag.mode=structured`（线上默认）或显式启用实验性的 `hybrid`。

## semantic-challenge-v1 两段式路由（2026-08-27）

12 条人工复核并完成 Top-5 标注的语义挑战在同一份 `current-corpus-v1`（295 条
`APPROVED + PUBLIC` 餐食）和同一套 DashScope/Qdrant 索引上运行。评测 runner 每个策略/问题
只调用一次检索并复用 top-10 结果计算 Top-3/5/10，避免重复 embedding 请求；完整原始报告见
`data/reports/semantic_challenge_v1.json`。

| 策略 | Recall@3 | Recall@5 | Recall@10 | 平均延迟（ms） | 硬约束违规 |
|---|---:|---:|---:|---:|---:|
| Structured | 0.0333 | 0.0667 | 0.2167 | 5.68 | 0 |
| Hybrid（全量实验） | 0.1000 | 0.1500 | 0.2500 | 212.20 | 0 |
| TwoStage（7 条 Structured + 5 条语义） | 0.0333 | 0.0833 | 0.2167 | 83.70 | 0 |

这组小样本结果表明，Hybrid 在语义挑战上的召回有提升，但代价是约 37 倍平均延迟；
TwoStage 保护了所有硬约束，但整体召回未超过全量 Structured。它可以作为按查询类型路由的
实验路径，不能据此宣称生产质量收益。当前面试口径保持：MySQL 负责事实约束，Structured
负责确定性候选，只有无强约束且含主观语义词时才允许触发 Hybrid；线上默认仍为 Structured。

## MiniMax 对照运行（2026-08-27）

在同一 295 条语料、同一 60 条查询集和同一融合权重下，使用 MiniMax `embo-01`（1536 维）
重建独立 collection `meal_minimax_embo-01_1536_minimax-1536` 并完成 60/60 条真实 Hybrid
查询。原始报告见 `data/reports/rag_evaluation_minimax.json`。

| 指标 | Structured | MiniMax Hybrid | 差值 |
|---|---:|---:|---:|
| 平均 Recall@3 | 0.2646 | 0.2776 | +0.0131 |
| 平均 MRR | 0.9667 | 0.9556 | -0.0111 |
| 平均 NDCG@3 | 0.8547 | 0.8629 | +0.0082 |
| 平均 Precision@3 | 0.7778 | 0.8056 | +0.0278 |
| 硬约束命中率 | 1.000 | 1.000 | 0.000 |
| P95 延迟（ms） | 2.75 | 479.83 | +477.08 |

MiniMax 主路径 60/60 为 `AVAILABLE` 且零降级；评测期间消融分支曾触发账号 RPM 限制，
因此这些结果只代表本次单轮运行，不能外推为稳定收益。相较 DashScope，本次 MiniMax 的
Recall@3 增量更大但延迟约 175 倍，仍不足以改变线上 Structured 默认和 TwoStage 路由策略。
