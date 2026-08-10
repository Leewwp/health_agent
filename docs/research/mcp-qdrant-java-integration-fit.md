# MCP + Qdrant 在当前 Java 项目中的最小落地评估

研究日期：2026-08-10  
目标环境：Java 21、Spring Boot 3.3.13、AgentScope 1.0.11、MySQL 8。

## 结论

在严格控制范围的前提下，Qdrant 与 MCP + Skills 可以在约一周内完成一个能在本地运行、可供面试演示的版本。推荐路线是：

1. 不升级 Spring Boot，不引入 Spring AI，也不增加第二套 Agent 编排框架。
2. MCP 直接使用 AgentScope 1.0.11 已经传递引入的官方 Java MCP SDK 0.17.0，并在 `pom.xml` 中显式声明该版本，避免依赖来源隐蔽。
3. Qdrant 使用 AgentScope 1.0.11 官方 BOM 已锁定的 Java Client 1.17.0，同时固定 Docker 镜像 `qdrant/qdrant:v1.17.0`。
4. Skills 作为项目自己的版本化能力清单，通过 MCP Resource 暴露；不宣称它是 MCP 标准原语，也不在本轮把现有健康编排器改造成 tool-calling Agent。
5. 第一天下午以前完成两个兼容性 go/no-go 冒烟。若失败，应缩减本轮能力，不应临时升级整套框架。

本方案不是生产级安全方案，也不声称符合最新 MCP OAuth 规范；它的目标是形成一个边界清楚、可运行、能解释设计取舍的面试作品。

## 为什么不用 Spring AI MCP Starter

MCP 最新规范把 Streamable HTTP 列为标准远程传输；单个 MCP endpoint 处理 POST/GET，GET 的 SSE 流可选。旧的 HTTP+SSE 传输已被 Streamable HTTP 取代。规范同时要求 Streamable HTTP 服务校验 `Origin`，本地服务优先绑定 `127.0.0.1`，并建议实施认证。[MCP Transport 规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)

Spring AI 1.1 当前文档支持 Spring Boot 3.4.x/3.5.x，Spring AI 2.0 则面向 Boot 4.0.x/4.1.x；本项目仍是 Boot 3.3.13。为了一个协议适配层同时升级 Boot 并引入 Spring AI，会扩大 Maven、Servlet、Jackson 和回归测试范围，不符合一周最小实现目标。[Spring AI 1.1 Getting Started](https://docs.spring.io/spring-ai/reference/1.1/getting-started.html) · [Spring AI 1.1 MCP Server Starter](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html) · [Spring AI 2.0 Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)

官方 Java MCP SDK 自身提供不依赖 Spring 的 Jakarta Servlet Streamable HTTP transport，以及同步 server、Tool、Resource 和 transport context API。SDK 官方架构说明也明确：核心模块以 Servlet 提供 server transport，授权只提供可插拔 hook，不内置认证实现。因此可以把 MCP 作为现有领域服务外面的一层协议适配器，而不接入 Spring AI 的 ChatClient/Agent。[Java MCP SDK 0.17.0](https://github.com/modelcontextprotocol/java-sdk/tree/v0.17.0) · [Servlet Streamable HTTP 源码](https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.0/mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletStreamableServerTransportProvider.java)

## MCP 最小实现

### 依赖和传输

当前 `agentscope:1.0.11` 的实际依赖中已经包含 `io.modelcontextprotocol.sdk:mcp:0.17.0`；AgentScope 官方 1.0.11 BOM 也将 MCP 固定为 0.17.0。[AgentScope 1.0.11 依赖 BOM](https://github.com/agentscope-ai/agentscope-java/blob/v1.0.11/agentscope-dependencies-bom/pom.xml)

建议在应用中显式声明 `mcp:0.17.0`，并完成以下装配：

- 用 `HttpServletStreamableServerTransportProvider` 建立 `/mcp`，通过 `ServletRegistrationBean` 注册并开启 async support。
- 用 `McpServer.sync(transport)` 显式注册同步 Tools 和 Resources；业务方法直接调用已有 service，禁止 MCP handler 反向调用本应用 HTTP API。
- 通过 `McpTransportContextExtractor<HttpServletRequest>` 把认证后的调用方身份放入 `McpTransportContext`；Tool 只能从 context 读取身份，不能信任请求参数里的 `userId`。
- 只显式注册 allowlist 中的能力，禁止扫描并发布整个 Spring ApplicationContext。

SDK 0.17.0 支持 Streamable HTTP，但它早于当前 2025-11-25 协议版本；当前本地 artifact 的 transport 支持协议版本截至 2025-06-18。它满足本轮“使用推荐传输”的目标，但不能表述为“实现最新 MCP 2025-11-25 全规范”。官方 SDK 当前已经发布 2.x 且包含 breaking changes，本轮不要单独覆盖 AgentScope 锁定的 MCP 版本。[Java MCP SDK Releases](https://github.com/modelcontextprotocol/java-sdk/releases) · [MCP SDK 0.17.0 Release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.17.0)

### 鉴权与用户隔离

本轮使用一个很小的 Servlet Filter 鉴权层即可，不引入完整 OAuth server：

- Bearer API key 从环境变量读取；配置把 key 映射为固定 `clientId` 和内部 `userId`。
- 缺失或错误 key 返回 401；日志只记录 key 指纹，绝不记录明文。
- `Origin` 缺失时允许非浏览器客户端；存在时必须与配置的精确 allowlist 匹配，否则返回 403。
- `get_session_context` 必须调用已有带 `(sessionId, userId)` 条件的 Mapper/Service，确保会话归属校验；输入中不提供可覆盖的 `userId`。

这只是本地演示鉴权。MCP HTTP 授权规范采用 OAuth 2.1 resource server、Protected Resource Metadata 和 `WWW-Authenticate` 发现流程；静态 API key 不得描述为 MCP OAuth 合规实现。[MCP Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)

SDK 0.17.0 的 Servlet transport 会写出 `Access-Control-Allow-Origin: *`，因此仅配置 CORS 不足以满足规范；必须在 transport 前校验请求 `Origin`，并用 response wrapper 抑制或改写通配响应头。该行为可在官方 0.17.0 transport 源码中核对。[Servlet Streamable HTTP 源码](https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.0/mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletStreamableServerTransportProvider.java)

### Tools、Resources 与 Skills

MCP 标准 server primitives 是 Prompts、Resources 和 Tools，没有名为 Skills 的标准原语。[MCP Server Features](https://modelcontextprotocol.io/specification/2025-11-25/server)

本轮建议只实现：

- Tool `search_meals`：调用既有餐食检索服务，只读。
- Tool `get_meal_detail`：按餐食 ID 查询审核数据，只读。
- 可选 Tool `get_session_context`：只有鉴权身份到 `userId` 的映射和越权测试完成后才开放。
- Resource `skill://catalog`：返回所有启用 skill 的名称、版本和说明。
- Resource `skill://{name}/{version}`：返回某个 skill 的用途、输入/输出约束和允许调用的 tool 名称。

Skill manifest 建议放在 classpath 下，至少包含 `name`、`version`、`description`、`allowedTools`、`inputSchema`、`outputSchema`。服务启动时校验：版本存在、工具名属于 MCP allowlist、JSON Schema 可解析；失败则阻止 MCP server 启动。manifest 只描述能力，不负责执行新的 Agent 循环。

每个 Tool 必须声明输入 Schema；若返回结构化结果，应声明输出 Schema 并填写 `structuredContent`。业务错误使用 `isError=true`，协议或参数结构错误使用 JSON-RPC error。服务端仍负责输入校验、访问控制、输出清洗和超时。[MCP Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) · [MCP Resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)

## Qdrant 最小实现

### 版本选择

AgentScope 1.0.11 自带 `QdrantStore` 类，其官方 BOM 将 `io.qdrant:client` 固定为 1.17.0。为避免把 AgentScope 编译时依赖替换为更新且未经本项目验证的版本，本轮显式使用 1.17.0，不追随当前 Java Client 1.19.0。[AgentScope 1.0.11 依赖 BOM](https://github.com/agentscope-ai/agentscope-java/blob/v1.0.11/agentscope-dependencies-bom/pom.xml)

Qdrant Java Client 1.17.0 要求 Java 8+，因此可运行于 Java 21；它使用 gRPC 1.75.0，运行时依赖 shaded Netty。官方 1.17.0 构建同时使用 Qdrant Server v1.17.0 做集成测试，因此本地 Docker 也固定同一版本。[Qdrant Java Client 1.17.0 README](https://github.com/qdrant/java-client/blob/v1.17.0/README.md) · [Qdrant Java Client 1.17.0 build.gradle](https://github.com/qdrant/java-client/blob/v1.17.0/build.gradle) · [Qdrant 1.17.0 测试镜像配置](https://github.com/qdrant/java-client/blob/v1.17.0/gradle.properties)

本轮仍建议在项目自己的 `VectorStore` 接口后直接封装 `QdrantClient`，不直接让领域服务依赖 `AgentScope QdrantStore`。后者的通用 `SearchDocumentDto` 没有暴露本项目需要的 payload filter，直接使用官方 client 更容易表达过敏原、排除项等过滤条件，同时保持业务边界清楚。

### 数据和查询边界

- MySQL 继续是餐食事实与业务状态的唯一真相源；Qdrant 只保存可重建索引。
- collection 使用固定的 embedding `provider + model + dimension` 命名或元数据，向量维度使用当前 DashScope `text-embedding-v3` 的实际返回维度，距离采用 Cosine。
- point ID 使用 `mealId`；payload 只放过滤/回表所需字段，如 `meal_id`、`meal_time`、`cuisine`、`allergens` 和数据版本。
- 索引任务执行幂等 upsert；同 ID upsert 会覆盖已有 point。[Qdrant Points](https://qdrant.tech/documentation/concepts/points/)
- 查询向量进入 Qdrant 时附带 payload `must`/`must_not` filter，取回 meal ID 与 score，再按 ID 回查 MySQL，并由现有 Java 规则重新执行硬约束校验。
- Qdrant 超时、不可用、collection/维度不匹配或结果为空时，立即降级到现有 `StructuredMealRetriever`；本轮保留 MySQL `meal_item_embedding` 回退数据，不做删除迁移。

Qdrant collection 的向量 size 和 distance 是固定配置，payload 支持 keyword/range 及 `must`、`should`、`must_not` 组合；Java Client 1.17.0 官方 README 给出了 collection 创建、批量 upsert、search 和 filter 的完整调用示例。[Qdrant Collections](https://qdrant.tech/documentation/concepts/collections/) · [Qdrant Filtering](https://qdrant.tech/documentation/concepts/filtering/) · [Qdrant Java Client 1.17.0 README](https://github.com/qdrant/java-client/blob/v1.17.0/README.md)

Docker Compose 只需新增一个 `qdrant/qdrant:v1.17.0` 服务，暴露 REST `6333` 和 gRPC `6334`；应用使用 gRPC 6334。不要使用浮动的 `latest` 标签。[Qdrant Quickstart](https://qdrant.tech/documentation/quickstart/)

### 测试策略

测试分两层：

1. `VectorStore` fake 覆盖检索融合、顺序、硬约束二次校验和降级，不启动 Docker。
2. 一个 Qdrant adapter 集成测试启动固定镜像，验证 create collection、upsert、带 `must_not` 的 search 和关闭 client。

Spring Boot 3.3.13 管理 Testcontainers 1.19.8，而 Qdrant Java Client 1.17.0 上游测试使用 Testcontainers 1.20.1。为减少测试依赖升级，本项目可先用 Boot 管理的 `GenericContainer` 暴露 6333/6334，再以 `Grpc.newChannelBuilder(..., InsecureChannelCredentials.create())` 连接映射后的 gRPC 端口；无需引入专用 Qdrant Testcontainers 模块。上游的连接和容器模式可参考其官方测试源码。[Qdrant Java Client CollectionsTest](https://github.com/qdrant/java-client/blob/v1.17.0/src/test/java/io/qdrant/client/CollectionsTest.java) · [Testcontainers Qdrant Module](https://java.testcontainers.org/modules/qdrant/)

## 依赖风险与 go/no-go

| 风险 | 当前事实 | 本轮处置 |
|---|---|---|
| MCP SDK 与 Boot 3.3 | MCP 0.17 编译依赖 Servlet 6.1、Jackson 2.19、Reactor 3.7；Boot 3.3 管理的主线更旧 | 第一天运行真实 `initialize -> tools/list -> tools/call -> resources/read`；失败即不升级 MCP SDK，缩减或停止 MCP 子项 |
| AgentScope 与 Boot 3.3 | AgentScope 1.0.11 官方依赖线已面向 Boot 4/Jackson 2.21/Reactor 2025，本项目当前依靠 Maven 管理版本运行 | 不在本轮顺带“修复”整棵依赖树；新增依赖后必须执行 compile、全量测试和本地启动 |
| MCP 最新协议 | 0.17 的 Streamable HTTP 支持到 2025-06-18，当前规范为 2025-11-25 | 面试表述为“Streamable HTTP 兼容实现”，不表述为最新全规范实现；用实际外部 client/Inspector 验收 |
| Qdrant SDK 漂移 | AgentScope 锁定 1.17.0，当前 Qdrant client 已更新到 1.19.0 | client/server 均固定 1.17.0；不在本轮升级 |
| gRPC/Netty | Qdrant client 使用 gRPC 1.75.0 和 `grpc-netty-shaded` | 查看 `mvn dependency:tree`，执行 Qdrant container 测试；不要另加非 shaded Netty transport |
| 身份泄漏 | MVC `HandlerInterceptor` 不一定覆盖直接注册的 MCP Servlet | MCP endpoint 使用独立 Filter + transport context；越权 session 测试是开放该 Tool 的前置条件 |

MCP 0.17 的依赖版本可由官方模块 POM核对：[mcp-core 0.17.0 POM](https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.0/mcp-core/pom.xml) · [mcp-json-jackson2 0.17.0 POM](https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.0/mcp-json-jackson2/pom.xml)。

第一天的 go/no-go 必须包含：

1. 加入显式 MCP/Qdrant 依赖后，`mvn -DskipTests compile` 与现有测试通过。
2. MCP Streamable HTTP 在当前 Tomcat 上完成一次真实初始化、工具列表和工具调用。
3. Qdrant 1.17.0 容器完成 collection 创建、upsert、过滤 search。

任一项失败时，不同时升级 Spring Boot、AgentScope、MCP SDK 和 Qdrant；这会把兼容性变量放大到一周无法收敛。MCP 失败时优先保住 Qdrant；Qdrant 失败时保留现有结构化检索并记录未完成，不做假的内存“生产实现”。

## 一周工作量判断

| 工作项 | 估算 |
|---|---:|
| 依赖树、MCP 与 Qdrant 双冒烟 | 0.5-1 天 |
| Qdrant adapter、collection/indexer、检索接线和降级 | 1.5-2 天 |
| Qdrant fake/容器测试与本地 Compose | 0.5-1 天 |
| MCP Servlet、API key/Origin Filter、身份 context | 1-1.5 天 |
| 两个只读 Tool、Skills Resources、Schema/allowlist 校验 | 1-1.5 天 |
| 全量回归、本地启动和最小说明 | 0.5-1 天 |
| **合计** | **5-8 人日** |

结论是“可以冲刺完成，但不是宽松承诺”。若要稳定落在一周，必须把完成标准限制为：项目本地可启动、现有测试通过、Qdrant 检索可用且可降级、外部 MCP client 能完成两项只读 Tool 和 Skills Resource 调用。`get_session_context`、OAuth、最新 MCP SDK、Spring Boot 升级、Qdrant 集群、异步索引、压力测试、完整监控和新 Agent 均不属于本轮最低承诺。

## 建议验收

- `mvn test` 通过，应用和 MySQL/Qdrant 能通过本地 Compose 启动。
- 使用真实 DashScope key 完成餐食向量回填；key 只通过环境变量注入，不写入仓库。
- 同一查询可展示 structured、vector/hybrid 的结果与降级路径；不得在没有评测数据时宣称召回提升。
- 外部 MCP client/Inspector 完成 `initialize`、`tools/list`、两次 `tools/call`、`resources/list/read`。
- 无 API key 返回 401，非法 Origin 返回 403，未在 allowlist 的 tool 不可见也不可调用。
- 若开放 `get_session_context`，必须证明另一个身份无法读取该 session；否则不发布此 Tool。

