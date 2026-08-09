# 06 后端技术栈展示方案

- Type: grilling
- Status: resolved
- Blocked by: 02

## Question

问题范围：在 1-2 周、单后端实例和真实代码缺口约束下，确定哪些后端能力值得进入规格与实现，并给出接入点、取舍理由和面试说明。

候选包括：Flyway、Redis 缓存、本地会话串行守卫、requestId 幂等、Docker Compose、GitHub Actions CI、springdoc-openapi、Actuator/Micrometer、核心测试，以及认证方案。RocketMQ、Redis 分布式锁、微服务和网关需要以“不引入”为默认结论进行审计。

代码事实：slot 字典每轮会被重复读取；当前会话锁为进程内结构；trace 事件由 `TraceScope` 收集并在请求结束时整条 INSERT；评估接口是管理员手动触发；项目已有独立 GitHub 仓库可承载 CI。

产出：技术清单、接入点、实现边界和面试话术。

## Answer

2026-08-09 审计后最终决策：

- **保留**：Flyway；Redis 缓存（首个真实落点为 slot 字典）；Docker Compose；GitHub Actions CI；springdoc-openapi；Actuator/Micrometer；核心自动化测试（多品类编排、计划规则、混合检索）。
- **会话并发采用单实例本地实现**，不部署第二个后端实例，也不把 Redis 分布式锁作为项目能力。定义一个小型 `SessionExecutionGuard` 接口，将两项行为封装在同一模块内：按 sessionId 串行执行，按 `userId + sessionId + requestId` 识别重复提交并复用进行中/已完成结果。锁和幂等结果必须有容量上限与过期策略，避免当前 `ConcurrentHashMap` 随会话数永久增长。
- **保留 Redis 但不承担锁职责**。Redis 用于低频变更、重复读取的数据缓存；不要为展示技术而缓存所有查询结果。
- **RocketMQ 不引入**。现有 trace 事件在请求线程内累积，并在 `TraceScope.close()` 时整条记录一次 INSERT；此前“每个 recordEvent 都同步写库”的依据错误，因此也不使用 Spring Event + `@Async` 改造 trace。保留当前批量单写语义。
- **Spring Security/JWT 从本票移出**。认证方式依赖最终部署拓扑与用户形态，在“身份隔离与部署拓扑”票中决定；同源 Web 场景不默认选择 JWT。
- **明确排除**：RocketMQ、Redis 分布式锁、Spring Cloud、微服务拆分、K8s、API 网关，以及没有业务缺口支撑的中间件。

面试表达重点：项目先识别真实约束，再选择最小技术；能够解释 MQ、分布式锁的适用拐点，以及本项目为什么尚未达到该拐点。幂等控制解决重复 LLM 调用的成本问题，本地会话锁解决状态机并发覆盖，两者不可混为一谈。
