# M5 面试工程化后端增强 - Wayfinder 地图

> Wayfinder 地图。本文件只维护目标、执行约束、已完成结论和范围边界；开放任务以子票为准。

- GitHub: https://github.com/Leewwp/health_agent/issues/46

## Destination

在七天时间盒内，为现有健康 Agent 交付一个本地可运行、可测试、可在面试中解释设计取舍的最小后端增强：使用 DashScope Embedding 与 Qdrant 实现真实候选融合，并通过 MCP 暴露四个只读或纯计算工具和三个版本化 Skill manifest；保持 MySQL 真相源、确定性硬约束、结构化降级和现有接口兼容，不新增 Agent。

## Notes

- **执行覆盖**：本地图显式允许把开发、测试和本地验收作为 `wayfinder:task` 推进；它不是仅做决策的地图。
- 时间盒固定为 `5 个开发日 + 1 个验收日 + 1 个缓冲日`，目标是尽快完成面试作品，不建设生产级平台。
- 只纳入已确认的 P0/P1；P2 和“暂时不做”的工作不得新增为本地图子票。
- DashScope 同时提供聊天与 Embedding；真实 key 只通过环境变量注入，不写入仓库、Issue 或 Trace。
- MySQL 始终是餐食事实和业务状态的唯一真相源；Qdrant 只是可按 `provider + model + dimension + version` 重建的向量索引。
- MCP 作为现有 Spring Boot 应用的协议适配层，只调用既有领域服务，不通过 HTTP 回调自身；Skills 是项目能力清单，不宣称为 MCP 标准原语。
- 每个实施会话先在 GitHub 认领对应子票；只有所有前置票关闭后，后续票才进入 frontier。
- 任一阶段保持 `mvn test` 可通过；只补核心单元/集成冒烟，不做压力测试或完整服务监控。
- 详细技术依据见 `docs/health-agent-implementation-plan.md` 的 M5、`docs/research/llm-provider-key-compatibility.md` 和 `docs/research/mcp-qdrant-java-integration-fit.md`。

## Decisions so far

<!-- 子票关闭后在这里追加“名称 + 链接 + 一句话结论”，不复制票内详情。 -->

- [01 MCP 与 Qdrant 依赖兼容性闸门](issues/01-mcp-qdrant-compatibility-gate.md) — MCP 0.17.0 四步 Servlet 冒烟与 Qdrant 1.17.0 create/upsert/filter/delete 真实通过；修正明文 gRPC 与查询向量构造后，MySQL + Qdrant 全门控 310/0/0，02/04/07 前置解除。
- [02 Qdrant VectorStore、索引生命周期与 Compose](issues/02-qdrant-vector-store-and-indexing.md) — VectorStore 接口 + InMemory/Qdrant 双适配器（collection 身份 provider+model+dimension+version、Cosine、幂等 upsert、payload 过滤、ping/clear/close 生命周期），VectorIndexingRunner 启动幂等批量索引（失败告警不阻塞），Compose 含 qdrant/qdrant:v1.17.0，README 记录索引重建方式；17 个测试通过（Qdrant 真实集成测试按 itest.qdrant 门控）。
- [04 MCP Streamable HTTP、Token 与 Origin 校验](issues/04-mcp-streamable-http-security.md) — /mcp 单一端点挂 Servlet transport，独立 McpSecurityFilter 校验 Bearer MCP_API_TOKEN（常量时间比较、未配置 fail-closed）与 Origin allowlist/缺失策略，鉴权经 transport context 传入工具 handler；18 个测试覆盖无/错 token 401、非法 Origin 403、合法 initialize、协议错误与 principal 传递。
- [07 健康 Agent Trace 最小脱敏增强](issues/07-health-agent-trace-redaction.md) — TraceRedactor 在 AgentTraceService 持久化边界统一脱敏（API key/Bearer/授权头/Cookie/环境变量凭证/JSON 敏感键），MEAL_RETRIEVED 补记向量供应商/模型/版本/collection；测试证明凭证不进入 traceJson/responseJson/errorMessage，非敏感内容原样保留。
- [03 结构化召回与 Qdrant 向量召回融合及降级](issues/03-true-hybrid-retrieval-and-fallback.md) — HybridMealRetriever 升级为两条独立召回路径的确定性融合：Qdrant 向量召回（payload 过滤审核状态 APPROVED/来源 PUBLIC/过敏原/排除 ID）+ 结构化召回，按 ID 回查 MySQL（findApprovedPublicByIds）二次执行全部硬约束，过期索引命中丢弃；融合分 0.5*归一结构分 + 0.5*语义余弦；Embedding/Qdrant 不可用、超时或空结果立即降级结构化并标记原因；VectorFilter 扩展 reviewStatus/sourceType must 过滤；全量 367 测试绿（含真实 Qdrant 集成）。
- [05 四个公共 MCP Tools 与 Schema](issues/05-public-mcp-tools.md) — /mcp 只暴露 search_meals/get_meal_detail/get_routine_facts/calculate_targets 四个只读或纯计算工具（McpToolSpec seam，直接调用 MealModule/HealthResourceProvider/RoutineModule/EnergyCalculator，不回调用自身）；显式 JSON Schema + 参数校验，参数错误 INVALID_PARAMS、资源不存在 RESOURCE_NOT_FOUND、业务失败 isError；8 个端到端测试覆盖工具列表、合法调用、Schema 拒绝与领域失败映射；全量 375 测试绿。
- [06 Skills Registry、Resources 与工具白名单](issues/06-skills-registry-and-resources.md) — 三个版本化 YAML manifest（固定 name/version/description/input_schema/output_schema/allowed_tools/risk_level），SkillsRegistry 启动校验（Schema 可解析、allowed_tools 属于四工具 allowlist、name 唯一，非法即拒绝启动），以稳定 URI skill://<name> 经 resources/list 与 resources/read 暴露原始 YAML；10 个测试覆盖合法读取、非法 Schema、未知工具与重复 name；全量 385 测试绿。

## Not yet specified

当前没有未锐化决策。范围、供应商、版本、最小安全边界、验收标准和实施依赖均已确认；开放内容都是可直接认领的实施任务。

## Out of scope

- 新 Agent、现有健康 Agent 的自主工具调用循环，以及应用通过 MCP/HTTP 回调自身。
- Spring Boot、AgentScope 或 MCP SDK 大版本升级，以及引入 Spring AI。
- MCP OAuth 2.1、完整最新规范兼容声明、公开互联网生产级鉴权。
- Qdrant 集群、高可用、异步索引流水线、分布式任务和删除 MySQL embedding 回退数据。
- Redis、消息队列、第二个后端实例、压力测试、容量规划和完整监控平台。
- 指南向量检索、训练向量检索、长期记忆 Agent、管理后台和新增前端页面。
