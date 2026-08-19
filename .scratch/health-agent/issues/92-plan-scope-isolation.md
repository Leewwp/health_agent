# #92 计划范围契约、旧数据清理与生成隔离

Status: resolved / ready-for-human

## 证据

- 实现：`PlanScope`、`PlanScopeGuard`、训练/餐食独立生成服务、`CompositePlanGenerationService`。
- 迁移：`V15__plan_scope_and_test_data_cleanup.sql` 按外键顺序清理旧测试计划；`V16__plan_generation_source_length.sql` 扩展生成来源。
- 测试：范围 Guard、综合简报确认、项目类型一致性、事务回滚、ACTIVE 唯一约束和真实 MySQL V1–V16 门控。
- 口径：新计划不隐式加入 `ROUTINE`，不保留 `LEGACY_MIXED` 分支。
