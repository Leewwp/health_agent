# P0 Qdrant VectorStore、索引生命周期与 Compose

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Priority: P0
- Estimate: 1-1.5 天
- Blocked by: 01
- GitHub: https://github.com/Leewwp/health_agent/issues/54

## Question

如何在保持 MySQL 真相源的前提下，将 Qdrant 封装为可替换、可重建、可降级的餐食向量索引？

## Scope

- 定义小型 `VectorStore` 接口，以及 Qdrant 生产适配器和内存测试适配器；
- collection 名称或 metadata 固定 `provider + model + dimension + version` 身份；
- 实现 collection 检查/创建、幂等 upsert、批量索引和 client 生命周期关闭；
- point 只保存 meal ID、向量和检索所需 payload，不把 Qdrant 作为餐食事实库；
- 为 `docker-compose.yml` 增加固定镜像 `qdrant/qdrant:v1.17.0`，应用使用 gRPC 端口；
- 增加 adapter 核心测试，并记录 collection 重建方式。

## Done when

本地 Qdrant 可完成审核餐食索引重建和过滤查询；内存适配器可供领域测试使用；关闭或清空 Qdrant 不会损坏 MySQL 业务数据。

## Answer

2026-08-12 完成（提交 076b919）：

- 新增 `com.diet.health.vectorstore` seam：`VectorStore` 接口 + `QdrantVectorStore`（明文 gRPC 6334、Cosine 距离、身份派生 collection 名、幂等 upsert、payload 过滤、ping/clear/close，Spring destroyMethod 关闭 client）+ `InMemoryVectorStore`（测试替身，行为对齐含空向量/limit 语义）。
- collection 身份固定 `provider + model + dimension + version`，切换 embedding 配置自动换 collection 身份；point 只保存 meal ID、向量和检索所需 payload，MySQL 仍是唯一真相源。
- `VectorIndexingRunner` 启动幂等批量索引（64/批，无向量/维度不一致跳过，payload 含 data_version，失败告警不阻塞启动）；`VectorStoreConfiguration` 按 mode 选择适配器。
- `docker-compose.yml` 增加 `qdrant/qdrant:v1.17.0` 服务（6333/6334 + healthcheck + 数据卷）；README 记录索引重建方式与降级语义。
- 17 个测试通过（Qdrant 真实集成测试按 `itest.qdrant` 门控）；关闭或清空 Qdrant 不影响 MySQL 业务数据。
