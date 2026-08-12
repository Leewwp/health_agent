# P0 MCP Streamable HTTP、Token 与 Origin 校验

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Priority: P0
- Estimate: 0.5-1 天
- Blocked by: 01
- GitHub: https://github.com/Leewwp/health_agent/issues/51

## Question

如何在现有 Spring Boot/Tomcat 应用内提供最小可用的 MCP Streamable HTTP 入口，并隔离匿名 Cookie、管理端鉴权与 MCP 身份边界？

## Scope

- 使用 MCP Java SDK 0.17.0 的 Servlet Streamable HTTP transport 注册单一 MCP endpoint；
- 使用独立 Filter 校验 `MCP_API_TOKEN` Bearer token，禁止沿用可编辑用户头或管理 token；
- 实现本地演示需要的 Origin allowlist/缺失 Origin 策略，并避免公开绑定产生 DNS rebinding 风险；
- 将鉴权结果传入 MCP transport context，不依赖 MVC `HandlerInterceptor` 覆盖 Servlet；
- 覆盖无 token、错误 token、非法 Origin、合法 initialize 和协议错误响应；
- 文档明确该实现不是 MCP OAuth 2.1 或最新规范全量合规声明。

## Done when

无效身份被拒绝，合法客户端能完成 initialize；MCP endpoint 不暴露健康匿名身份或 admin 权限，测试可复核 Token 与 Origin 边界。

## Answer

2026-08-12 完成（提交 fc194fb）：

- 使用 MCP Java SDK 0.17.0 在 `/mcp` 注册单一 Streamable HTTP 端点（Servlet transport），不覆盖 MVC 拦截器链。
- 独立 `McpSecurityFilter` 校验 `MCP_API_TOKEN` Bearer（常量时间比较、未配置时 fail-closed），与匿名 Cookie/admin token 身份边界完全隔离；Origin allowlist/缺失策略覆盖 DNS rebinding 风险，鉴权结果经 transport context 传入工具 handler。
- 18 个测试覆盖无/错 token 401、非法 Origin 403、合法 initialize、协议错误与 principal 传递；文档明确不声明 MCP OAuth 2.1 或最新规范全量合规。

