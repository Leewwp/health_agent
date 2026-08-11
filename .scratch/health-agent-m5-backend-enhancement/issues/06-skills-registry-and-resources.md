# P1 Skills Registry、Resources 与工具白名单

- Type: task
- Status: open
- Triage: ready-for-agent
- Priority: P1
- Estimate: 0.5 天
- Blocked by: 05
- GitHub: https://github.com/Leewwp/health_agent/issues/50

## Question

如何用版本化 YAML manifest 表达项目能力，并通过 MCP Resources 安全提供给外部 Agent，而不引入新的自主执行循环？

## Scope

- 建立 `meal-recommendation`、`routine-guidance`、`health-target-calculation` 三个 YAML manifest；
- 每个 manifest 固定 `name/version/description/input_schema/output_schema/allowed_tools/risk_level`；
- 建立 Skills Registry，启动时校验版本、Schema 可解析性和 `allowed_tools` 属于四工具 allowlist；
- 以稳定 URI 通过 MCP `resources/list` 和 `resources/read` 暴露 manifest；
- manifest 只描述能力与调用边界，不包含 Prompt、密钥或自主工具编排；
- 测试合法资源读取、非法 Schema、未知工具和重复 name/version。

## Done when

外部 MCP 客户端可列出并读取三个 Skill；任何越过白名单或 Schema 无效的 manifest 都会在启动阶段被拒绝。
