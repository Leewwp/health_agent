# 训练严格候选与难度冲突

Type: task
Status: resolved / ready-for-human
Blocked by: none
Priority: P0
GitHub: https://github.com/Leewwp/health_agent/issues/108

## Goal

使训练计划严格尊重训练部位、器材、难度、目标和审核资格，修复“全身”放行任意部位以及多难度静默截断。

## Acceptance

- “全身”只命中明确全身标签，腿部/背部专属动作不能进入全身计划。
- 严格条件零候选时不自动放宽，返回候选不足说明和追加/修改条件操作。
- 严格条件唯一候选时按全部指定训练日复用该动作，并明确提示候选不足。
- 多难度输入被识别为冲突；已有难度时保持原值并提示“只能选择一个难度”，没有已有值时要求重新选择。
- Agent、规则降级和计划 fallback 使用同一候选白名单与约束；不能由 Agent 注入不满足条件的动作。
- 计划覆盖所有指定日期，时间和风险 Guard 继续生效。

## Notes

不扩充动作数据集、不引入自动放宽策略、不把辅助肌群或动作名称推断为全身资格。

## 实现与验收记录（2026-08-28）

- 全身严格匹配：移除 `TrainingPlanGenerationService.filterCandidates` 的 `allBody` 旁路，候选 `bodyParts` 必须包含简报部位；种子新增 9009 开合跳（全身/徒手/入门/减脂）作为唯一明确全身候选。
- 零候选不放宽：错误文案覆盖部位/器材/难度/目标四个维度（"当前审核动作库没有同时满足部位、器材、难度和目标的动作，请回到聊天补充或修改训练条件"），且不写入计划。
- 唯一候选复用：候选数少于训练日数时按指定训练日复用并在生成说明中明确"符合全部条件的候选动作只有 N 个……已按指定训练日复用候选"（`deterministicExplanation`）。
- 多难度冲突：`PlanBriefService.interpret` 检测难度多值——已有值保持原值并提示"一次只能选择一个难度……已保留当前难度"，无已有值要求重新选择；Agent 候选路径经 `applyAgentCandidate` 走同一归一化并保留冲突提示。
- 共享白名单：Agent 契约允许集、Guard 与 fallback 继续消费同一 `filterCandidates` 结果；`validateForPersistence` 写入前复跑资源目录校验。
- 清理：`CandidateSelection.goalRelaxed/difficultyRelaxed` 恒 false 死字段与对应 metadata 一并移除。
- 测试：`TrainingPlanGenerationServiceTest` 14 用例（新增全身零候选不放宽、单部位动作被拒+唯一全身候选复用、唯一候选三日复用说明）；`PlanBriefServiceTest` 15 用例（新增首次多难度冲突、已有难度保留、Agent 多难度冲突）。
