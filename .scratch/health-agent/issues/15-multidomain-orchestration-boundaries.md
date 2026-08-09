# 15 多品类编排模块边界

- Type: grilling
- Status: resolved
- Blocked by: 04, 13, 14

## Question

如何保留“统一对话入口”，同时避免把饮食、健身、作息和综合计划全部堆进现有 500 行以上的 `DietOrchestratorService`？

推荐评估以下模块与 seam：

1. `HealthOrchestrator` 只负责会话生命周期、意图/模式、澄清、路由、风险与持久化。
2. 饮食、动作、作息各自形成深模块，对编排层暴露领域结果，而不是泄露 Mapper、prompt 和检索细节。
3. `WeeklyPlanComposer` 负责跨品类时间冲突、数值预算和计划一致性校验。
4. `SessionExecutionGuard` 封装本地串行执行与 requestId 幂等，调用方不感知锁和缓存实现。
5. 测试以这些模块的外部接口为主要验证面，LLM、数据库和 embedding 使用可替换 adapter。

产出：模块职责、接口草案、依赖方向和现有类迁移清单。

## Answer

2026-08-10 已决策：

- `HealthOrchestrator` 负责会话生命周期、意图分类、澄清、领域路由、风险和持久化，不直接处理 Mapper、领域 prompt 或检索细节。
- 饮食、健身、作息分别形成领域服务，对外暴露领域结果；餐食混合召回封装在饮食领域内部。
- `WeeklyPlanComposer` 负责综合周计划的时间冲突、数值预算和跨领域一致性校验。
- `SessionExecutionGuard` 统一封装单实例会话串行和 requestId 幂等，调用方不依赖具体锁或缓存实现。
- LLM、数据库和 Embedding 均通过可替换 adapter 注入，核心测试优先覆盖这些模块的外部接口。

## Integration Decision

2026-08-10 补充确认：新内容复用现有餐食推荐的“意图识别 → 槽位/档案合并 → 澄清 → 召回/排序 → Agent 解释 → 风险 → 持久化”模式，但不直接复用餐食领域类型。

- 现有餐食检索、排序、推荐应答和多餐逻辑先整体封装到 `MealModule`，保持现有饮食链路行为不变。
- `ExerciseModule` 复用相同的推荐/调整/解释模式，但内部使用动作筛选、审核白名单、替代关系和训练计划片段。
- `RoutineModule` 复用相同的澄清/解释/来源模式，但首版内部使用时间区间和结构化作息事实；指南证据检索作为后续扩展。
- `WeeklyPlanComposer` 只接收各领域的计划片段和健康档案结果，负责七天时间安排、跨领域冲突、数值预算和最终校验。
- `SlotBundle`、`MealPlanService`、`MealRankService` 和餐食 Prompt 保留在餐食模块内部，不扩展成包含所有品类字段的万能类型。
- 现有 HTTP 入口、会话、消息、Trace 和餐食 API 保持兼容；新的健身、作息和周计划 API 作为增量能力加入。

本决策冻结了后端模块方向，但 03 原型文件、17 号偏好模型和 18 号前端信息架构仍是后续独立任务。
