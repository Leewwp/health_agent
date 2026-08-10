# 33 审核资源、浏览 API 与餐食 RAG

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Depends on: 30, 32

## Scope

在 32 号已通过的 Agent 垂直闭环上接入可重建的审核资源子集、分页浏览 API 和餐食 hybrid RAG。资源扩量不能重写 Agent 编排。

## Must do

- 扩展 `meal_item`，新增 `exercise_item`、`routine_fact` 和必要来源/媒体状态字段；
- 建立可重跑的最小 ETL，记录来源版本、输入摘要、选入/排除数量和原因；
- 建议导入 100-300 条审核餐食、20-40 个 `plan_ready` 动作和 10-20 条作息事实；数量不是验收标准；
- 餐食完成中文名称/标签、份量口径、营养估算标记和过敏原状态；
- 动作补齐 `difficulty`、`movement_pattern`、`risk_tags`、`alternative_group`、`review_status`、`plan_ready`；
- 无明确媒体许可时清除外链并使用稳定无图状态，不因没有图片排除合格文本资源；
- 实现 `/api/v1/health/meals`、`/exercises` 的分页查询和 `page/size` 上限 50；
- 只在餐食召回中加入 `meal_item_embedding` 和 Embedding 适配器，失败回退结构化检索；
- 保留 `Gym visual` 等必要署名字段；
- 使用固定查询集记录 structured-only/hybrid 的 Recall@3、硬约束命中率和 Embedding 降级。

## Must not do

全量导入 1,000 条餐食或 1,324 个动作、训练/作息向量检索、向量数据库、把图片数量当作推荐质量指标，或为了 RAG 改写 32 号的 Agent 契约。

## Done when

审核资源子集和重建报告可复现；接口返回分页、空数据、无图、来源和计划资格状态；结构化与 hybrid 都可运行且硬约束命中率为 100%。hybrid 没有可复现提升时只记录实验结论，不在项目说明中宣称效果提升。

## 验收记录（2026-08-10）

- ETL `scripts/build_reviewed_resources.py` 可重跑（重跑 git diff 为空），报告记录来源版本 + 输入 SHA-256 + 选入/排除原因；seed SQL 启动幂等导入（292 餐食/30 动作/15 事实）。
- 浏览 API `/api/v1/health/meals`、`/exercises` 分页（size≤50 越界 400），无图/来源/plan_ready 状态透出。
- RAG：hybrid 在结构化 top-10 池语义重排，embedding 不可用降级；固定查询集评估 Recall@3=0.393（结构化=hybrid，10/10 降级）、硬约束 1.000，见 docs/research/meal-rag-evaluation.md。
- 待办（需真实 API key，不阻塞）：配置 DASHSCOPE_API_KEY 后执行 `--diet.embedding.generate-on-startup=true` 生成向量，重跑评估记录 hybrid 真实 Recall@3。
