# P0 MCP 与 Qdrant 依赖兼容性闸门

- Type: task
- Status: open
- Triage: ready-for-agent
- Priority: P0
- Estimate: 0.5-1 天
- GitHub: https://github.com/Leewwp/health_agent/issues/48

## Question

当前 Spring Boot 3.3.13、AgentScope 1.0.11 和 Java 21 依赖线，能否在不升级主框架的前提下稳定承载 MCP Java SDK 0.17.0 与 Qdrant Java Client 1.17.0？

## Scope

- 在 `pom.xml` 显式锁定 MCP SDK `0.17.0` 和 Qdrant Client `1.17.0`，不升级 Spring Boot、AgentScope 或 Jackson 主线；
- 检查 Maven dependency tree 中 Servlet、Jackson、Reactor、gRPC 和 shaded Netty 的最终版本；
- 运行 `mvn -DskipTests compile` 和现有 `mvn test`；
- 完成 MCP `initialize -> tools/list -> tools/call -> resources/read` 最小真实冒烟；
- 完成 Qdrant 1.17.0 collection 创建、upsert 和带过滤条件查询冒烟；
- 将失败原因和继续/缩减判断记录在票内，不同时升级多个基础依赖规避问题。

## Done when

编译和现有测试通过，MCP 与 Qdrant 双冒烟均有可复核结果；若任一技术不可兼容，票内给出明确停止条件，并在继续开发前收缩对应范围。
