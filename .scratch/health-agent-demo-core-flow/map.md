# 健康 Agent 面试演示核心流程 — 追踪地图

Status: ready-for-human

## Destination

在单实例云端演示中稳定跑通餐食推荐、动作推荐、受约束的 Agent 训练计划和 Trace，并具备正常延迟、加载反馈、失败恢复和主要页面体验。所有简历技术点必须有代码、测试、Trace 或实跑证据。

## Authoritative documents

- 执行规格：[spec.md](spec.md)
- 未来任务：[future.md](future.md)
- 原始归档：[archive-original-spec-2026-08-18.md](archive-original-spec-2026-08-18.md)

## Delivery graph

1. [01 推荐主流程延迟与交互闭环](issues/01-recommendation-latency-and-ux.md) / [GitHub #84](https://github.com/Leewwp/health_agent/issues/84) — 已完成，等待人工/远端关票。
2. [02 最小训练计划需求简报与确认](issues/02-minimal-training-plan-brief.md) / [GitHub #85](https://github.com/Leewwp/health_agent/issues/85) — 已完成，等待人工/远端关票。
3. [03 受约束 Agent 训练计划草稿闭环](issues/03-constrained-agent-training-plan.md) / [GitHub #86](https://github.com/Leewwp/health_agent/issues/86) — 已完成，等待人工/远端关票；真实模型成功 smoke 尚未完成。
4. [04 Trace 最小诊断工作台](issues/04-minimal-trace-workbench.md) / [GitHub #87](https://github.com/Leewwp/health_agent/issues/87) — 已完成，等待人工/远端关票。
5. [05 Qdrant 可选 Hybrid RAG 真实证据](issues/05-qdrant-hybrid-rag-evidence.md) / [GitHub #88](https://github.com/Leewwp/health_agent/issues/88) — 已完成，等待人工/远端关票。
6. [06 云端发布与面试验收](issues/06-cloud-release-and-interview-acceptance.md) / [GitHub #89](https://github.com/Leewwp/health_agent/issues/89) — 当前 frontier，等待云端发布验收。

当前 frontier：06 / #89 发布验收。#84–#88 的本地实现和证据已完成，#85–#87 的真实模型成功 smoke 仍是发布前待补外部证据，不影响本地确定性闭环验收。

## Review corrections (2026-08-18)

- 新匿名用户生成计划前需要现有健康档案；主线增加“保留简报 → 完善档案 → 返回确认”，不引入默认档案或新账号系统。
- `planBrief` 与普通推荐 `slots` 隔离，fallback 必须遵守同一份已确认偏好，避免演示前序动作推荐污染计划或模型失败后退回固定排期。
- 面试演示配置保留受 `ADMIN_TOKEN` 保护的对应 Trace 跳转；普通配置隐藏内部诊断字段。
- 六票逐项估算合计为 9–14.5 人日。原 7–11 人日与逐票数字不一致，不能继续称为“投入”；Qdrant 并行只能缩短日历时间。
- 已确认发布门槛：等待反馈 ≤100ms；明确推荐 P95 ≤3 秒且单次 ≤5 秒；歧义 Agent 请求 ≤15 秒；训练计划生成 ≤20 秒并在上限内 fallback。云端主演示默认启用 Qdrant，但始终保留 Structured 降级。

## Completion updates (2026-08-19)

- #84 已完成：确定性意图/Clarify 快路径、统一请求截止时间、前端等待与失败恢复、详情交互和 Trace 来源证据已落地；全量双门控 661/0/0，前端行为测试 11/11，真实 Chromium 补充验收完成。
- #88 已完成：Qdrant 真实索引 295/295，固定 60 条评估为 `REAL_HYBRID`、零降级；报告与发布证据已同步，结果不宣称召回提升。

## Completion evidence (2026-08-19, #85-#87)

- #85：独立 `planBrief` 会话命名空间、确定性多轮收集/确认/纠正、缺健康档案往返和 V13 训练目标标签迁移已完成；浏览器验证了简报、纠正、档案保存后回到原会话和生成入口。
- #86：受约束计划生成服务已完成服务端简报重读、审核 `plan_ready` 候选白名单、确定性 Guard、规则 fallback、V14 生成来源/元数据和 requestId 幂等；浏览器验证了 fallback 草稿、七日安排和激活。
- #87：Trace 诊断摘要、`SUCCESS + DEGRADED`、FALLBACK 原因、事件时间线、token 状态、脱敏 JSON 和 admin 鉴权展示已完成；浏览器验证了正确鉴权展示及无 token/错误 token 的 401 拒绝。
- 自动化证据：Maven 三组门控均 670 tests、0 failures；普通门控跳过 37 个环境场景，MySQL 门控跳过 3 个 Qdrant 场景，MySQL+Qdrant 门控 0 skipped；前端行为测试 11/11；Compose 配置通过。
- 外部证据边界：本轮使用 fixture/fallback 完成浏览器闭环，真实模型成功 smoke 尚未执行，不将 fallback 结果描述为真实模型成功。

## GitHub synchronization

- Parent issue: [#83 健康 Agent 面试演示核心流程：受约束 Agent 训练计划与可观测降级](https://github.com/Leewwp/health_agent/issues/83)
- Ticket issues: [#84](https://github.com/Leewwp/health_agent/issues/84)、[#85](https://github.com/Leewwp/health_agent/issues/85)、[#86](https://github.com/Leewwp/health_agent/issues/86)、[#87](https://github.com/Leewwp/health_agent/issues/87)、[#88](https://github.com/Leewwp/health_agent/issues/88)、[#89](https://github.com/Leewwp/health_agent/issues/89)
- GitHub 原生父子关系与 blocking edges 已建立；本地 #84/#88 已标记 `resolved`，远端是否关闭需按人工评审流程同步，不能由本地状态推断。

## Scope decisions

- 训练计划保留，改为 Agent 在审核候选集内选择动作并排期；Java Guard 负责安全、资源、时间和事务。
- 作息退出核心演示，但保留现有确定性模块和数据兼容。
- Qdrant 保持可选运行，必须有一次真实启用证据，不夸大召回收益。
- MOVE/REPLACE、完整 Trace 工作台、个性化作息和生产化能力进入未来任务。
