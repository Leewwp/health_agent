# P1：为健康反馈增加 traceId 精确归因

- Type: task
- Status: open
- Triage: ready-for-agent
- Priority: P1
- Estimate: 1 天
- GitHub: https://github.com/Leewwp/health_agent/issues/74

## Question

如何让健康聊天产生的反馈精确关联到推荐 Trace，而不是由评估器按 session 和时间窗口近似归因，同时保持旧饮食反馈和计划反馈兼容？

## Scope

- 新增 Flyway 迁移，为 `recommend_feedback` 增加可空 `trace_id` 和必要索引；
- 扩展健康反馈请求、模型、Mapper 与服务校验，传入 traceId 时校验用户、会话和 Trace 归属；
- 聊天资源卡反馈携带产生该推荐的 `traceId`，浏览页和历史旧数据允许为空；
- 旧 `/api/v1/diet/**` 与已有反馈行保持兼容，不伪造 traceId；
- 评估读取优先按 traceId 精确归因，仅对旧数据保留有明确标记的兼容回退；
- 覆盖越权、Trace/session 不匹配、空 traceId 和迁移兼容测试。

## Done when

新健康聊天反馈可按 traceId 一对一归因，非法归属被拒绝且不落库；旧反馈接口与旧数据仍可使用；真实 MySQL 迁移及相关自动化测试通过。
