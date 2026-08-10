# 餐食 RAG 评估记录（33 号票）

> 固定标注查询集：`src/main/resources/diet/eval/labeled_meal_queries.json`（10 条，真值为库内审核餐食来源 ID）。
> 运行方式：`mvn spring-boot:run -Dspring-boot.run.arguments="--diet.rag.eval-run=true"`，报告输出 `data/reports/rag_evaluation.json`。

## 结论（2026-08-10，无 API key 环境，292 条审核餐食）

| 指标 | structured-only | hybrid |
|---|---|---|
| 平均 Recall@3 | 0.393 | 0.393（全部降级为结构化） |
| 硬约束命中率 | 1.000 | 1.000 |
| 降级次数 | 0 | 10 / 10 |

- 本环境未配置 `DASHSCOPE_API_KEY`，`meal_item_embedding` 为空：hybrid 检索器对全部 10 条查询按设计降级为结构化检索，结果与 structured-only 逐条一致（Recall@3 相同），Embedding 降级正确率 100%。
- 检索池只包含 `review_status=APPROVED` 的审核餐食；旧库 PENDING 行不进入审核检索链路（浏览、检索、评估口径一致）。
- 硬约束（过敏原/排除 ID）命中率 1.000：`q-peanut-exclusion`（排除 208047 花生酱鸡）、`q-fish-exclusion`（排除 278532 鱼料理）的排除项均已传入检索器过滤且未进入 top3。
- 结构化基线 Recall@3=0.393：查询集多为大相关集合（如 52 条「快速午餐」），top3 命中 3 条的预期上限约 3/52≈0.058，其余查询（如 2-3 条小集合）拉高了平均值。

## 说明

- 未配置向量/未生成 embedding 时不宣称 RAG 效果提升；配置 `DASHSCOPE_API_KEY` 并执行
  `--diet.embedding.generate-on-startup=true` 生成向量后重跑评估，hybrid 的语义重排在
  结构化 top-10 池内进行，方可比较语义分对 Recall@3 的影响。
- 向量生成：`diet.embedding.generate-on-startup=true`（需要真实 key），幂等写入 `meal_item_embedding`。
- 检索模式：`diet.rag.mode=hybrid`（默认）或 `structured`。
