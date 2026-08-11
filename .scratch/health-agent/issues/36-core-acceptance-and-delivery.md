# 36 核心验收、部署与运行手册

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Depends on: 31, 32, 33, 34, 35

## Scope

按 28 号验收门槛汇总并复现各阶段已经建立的核心测试、RAG 对比、接口/浏览器冒烟、Compose/CI、README 和部署运行手册。本票不负责首次补写前序票遗漏的核心测试。

## Must do

- 汇总并在干净环境重跑多品类意图、Agent 契约、计划、风险、反馈和降级测试夹具；
- 固定餐食查询集，记录 structured-only/hybrid 的 Recall@3、硬约束命中率和 Embedding 降级；
- 验收非法 JSON、API key 缺失、Embedding 不可用、候选为空、媒体 404、重复 requestId、Cookie 篡改、admin 未授权和 MySQL 不可用；
- 使用真实浏览器完成桌面/移动端三条主流程；
- 提供 Java 21 CI、Compose、健康检查、Flyway 前备份和应用镜像回退说明；
- 更新 README：启动、导库、环境变量、开发/用户侧边界、测试和部署入口；
- 输出 M0-M4 完成证据清单；
- 分别标记“面试可演示门槛”和“完整 MVP 门槛”，不得用前者冒充后者；
- 如果 hybrid 没有可复现提升，不在项目说明中宣称 RAG 有效果提升。

## Done when

28 号发布证据矩阵所有条目都有自动化、接口、浏览器或部署证据；项目能按 README 从干净环境启动；完成结果和残余缺口可复核。

## Answer

已完成 M0-M4 验收、干净库启动、真实 DashScope/浏览器流程、MySQL 事务集成测试、Compose/CI 和运行文档。证据见 `docs/release-evidence.md`；GitHub CI 全绿，对应实施票已关闭。
