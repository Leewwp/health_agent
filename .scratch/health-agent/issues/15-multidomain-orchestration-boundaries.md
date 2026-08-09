# 15 多品类编排模块边界

- Type: grilling
- Status: open
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
