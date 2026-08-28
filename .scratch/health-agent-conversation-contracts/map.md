# 健康聊天显式任务路由与计划生成约束

## Destination

让健康聊天、餐食/动作资源浏览和周计划生成遵守单一、可验证的领域边界：新会话只由明确任务词进入流程；计划简报只做展示和补充，不存在用户可见或持久化意义上的独立确认步骤；严格候选不被静默放宽；作息问题不跨域返回餐食或动作资源。

## Notes

- 本规格由 2026-08-28 用户访谈收敛，依据现有 `CONTEXT.md`、ADR-0002、ADR-0014、ADR-0015 以及 #102 审计。
- 当前名称搜索 API/SQL 链路完整，重点风险是浏览页监听器生命周期、运行实例版本和缺少前端回归测试。

## Decisions so far

- “全身”只匹配动作资源明确归一为“全身”的标签，不等于任意单部位动作集合。
- 难度为单选；多难度冲突保留已有值并提示“只能选择一个难度”。
- 零候选不自动放宽；唯一候选允许覆盖指定训练日并明确说明候选不足。
- 餐食和训练计划均采用“简报摘要 + 可补充项 + 开始生成/补充”，移除独立确认按钮、确认阶段和确认版本依赖。
- 新会话只有特定任务词触发流程；活动上下文可短句继承，显式切换暂停旧简报而不清空。
- 作息本轮保持现有有据事实链路；文档/RAG 扩展列为未来方向。
- 快捷问题首批覆盖餐食计划、训练计划、综合计划、餐食推荐、训练动作推荐，并保留可扩展性。
- 跨品类回归以前置合同 [#102](https://github.com/Leewwp/health_agent/issues/102)：餐食计划/综合计划只生成所选 `mealTimes`，单餐/双餐按所选餐次归一化热量，候选足够时七天内优先换餐；综合计划按餐食→训练有序收集并保持两侧简报隔离。当前 #104/#106 实施不得覆盖这些已确认边界。
- 无计划词的综合引导（如“综合安排饮食、训练和作息”）保持 `COMPOSITE + RECOMMEND` 引导；只有组合计划词命中才进入 `COMPOSITE + PLAN`（与 health-eval-v2 基准一致）。

## Child Tickets

- [01 浏览页名称搜索事件生命周期](issues/01-browse-name-search-lifecycle.md)
- [02 健康聊天显式任务路由与跨域隔离](issues/02-explicit-task-routing.md)
- [03 训练严格候选与难度冲突](issues/03-training-strict-candidates.md)
- [04 无确认步骤的计划简报与生成](issues/04-plan-brief-without-confirmation.md)
- [05 作息文档/RAG 未来方向](issues/05-routine-grounded-docs-future.md)

GitHub 父 issue：[\#103](https://github.com/Leewwp/health_agent/issues/103)；子 issue：[#105](https://github.com/Leewwp/health_agent/issues/105)、[#106](https://github.com/Leewwp/health_agent/issues/106)、[#108](https://github.com/Leewwp/health_agent/issues/108)、[#104](https://github.com/Leewwp/health_agent/issues/104)、[#107](https://github.com/Leewwp/health_agent/issues/107)。
