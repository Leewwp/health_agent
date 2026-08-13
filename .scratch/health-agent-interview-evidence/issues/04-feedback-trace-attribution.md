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
- **已冻结数据库契约**：新增 V8 迁移，`recommend_feedback.trace_id` 使用 `varchar(128) NULL`，增加 `(user_id, trace_id)` 普通索引；不建立外键，不改变既有 `item_id`/类型化资源兼容列；
- 扩展健康反馈请求、模型、Mapper 与服务校验，传入 traceId 时校验用户、会话和 Trace 归属；
- **已冻结请求契约**：健康反馈请求新增可选 `traceId`；聊天响应的每张资源卡从所属回复继承该 `traceId`，浏览页、计划页和历史旧数据保持为空；
- **已冻结校验顺序**：先按当前匿名用户查询 Trace，再要求 `trace.sessionId == request.sessionId`；Trace 不存在、用户不匹配或会话不匹配均返回统一 404/无权访问错误，并保证 insert 未执行；
- **已冻结资源归属**：`traceId` 非空时必须同时提供 `resourceType/resourceId`，且该类型化资源必须存在于 Trace 最终 `response_json.displayBlocks`；同一会话中其他轮次出现过的资源不能通过校验，Trace/资源不匹配返回 400 且不写入；
- 旧 `/api/v1/diet/**` 与已有反馈行保持兼容，不伪造 traceId；
- **已冻结评估归因**：优先按 `trace_id` 精确读取；trace 为空的旧反馈才允许 session/时间窗口回退，报告必须标记 `EXACT_TRACE` 或 `LEGACY_SESSION_FALLBACK`；
- 覆盖越权、Trace/session 不匹配、Trace/resource 不匹配、空 traceId 和迁移兼容测试。

## Done when

新健康聊天反馈可按 traceId 一对一归因，非法归属被拒绝且不落库；旧反馈接口与旧数据仍可使用；真实 MySQL 迁移及相关自动化测试通过。

## Implementation contract

- `trace_id` 允许为空；非空值长度上限与 `diet_request_trace.trace_id` 一致（128 字符）。
- 不补造旧数据的 traceId；旧饮食接口继续写 NULL。
- 精确归因只接受最终响应实际展示过的资源卡；不得仅凭同一 user/session 建立关联。
- 评估读取和聚合必须能区分精确归因与兼容回退，不能把回退结果伪装成精确反馈。
