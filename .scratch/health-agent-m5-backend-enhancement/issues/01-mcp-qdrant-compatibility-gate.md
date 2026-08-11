# P0 MCP 与 Qdrant 依赖兼容性闸门

- Type: task
- Status: resolved
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

## Answer

2026-08-12 真实复核通过，M5 可继续：

- Spring Boot 3.3.13、AgentScope 1.0.11、Java 21 保持不变；MCP `0.17.0`、Qdrant Client `1.17.0` 依赖树无冲突，`mvn -DskipTests compile` 通过。
- MCP Servlet transport 的 `initialize -> tools/list -> tools/call -> resources/read` 冒烟真实通过。
- Docker Hub 仍因 TLS handshake timeout 失败；从 DaoCloud 镜像代理取得同一 `qdrant/qdrant:v1.17.0` 镜像并本地重标记，镜像 digest 为 `sha256:f1c7272cdac52b38c1a0e89313922d940ba50afd90d593a1605dbbc214e66ffb`。
- 初次真实运行发现原测试误用默认 TLS 连接明文 6334，以及用 `setVector(index, value)` 写空查询向量；已分别改为显式 `useTls=false` 和 `addVector(...)`。失败路径使用 `finally` 幂等清理 collection。
- `QdrantGateSmokeTest` 已真实完成 collection 创建、upsert、payload filter 查询和删除；`mvn test -Ditest.mysql=true -Ditest.qdrant=true` 最终 310 通过、0 失败、0 跳过。
