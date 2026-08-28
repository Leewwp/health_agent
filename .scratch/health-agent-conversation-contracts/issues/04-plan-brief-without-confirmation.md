# 无确认步骤的餐食/训练计划简报与生成

Type: task
Status: resolved / ready-for-human
Blocked by: none
Priority: P0
GitHub: https://github.com/Leewwp/health_agent/issues/104

## Goal

统一餐食和训练计划交互：简报只展示当前条件与可补充内容，用户直接选择开始生成或补充，不再出现用户不可理解的确认简报阶段。

## Acceptance

- 餐食和训练简报完整时只显示“开始生成”和“补充”，不显示“确认简报”按钮、确认文案或确认阶段。
- 开始生成时服务端校验当前简报完整性并直接创建草稿；生成不得依赖确认字段或确认版本。
- 生成请求重读当前会话，字段修改后不会使用旧简报；请求幂等仍保持。
- 餐食计划默认当前周，餐次和健康目标为最低条件，餐次集合原样传入生成；可选条件不阻断。
- 综合计划的餐食与训练子简报分别完整后才生成，修改一侧不会静默保留另一侧旧状态。
- 餐食计划不能被普通餐食推荐逻辑截断，餐食推荐也不能触发计划生成。
- 餐食计划只生成当前简报选择的餐次；单餐或双餐按所选餐次归一化每日热量，候选足够时七天内优先更换餐食，候选不足时按最少重复继续生成。
- 综合计划默认餐食到训练有序收集，共享目标可以继承但两侧简报保持隔离；单侧字段修改只更新该侧并重新展示“开始生成”入口。

## Notes

删除确认状态时需评估现有持久化数据兼容和写入清理；不新增数据库迁移来重建另一套状态。

餐次传递、单餐/双餐热量归一化、餐食跨日软多样性和综合计划有序收集沿用前置实现票 [#102](https://github.com/Leewwp/health_agent/issues/102)，本票不得回退这些不变量。

## 实现与验收记录（2026-08-28）

- 模型层：`PlanBrief`/`MealPlanBrief` 删除 `confirmed/confirmationVersion/confirmedAt` 字段及 `confirm()/isConfirmedAndComplete()/invalidate()`；完整性判定统一为 `isComplete()`。
- 服务层：`PlanBriefService`/`MealPlanBriefService` 删除确认分支与 `isConfirmation`，`UpdateResult` 去掉 `confirmedNow`。
- 编排层：简报完整即提供"开始生成（GENERATE_PLAN）+ 补充（CONTINUE_*_PLAN_BRIEF）"两个动作，无确认按钮/确认文案/确认阶段；`HealthNextAction.CONFIRM_PLAN_BRIEF` 枚举删除。
- 生成门槛：`TrainingPlanGenerationService.requireCompleteBrief`、`MealPlanGenerationService`、`CompositePlanGenerationService`、`WeeklyPlanService.persistScopedGeneratedDraft` 全部改为"服务端重读会话 + `isComplete()` + 目标周一致"，生成 metadata 不再写任何确认版本。
- 持久化兼容：`HealthSessionService` 不再写入确认字段；旧会话 JSON 中的 `confirmed/confirmationVersion/confirmedAt` 读取时忽略（无迁移、无新表）。
- 前端：chat.js 删除确认动作处理，GENERATE_PLAN 动作文案"开始生成"；plans.js 文案改为"从当前聊天简报生成/正在读取当前简报"。
- 不变量回归：餐食计划只生成所选 `mealTimes` 并按所选餐次归一化热量、七天优先换餐、综合计划餐食→训练有序收集与两侧隔离均保持（`MealPlanPickerTest`、`WeeklyPlanComposerServiceTest`、编排器同构测试继续通过）。
- 测试：`HealthOrchestratorServiceTest`（38：餐食简报完整即开始生成+补充、综合两侧完整后开始生成+单侧修改即时生效、训练简报完整出现开始生成+字段纠正即时生效）、`PlanBriefServiceTest`、`MealPlanBriefServiceTest`、`WeeklyPlanServiceTest`、`HealthPlanRiskGuardControllerTest`、前端 `chat-plan-actions.test.mjs`（完整时只出现开始生成/补充且无"确认"）同步改写。
