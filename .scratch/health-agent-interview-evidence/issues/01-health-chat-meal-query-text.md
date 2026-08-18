# P0：健康聊天餐食检索透传用户原始语义

- Type: task
- Status: resolved
- Triage: ready-for-human
- Priority: P0
- Estimate: 0.5 天
- GitHub: https://github.com/Leewwp/health_agent/issues/76

## Question

如何修复健康聊天餐食主链路丢失 `userInput` 的问题，使 Hybrid RAG 优先嵌入用户原话，同时保持 fixture、结构化降级、排除项和硬约束语义不变？

## Scope

- `HealthOrchestratorService` 的 MEAL 检索分支调用 `MealModule` 的显式文本重载；
- 增加编排层回归测试，精确验证用户原话、槽位和排除 ID 均被透传；
- 保持 FIXTURE_SEED 完全绕过 Embedding/VectorStore 的契约；
- 保持 REVIEWED_DB 下显式文本优先、空文本回退槽位拼接的既有语义；
- 必要时补 Trace 断言，证明检索模式和降级原因仍可观测。

## Done when

健康聊天餐食请求的原始文本进入 `MealRetrievalQuery.text`；相关单元测试通过，普通全量测试保持绿；fixture 和结构化降级行为无回归。

## Answer

已在提交 `6374494` 完成：编排层透传用户原话，覆盖推荐、调整排除项、fixture 与结构化降级回归。
