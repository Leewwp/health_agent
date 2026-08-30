# 周模板、餐训适配与任务路由修复

Status: ready-for-agent

## Destination

消除周计划目标周对话负担、餐食/训练时间冲突误报、固定需求指引重复提示，以及计划上下文中无关任务误触发；通过确定性计划上下文路由和受约束歧义仲裁保持任务选择可解释、可回归。

## Spec

- [详细修复规格](spec.md)
- [ADR-0018](../../docs/adr/0018-week-template-training-priority-and-ambiguous-routing.md)

## Implementation order

1. P0：动态需求输入指引和问题 4 的计划上下文优先路由。
2. P1：统一内部周锚点并移除目标周用户必填/对话展示。
3. P1：训练优先的餐训时间适配及 Guard/metadata/Trace 闭环。
4. P1：规则冲突时的单次歧义任务仲裁 Agent。

## Tickets

- [01 动态需求输入指引](issues/01-dynamic-supplementable-guidance.md) — Blocked by: None
- [02 计划上下文优先路由（问题 4）](issues/02-plan-context-first-routing.md) — Blocked by: None
- [03 统一内部周锚点与目标周移除](issues/03-internal-week-anchor.md) — Blocked by: None
- [04 训练优先餐训时间适配](issues/04-training-priority-meal-scheduling.md) — Blocked by: None（聊天修改触发路径的端到端验收与 02 联调）
- [05 歧义任务单次受约束仲裁](issues/05-ambiguous-task-arbitration.md) — Blocked by: 02

## Notes

- 本规格尚未实施生产代码。
- 生产实现须保留当前工作树无关修改，并按 spec 的自动化、MySQL 门控和真实浏览器验收要求交付。
