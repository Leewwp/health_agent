# P0 四个公共 MCP Tools 与 Schema

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Priority: P0
- Estimate: 0.5-1 天
- Blocked by: 04
- GitHub: https://github.com/Leewwp/health_agent/issues/47

## Question

如何用最小、稳定且可面试解释的 Schema，将既有健康领域能力暴露为四个只读或纯计算 MCP Tools？

## Scope

- 实现 `search_meals`、`get_meal_detail`、`get_routine_facts`、`calculate_targets`；
- 每个 Tool 声明输入 Schema、输出 Schema 和结构化结果，参数错误与业务错误使用正确的 MCP 错误语义；
- handler 直接调用既有 resource/profile/target 领域服务，不通过本应用 HTTP API 回调自身；
- 餐食查询继续使用审核资源和硬约束，目标计算继续使用确定性公式；
- 不开放写档案、生成/激活计划、写反馈、读取 Trace 或任意数据库查询工具；
- 测试工具列表、合法调用、Schema 拒绝和领域失败映射。

## Done when

合法 MCP 客户端能发现并调用四个工具，返回结果与现有领域服务一致；工具不产生业务写入，也不能越过审核资源和确定性规则。

## Answer

2026-08-12 完成（提交 0b4a077）：

- `/mcp` 只暴露 `search_meals`、`get_meal_detail`、`get_routine_facts`、`calculate_targets` 四个只读或纯计算工具。
- `McpToolSpec` seam 直接调用 `MealModule`/`HealthResourceProvider`/`RoutineModule`/`EnergyCalculator` 等既有领域服务，不通过本应用 HTTP API 回调自身；餐食查询继续走审核资源与硬约束，目标计算走确定性公式。
- 每个 Tool 声明显式 JSON Schema + 参数校验：参数错误 `INVALID_PARAMS`、资源不存在 `RESOURCE_NOT_FOUND`、业务失败 `isError`。
- 不开放写档案、生成/激活计划、写反馈、读 Trace 或任意数据库查询工具；8 个端到端测试覆盖工具列表、合法调用、Schema 拒绝与领域失败映射；全量 375 测试绿。

