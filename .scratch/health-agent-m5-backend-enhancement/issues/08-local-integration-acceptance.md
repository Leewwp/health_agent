# P0 M5 本地集成验收与运行文档

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Priority: P0
- Estimate: 1 天
- Blocked by: 03, 06, 07
- GitHub: https://github.com/Leewwp/health_agent/issues/49

## Question

如何用最少但可复核的证据证明 M5 后端增强已经完成、能在本地运行，并可在面试中准确讲述？

## Scope

- 从本地 MySQL + Qdrant 环境启动应用，密钥全部通过环境变量注入；
- 运行 `mvn -DskipTests compile`、现有测试及新增 Qdrant/MCP/Skills/Trace 核心测试；
- 使用真实 DashScope Embedding 完成一次 Qdrant hybrid 餐食查询；
- 停止或隔离 Qdrant，验证同一查询降级到结构化结果；
- 使用外部 MCP client/Inspector 完成 `initialize`、`tools/list`、四个 `tools/call`、`resources/list/read`；
- 验证无效 token、非法 Origin、工具 Schema 和 Skill 白名单拒绝；
- 更新 README、Compose/env 示例和 M5 验收记录，说明版本、启动命令、索引重建、降级与非目标。

## Done when

计划内任务全部关闭；项目能在本地运行；真实 Qdrant hybrid、结构化降级、合法 MCP 调用和非法访问拒绝都有可复核证据，且文档没有生产级或效果提升的夸大表述。

## Answer

2026-08-12 真实环境验收通过（提交 f54ef98 / 3fc8e49）：

- 本地 MySQL + Qdrant 1.17.0 启动，密钥全部经环境变量注入；deepseek-v4-flash-0731 聊天 + qwen3.7-text-embedding（1024 维）真实调用。
- 295/295 餐食向量索引到 Qdrant；固定查询集评估 hybrid Recall@3=0.387（structured 0.380）、硬约束命中率 1.0、降级 0，如实记录提升幅度。
- 实时聊天 Trace 记录 HYBRID + 向量身份；停止 Qdrant 后同一查询降级 STRUCTURED/vector_store_unavailable 且优雅返回。
- 外部 MCP 客户端完成 initialize、tools/list、四个 tools/call、resources/list/read；无 token 401、非法 Origin 403（allowlist 配置后）均验证。
- 验收发现并修复两处真实缺口：BrowseMealColumns 缺 source_type 列（qdrant 索引启动 List.of(null) NPE）、VectorIndexingRunner 加 @Order(0) 早于 RAG 评估执行（否则 collection 未建时全量降级）。
- README、Compose/env 示例与评估报告口径同步（387 测试双门控全绿），无生产级或效果提升的夸大表述。
