# Vercel 部署适配性调研

> 调研日期：2026-08-09
> 
> 目标：判断本仓库的 Spring Boot 3.3/Java 21 + MyBatis + MySQL + Redis + DashScope/AgentScope 长耗时对话服务，以及独立 vanilla 前端，是否适合部署到 Vercel。
> 
> 约束：本报告只使用 Vercel、Spring、Redis/Upstash 官方一手资料。未能稳定访问的供应商文档不作为证据；没有官方明确说明的地方标为“工程推论”。

## 结论摘要

Vercel 适合部署本项目的独立 vanilla 前端，不适合把当前 Spring Boot 服务作为常规、常驻的整栈后端直接迁移。2026 年 Vercel 已提供 Beta 的 OCI 容器镜像和 Services，因此“把 Java/Spring Boot 做成容器并作为 Vercel Function 运行”在技术上存在路径；但这仍然是按请求计费和生命周期管理的 Function，不是拥有常驻进程、Docker Compose、固定网络出口和无限运行时间的 VM/PaaS。

推荐拓扑：

```text
中国大陆用户
    |
    v
Vercel Static Deployment（vanilla HTML/CSS/JS，可选独立域名）
    |
    | HTTPS API + CORS/认证
    v
Spring Boot 3.3 / Java 21（传统容器或 PaaS/VM，单实例起步）
    |                         |
    v                         v
托管 MySQL + Redis          DashScope
（与后端尽量同区域）
```

后端和数据库应选择能稳定服务中国大陆用户、并能访问 DashScope 北京接口的区域和网络环境；Vercel 官方明确表示没有中国大陆基础设施，不能保证中国大陆访问性能或可用性。因此推荐“Vercel 只托管前端，Spring Boot 后端和数据层部署在另一处”的拆分方案。具体后端供应商不是本次限定的一手资料调研范围，部署时仍需单独验证其 Java 21、MySQL、Redis、网络出口和中国大陆合规条件。

## 当前仓库事实

只读检查结果如下：

- `pom.xml` 使用 Spring Boot `3.3.13`、Java 21、`spring-boot-starter-web`、MyBatis Spring Boot Starter、MySQL Connector/J 和 AgentScope Spring Boot Starter `1.0.11`。
- `application.yml` 的 JDBC URL 指向 `localhost:3306`，当前配置是本地 MySQL；仓库没有 Redis 客户端依赖或 Redis 配置，Redis 属于规划项而非当前实现。
- 前端是 `src/main/resources/static/` 下的静态 HTML/CSS/JavaScript，没有 Node/npm 构建步骤，`api.js` 使用同源路径 `/api/v1/diet` 和 `X-User-Id` 请求头。
- 当前没有发现 `SseEmitter`、`StreamingResponseBody`、`EventSource` 或 `WebSocket` 实现；当前聊天请求是普通 HTTP 请求，LLM 结果由后端完成后返回。

## 官方能力核验

### Java runtime

Vercel 的官方 runtime 页面列出 Node.js、Bun、Python、Rust，以及 Go、Ruby、Wasm 和 Edge；没有 Java runtime。Vercel 允许社区 runtime，但社区 runtime 不等于 Vercel 官方 Java runtime，且需要自行承担兼容性和维护成本。

**结论：Java 21 不是 Vercel 官方 Functions runtime。** 当前 Spring Boot 应用不能通过“选择 Java runtime”直接部署。

Vercel 同一页面同时列出 Beta 的 Container Images：Functions 可以运行存放在 Vercel Container Registry 的 OCI 兼容镜像。该能力可以让一个包含 JRE 和 Spring Boot 可执行 JAR 的 Java 镜像启动 HTTP 服务，但这是容器镜像 Function 路径，不改变 Java 不属于官方 runtime 的事实。

来源：

- [Vercel Runtimes](https://vercel.com/docs/functions/runtimes)（访问日期：2026-08-09；页面更新时间：2026-07-29）
- [Vercel Container Images](https://vercel.com/docs/functions/container-images)（访问日期：2026-08-09；页面更新时间：2026-07-07）

### Spring Boot 与容器

Spring Boot 官方文档提供 Dockerfile、分层 JAR 和 OCI 镜像的打包方式，说明 Spring Boot 应用可以被构造成标准容器镜像。Spring Cloud Function 官方的 serverless adapter 文档列出 AWS Lambda 等适配器；本次核验没有发现 Vercel 适配器。这个“没有适配器”的判断是对官方文档范围的检索结果，不是 Spring 对所有未来版本的否定承诺。

因此有两个不同结论：

1. Spring Boot 可以构造成 Vercel Container Images 所要求的 OCI 镜像，这是技术可行性。
2. Vercel 没有 Java/Spring 的官方 Functions runtime 或 Spring 官方 Vercel adapter；在 Vercel 上运行时要依赖 Beta 容器契约和自定义镜像验证。

来源：

- [Spring Boot 3.3.13 Dockerfiles](https://docs.spring.io/spring-boot/3.3.13/reference/packaging/container-images/dockerfiles.html)（访问日期：2026-08-09）
- [Spring Cloud Function AWS Lambda adapter](https://docs.spring.io/spring-cloud-function/reference/adapters/aws-intro.html)（访问日期：2026-08-09；用于核对 Spring 官方 serverless adapter 形态）
- [Spring Boot Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)（访问日期：2026-08-09；用于核对当前官方容器打包文档）

### 常驻 Spring Boot、Docker Compose 与 Vercel Services

Vercel Container Images 要求镜像启动一个接收 HTTP 请求的服务，默认端口为 80，也可以使用 `PORT` 环境变量覆盖。生产环境连续 5 分钟没有流量时实例会自动缩容，预览环境连续 30 秒没有流量时缩容；缩容前发送 `SIGTERM`，有 30 秒清理宽限期。该生命周期与常驻 Spring Boot 服务器不同。

Vercel Services 允许一个项目包含多个前端或后端 service，每个 service 独立构建并通过 rewrite 路由；service 可以使用自定义 container runtime。这能表达“前端 service + 后端 service”，但不等于运行 `docker compose up`：Vercel 文档没有提供 Compose 编排、同一 Compose 网络、任意 sidecar 或常驻 worker 的部署契约。后半句是工程推论。

**结论：**

- 普通 Vercel 项目不能把现有 Spring Boot 进程当作常驻服务器运行。
- 可以尝试把 Spring Boot 包进 `Dockerfile.vercel`，作为 Beta Container Function 运行。
- 不能把本地 MySQL/Redis 容器和 Spring Boot 通过现有 Docker Compose 一起搬到 Vercel；数据库和 Redis 必须是外部托管服务，或者拆成 Vercel 可识别的独立 service，但后者仍不提供 Compose 的持久化数据服务语义。

来源：

- [Vercel Container Images：端口与缩容](https://vercel.com/docs/functions/container-images)（访问日期：2026-08-09）
- [Vercel Services](https://vercel.com/docs/services)（访问日期：2026-08-09；页面更新时间：2026-06-30）

### 时长、内存、文件系统、冷启动

Vercel Functions（启用 Fluid Compute 时）的官方限制为：

| 项目 | 官方限制/说明 |
|---|---|
| 最大时长 | Hobby 默认及最大 300 秒；Pro/Enterprise 默认 300 秒、一般最大 800 秒；1800 秒扩展最大值为 Beta，且官方明确支持范围是指定 Node.js/Python runtime 版本 |
| 内存/CPU | Hobby 2 GB / 1 vCPU；Pro/Enterprise 默认 2 GB / 1 vCPU，可配置到 4 GB / 2 vCPU |
| 文件系统 | 只读文件系统；可写 `/tmp` 最多 500 MB |
| 请求/响应体 | Function 请求体或响应体最大 4.5 MB |
| 文件描述符 | 所有并发执行共享 1,024 个文件描述符，实际可供应用使用的更少 |
| 归档与冷启动 | 生产部署闲置 2 周、预览部署闲置 48 小时会归档；归档恢复后的首次冷启动至少额外增加 1 秒。Vercel 未给出 Spring/JVM 的固定冷启动时间 |
| 容器镜像 | Container Images 页面说明沿用 Vercel Functions 的相同限制和 Active CPU 计费模型 |

“Java 容器具体能否使用 800 秒、Java Spring 镜像的实际大小上限、JVM 启动耗时”没有在已核验的 Vercel 官方页面中按 Java 单独承诺，均需在实际账号和镜像上做部署实验。

来源：

- [Vercel Functions Limits](https://vercel.com/docs/functions/limitations)（访问日期：2026-08-09）
- [Vercel Maximum Duration](https://vercel.com/docs/functions/configuring-functions/duration)（访问日期：2026-08-09；页面更新时间：2026-07-01）
- [Vercel Memory and CPU](https://vercel.com/docs/functions/configuring-functions/memory)（访问日期：2026-08-09；页面更新时间：2026-07-15）
- [Vercel Fluid Compute](https://vercel.com/docs/fluid-compute)（访问日期：2026-08-09）
- [Vercel Container Images](https://vercel.com/docs/functions/container-images)（访问日期：2026-08-09）

### 外部 MySQL/Redis 网络连接

Vercel 官方文档说明 Function 可以访问外部数据存储，并建议让计算区域靠近数据库。Vercel 的 Node.js API 文档还明确列出 MySQL2、MariaDB 和 Redis（ioredis）等连接池客户端，并提供 `attachDatabasePool` 管理 Fluid Compute 中的连接池。这个 API 是 Node.js 专用，不适用于本仓库的 Java/Hikari/MyBatis；它只能证明 Vercel 对外部数据库连接和连接池问题有明确支持方向。

Vercel 默认出站请求来自动态 IP。若 MySQL/Redis 供应商要求 IP allowlist，Vercel 提供 Pro/Enterprise Static IP，但官方 Container Images 页面明确写出 Secure Compute 和 Static IPs 尚不支持自定义容器镜像；Secure Compute 页面也将 Container Images beta 列为不支持的增强能力。使用 Vercel Java 容器时，需依赖公开可达、TLS、账号密码/令牌和数据库自身安全策略，不能假设有固定出站 IP。

Upstash 官方文档说明其 Redis 同时支持 TCP 和 HTTP，TLS 默认开启；Java 可以用 Jedis 连接。Upstash 还明确建议 serverless 环境优先使用 HTTP/REST，因为高并发 serverless 下 TCP 客户端可能遇到连接数问题。对于传统单实例 Spring Boot，Jedis/Lettuce 连接池更自然；若后端改造成 Vercel Function，则应优先选用 serverless 友好的 HTTP Redis 客户端或严格管理连接池。

**结论：** 外部 MySQL/Redis 在网络层面可行，但当前本地 `localhost` 配置不能直接用于 Vercel；必须改为托管服务的 TLS 连接串，并处理连接池、连接上限、动态出站 IP、跨区域延迟和凭据注入。

来源：

- [Vercel Storage overview](https://vercel.com/docs/storage)（访问日期：2026-08-09）
- [Vercel `@vercel/functions` API：attachDatabasePool](https://vercel.com/docs/functions/functions-api-reference/vercel-functions-package)（访问日期：2026-08-09；页面更新时间：2026-07-27）
- [Vercel：allowlist deployment IP addresses](https://vercel.com/kb/guide/how-to-allowlist-deployment-ip-address)（访问日期：2026-08-09；页面更新时间：2026-07-16）
- [Vercel Secure Compute](https://vercel.com/docs/networking/secure-compute)（访问日期：2026-08-09；页面更新时间：2026-07-29）
- [Upstash：Connect Your Client](https://upstash.com/docs/redis/howto/connect-client)（访问日期：2026-08-09）
- [Upstash：Using redis-cli，TCP or HTTP](https://upstash.com/docs/redis/howto/redis-cli)（访问日期：2026-08-09）
- [Upstash：Java/Jedis serverless example](https://upstash.com/docs/redis/tutorials/serverless_java_redis)（访问日期：2026-08-09）

本次没有找到并稳定读取一个 MySQL 供应商的 Vercel + Java/JDBC 专门官方页面，因此没有把某个 MySQL 供应商的区域、连接数或网络策略写成事实；这些信息应在选定供应商后单独复核。

### SSE、流式响应与 WebSocket

Vercel 官方 Streaming 文档说明 Node.js 和 Python Function 支持流式响应，但流式响应仍计入 Function 最大时长，不能无限流式。Vercel 的最大时长文档还说明长时间 HTTP/2 连接可以用协议级 PING，但 HTTP/1.1 没有等价机制，中间网络层可能关闭空闲连接，因此应持续发送进度或心跳。

Vercel WebSockets 已是 Beta，所有计划可用，但连接绑定在接受它的 Function 实例上；达到 Function 最大时长会断开，重连不保证回到同一个实例，持久状态必须放到外部 Redis 等存储。官方示例主要针对 Node.js、Python 和框架适配器，没有给出 Java/Spring WebSocket 示例。

**对本仓库的判断：** 当前没有 SSE/WebSocket 实现。若未来只需要普通聊天结果，保持短请求比引入流式更稳妥；若需要逐 token 流式，传统 Spring Boot 后端可以单独设计 SSE/WebSocket，但“Java Spring 在 Vercel Container Image 上的流式行为”本次没有得到 Vercel 官方专门核验，应先做小型端到端实验。若依赖长连接来维持会话或 Agent 内存，不能依赖进程内状态。

来源：

- [Vercel Streaming Functions](https://vercel.com/docs/functions/streaming-functions)（访问日期：2026-08-09；页面更新时间：2026-02-13）
- [Vercel Maximum Duration](https://vercel.com/docs/functions/configuring-functions/duration)（访问日期：2026-08-09）
- [Vercel WebSockets](https://vercel.com/docs/functions/websockets)（访问日期：2026-08-09；页面更新时间：2026-07-24）
- [Vercel WebSocket Knowledge Base](https://vercel.com/kb/guide/do-vercel-serverless-functions-support-websocket-connections)（访问日期：2026-08-09；页面更新时间：2025-11-03）

### Cron 与后台任务

Vercel Cron 通过生产部署 URL 发起 HTTP GET，Cron 的时长限制与 Functions 相同。官方文档明确说明：失败调用不会自动重试；投递是 best effort，可能漏调或重复调用；当执行时间超过间隔时可能并发重叠。需要使用幂等设计和锁机制。

Hobby 计划最多每天一次且时间精度为小时级；Pro/Enterprise 可以每分钟运行。Cron 不能替代一个常驻 scheduler 或 worker。

Vercel `waitUntil` 只是在响应返回后继续执行 Promise，Promise 仍共享本次 Function 的超时，超时后会被取消。官方 API 页面将它定位为日志、分析、缓存更新等短后台副作用，不是无限后台任务。Vercel Workflows 的官方支持语言是 JavaScript、TypeScript 和 Python；本次没有找到 Java 工作流运行时。

**对本项目的判断：** AgentScope/DashScope 调用若在请求内完成，受 Function 时长和连接断开限制；若想把计划生成拆成后台任务，应使用外部队列/worker 或把任务改造成 Vercel 支持的 JS/TS/Python Workflows，而不是在 Spring Boot 请求线程里启动不受管理的后台线程。用户明确只保留本地锁时，后端必须保持单实例；Vercel 容器 Function 可能扩展出多个实例，本地锁只能拦截同一实例内的重复提交，不能提供全局互斥。

来源：

- [Vercel Cron Jobs](https://vercel.com/docs/cron-jobs)（访问日期：2026-08-09；页面更新时间：2026-06-16）
- [Vercel Managing Cron Jobs](https://vercel.com/docs/cron-jobs/manage-cron-jobs)（访问日期：2026-08-09；页面更新时间：2026-07-15）
- [Vercel Cron Usage and Pricing](https://vercel.com/docs/cron-jobs/usage-and-pricing)（访问日期：2026-08-09；页面更新时间：2026-07-15）
- [Vercel `waitUntil`](https://vercel.com/docs/functions/functions-api-reference/vercel-functions-package)（访问日期：2026-08-09）
- [Vercel Workflows](https://vercel.com/docs/workflows)（访问日期：2026-08-09；页面更新时间：2026-07-15）

### 区域与中国大陆访问

Vercel 官方区域列表包含香港 `hkg1`、东京 `hnd1`、新加坡 `sin1` 等亚洲区域，但没有中国大陆计算区域。默认 Function 区域是美国华盛顿特区 `iad1`，官方建议让 Function 与数据库位于同一区域或尽量接近。

Vercel 官方中国大陆访问指南明确写出：Vercel 没有中国大陆服务器或 CDN 节点；境外域名可能被长城防火墙限速或阻断；Vercel 不提供中国境内托管或 ICP/本地合规支持；即使使用自定义域名，也不能保证中国大陆可用性和性能。香港节点不是中国大陆节点，不能把 `hkg1` 解释成大陆部署。

本仓库当前 DashScope `base-url` 指向阿里云北京地址。由于 Vercel 官方没有对该地址的跨境连通性、延迟或可用性作承诺，本报告只得出工程推论：把需要稳定访问北京 DashScope 的 Java 后端放在与它网络关系更直接的区域，风险低于把后端放到 Vercel 再跨区域调用；实际仍需从候选部署区域做 DNS、TLS、HTTP 超时和长响应测试。

来源：

- [Vercel Global network and regions](https://vercel.com/docs/regions)（访问日期：2026-08-09；页面更新时间：2026-03-05）
- [Vercel Configuring Function regions](https://vercel.com/docs/functions/configuring-functions/region)（访问日期：2026-08-09；页面更新时间：2026-07-15）
- [Vercel：Accessing Vercel-hosted sites from mainland China](https://vercel.com/kb/guide/accessing-vercel-hosted-sites-from-mainland-china)（访问日期：2026-08-09；页面更新时间：2025-11-03）

## 三种部署方案比较

### 方案 A：整栈部署到 Vercel

**可行性：有条件可行，不推荐作为当前项目的生产默认方案。**

可行路径不是官方 Java runtime，而是：

1. 前端作为静态输出或一个 frontend service。
2. Spring Boot 构建为 OCI 镜像，写 `Dockerfile.vercel`，监听 Vercel 注入的 `PORT`。
3. MySQL 和 Redis 使用外部托管服务，不能依赖 `localhost` 或 Docker Compose 内的数据库容器。
4. API 通过 Vercel Services rewrite 或独立后端域名提供。

主要问题：

- Container Images 和 Services 都是 Beta。
- Spring/JVM 启动、AgentScope 初始化和 MyBatis 连接池会增加冷启动和内存压力；Vercel 没有对 Java/Spring 冷启动作保证。
- Function 会缩容和归档，不是常驻进程；单请求仍受 Function 时长约束。
- Container Images 不支持 Static IPs/Secure Compute，数据库 IP allowlist 和私网连接不适合该方案。
- 中国大陆无本地节点，且 DashScope 北京地址、数据库和 Function 可能跨区域。
- Vercel 的本地锁无法阻止不同 Function 实例同时执行同一用户请求。

适用范围：仅适合作为低流量演示或明确接受 Beta 限制的实验。若一定要试验，应先以一个健康检查接口和一个不调用 LLM 的聊天假后端验证容器启动、端口、外部 MySQL/Redis、日志、超时和部署后缩容行为，再接入 AgentScope。

### 方案 B：仅前端部署到 Vercel

**可行性：成熟且推荐。**

Vercel 官方支持没有构建步骤的静态项目：选择 Other framework preset、清空 Build Command，并直接提供根目录静态文件。当前 vanilla 文件可以独立复制到一个前端部署目录；也可以保留在 monorepo 中，通过 Root Directory/Output Directory 指向前端目录。

后端 API 应使用独立 HTTPS 域名。当前 `api.js` 的同源 `/api/v1/diet` 需要改为可配置 API origin，或者配置 Vercel external rewrite；无论哪种方式，Spring Boot 都必须配置 CORS，不能把 `X-User-Id` 当作真实认证。前端托管和 API 托管分离后，浏览器请求、Cookie/Session、CORS、CSRF、错误重试和流式跨域都需要单独验收。

这是本仓库的推荐方案：保留 Java 21、Spring Boot、MyBatis 和 AgentScope 的运行模型，把 Vercel 当作静态 CDN/前端发布平台。

来源：

- [Vercel Configuring a Build：无构建步骤的静态项目](https://vercel.com/docs/builds/configure-a-build)（访问日期：2026-08-09）
- [Vercel Frontends](https://vercel.com/docs/frameworks/frontend)（访问日期：2026-08-09）
- [Vercel CDN overview](https://vercel.com/docs/cdn)（访问日期：2026-08-09）

### 方案 C：把后端改造成 Vercel serverless function

**可行性：可以重写，但不适合当前 Spring Boot 项目。**

Vercel 官方 Function runtime 的主路径是 Node.js/Python 等，不是 Java。若把后端改写为 TypeScript/Node.js：

- 需要重写 Spring MVC Controller、MyBatis 数据访问、会话锁、AgentScope 调用和异常处理。
- 普通请求可以使用 Node.js Function；流式响应可以使用 Vercel streaming；WebSocket 是 Beta 且连接寿命受 Function 时长约束。
- MySQL2/Redis 连接池需要按 Vercel Fluid Compute 方式管理；Upstash REST Redis 更贴近 serverless。
- 超过单次 Function 时长的计划生成要拆成外部队列或 Vercel Workflows；Workflows 官方支持 JS/TS/Python，不支持把当前 Java AgentScope 直接搬过去。

这不是“部署当前项目”，而是换后端语言和运行模型，重写量大、回归面广，也会削弱现有 Spring/MyBatis 架构的复用价值。除非未来明确决定整个后端转向 TypeScript/Python，否则不建议为 Vercel 改造。

## 推荐落地顺序

1. 将 vanilla 前端从 Spring Boot 静态资源中抽出为独立 Vercel 项目，先用 mock API 验证静态部署和中国大陆访问；生产页面使用自定义域名，不依赖 `.vercel.app`。
2. 在传统容器/PaaS/VM 上部署当前 Spring Boot 单实例，使用 Java 21；将 MySQL/Redis 改为外部托管服务，三者尽量同区域。此处保留本地会话锁，正好满足“拦截重复提交、避免浪费 LLM 算力”的目标；它不是跨实例分布式锁。
3. 为后端配置正式认证和用户隔离，移除仅信任浏览器 `X-User-Id` 的设计；以环境变量/密钥服务注入 DashScope、MySQL 和 Redis 凭据。
4. 给聊天请求设置总超时、LLM 超时、数据库连接超时和幂等键；对重复提交返回已有请求结果或明确的处理中状态。
5. 若之后确实需要低成本尝试 Vercel 后端，再单独建立 Container Images Beta 原型，不改变生产后端；验证容器大小、启动时间、MyBatis 连接池、DashScope 访问、SSE/WebSocket、缩容重启和本地锁失效场景。

## 最终推荐

对当前仓库：**Vercel 只部署独立 vanilla 前端；Spring Boot/Java 21 + MyBatis + MySQL + Redis + AgentScope 放在传统容器运行环境；后端与数据层放在面向中国大陆和 DashScope 的合适区域。**

整栈 Vercel 不是绝对不可行，但只能作为 Beta 容器 Function 实验，不能按“常驻 Spring Boot + Docker Compose”理解。后端重写为 Vercel serverless function 也不是小型部署适配，而是 Node.js/Python 方向的重构；对当前项目不划算。

## 未核验项与风险边界

- 没有找到 Vercel 官方针对 Java 21、Spring Boot 3.3、AgentScope 或 DashScope 的兼容性测试矩阵；Java 容器的实际冷启动、镜像大小和 JDBC 长连接行为必须实测。
- 没有找到 Vercel 官方对 Java/Spring 在 Container Images 上的 SSE/流式实现示例；Node.js/Python 的流式能力不能直接当作 Java 兼容性承诺。
- 没有找到并稳定读取一个 MySQL 供应商的 Vercel + Java/JDBC 专门官方页面；本报告未指定某个 MySQL 供应商，也未把其连接数、区域、白名单规则写成既定事实。
- Vercel 中国大陆指南说明平台不保证大陆可用性；但从具体中国网络、Vercel 区域到 DashScope 北京 endpoint 的延迟/丢包/长连接成功率，本次没有做线上压测。
- Vercel Container Images、WebSockets、Services、Vercel Queues/Workflows 中部分为 Beta；正式采用前应确认当前账户计划、区域、账单和 Beta 可用性。

