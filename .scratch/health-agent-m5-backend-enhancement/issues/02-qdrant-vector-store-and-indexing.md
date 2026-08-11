# P0 Qdrant VectorStore、索引生命周期与 Compose

- Type: task
- Status: open
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
