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

## Not yet specified

当前没有未锐化决策。范围、供应商、版本、最小安全边界、验收标准和实施依赖均已确认；开放内容都是可直接认领的实施任务。

## Out of scope

- 新 Agent、现有健康 Agent 的自主工具调用循环，以及应用通过 MCP/HTTP 回调自身。
- Spring Boot、AgentScope 或 MCP SDK 大版本升级，以及引入 Spring AI。
- MCP OAuth 2.1、完整最新规范兼容声明、公开互联网生产级鉴权。
- Qdrant 集群、高可用、异步索引流水线、分布式任务和删除 MySQL embedding 回退数据。
- Redis、消息队列、第二个后端实例、压力测试、容量规划和完整监控平台。
- 指南向量检索、训练向量检索、长期记忆 Agent、管理后台和新增前端页面。
