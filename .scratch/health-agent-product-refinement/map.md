# 健康 Agent 产品体验与资源能力增强

Status: ready-for-agent

本目录记录 2026-08-20 讨论形成的实现规格与跟踪票。主规格见 [spec.md](./spec.md)。

## Implementation Order

1. **P0** [05 聊天布局与简洁澄清](./issues/05-chat-layout-and-clarification.md)
2. **P0** [04 计划详情与详情抽屉](./issues/04-plan-resource-details.md)
3. **P0** [03 替代推荐与反馈语义](./issues/03-alternative-recommendation-and-feedback.md)
4. **P1** [02 当前安排关系与计划清理](./issues/02-current-assignment-and-plan-cleanup.md)
5. **P1** [06 歧义意图仲裁](./issues/06-ambiguous-intent-arbitration.md)
6. **P1/P2** [01 动作目录浏览与资格扩容](./issues/01-exercise-catalog-expansion.md)

第 03 依赖会话排除契约；第 02 需要数据库当前安排关系；第 04 与第 05 可并行。第 01 必须拆分为“浏览/单次推荐扩容”和“周计划资格扩容”，后者不得阻塞 P0。

## Delivery Matrix

| 任务 | 优先级 | 演示价值 | 复杂度 | 依赖/闸门 | 失败降级 |
|---|---|---:|---:|---|---|
| 聊天布局与澄清 | P0 | 高 | 中 | 浏览器桌面/390px | 保留现有布局，功能可用 |
| 计划详情 | P0 | 高 | 中 | reviewed reader | 展示计划快照，详情提示不可用 |
| 替代推荐与反馈 | P0 | 高 | 高 | 会话排除契约 | 保留单次推荐，关闭换一批 |
| 当前安排与清理 | P1 | 高 | 高 | 新迁移与事务 | 计划仍可生成/激活 |
| 歧义仲裁 | P1 | 高 | 中 | 规则归一化 | 规则结果 + 简短澄清 |
| 动作浏览扩容 | P1 | 中高 | 中 | 导入幂等 | 使用现有审核子集 |
| 动作计划资格扩容 | P2 | 中 | 很高 | 数据质量闸门 | 使用现有 plan_ready 白名单 |

## Release Cut Line

P0 任务全部完成并通过演示脚本后即可进入面试准备。P1 未完成不阻塞发布；P2 默认不纳入本轮承诺。
