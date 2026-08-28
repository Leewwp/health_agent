# 健康聊天显式任务路由与跨域隔离

Type: task
Status: resolved / ready-for-human
Blocked by: none
Priority: P0
GitHub: https://github.com/Leewwp/health_agent/issues/106

## Goal

让新会话只有明确任务词进入餐食推荐、训练动作推荐、餐食计划、训练计划或综合计划；活动上下文可安全继承，显式切换暂停旧简报，作息和无关问题不跨域。

## Acceptance

- 新会话模糊短句返回 `OTHER + CHAT` 或领域澄清，不猜测餐食/训练。
- 明确任务词仍命中正确 domain/task；综合计划保持 `COMPOSITE + PLAN`。
- 餐食↔训练显式切换时，旧简报保留但不进入当前澄清、检索或资源卡；明确继续表达可恢复。
- “晚上几点前停止喝咖啡”和“晚上几点后停止锻炼”只返回 `ROUTINE` 事实/边界说明，不返回餐食或动作资源。
- 任何响应的 display blocks 与 domain 一致；无关问题无健康资源卡。
- 快捷问题提供五个可扩展基础入口：餐食计划、训练计划、综合计划、餐食推荐、训练动作推荐。

## Notes

复用现有 HealthOrchestratorService、IntentRuleService、HealthIntentRevisionService 和领域投影；不建立第二套意图状态机。

餐食/综合计划的餐次、热量和跨日多样性规则沿用前置实现票 [#102](https://github.com/Leewwp/health_agent/issues/102)；本票只负责路由与跨域隔离，不得因入口修复改变生成契约。

## 实现与验收记录（2026-08-28）

- 任务词门槛：`HealthTaskEvidence`（新增，`src/main/java/com/diet/health/intent/HealthTaskEvidence.java`）区分"明确任务词"与"槽位别名"；`IntentRuleService.fallback/fastPath` 与 `HealthIntentRevisionService.revise` 共享同一判定，新会话槽位短句（清淡一点/入门徒手/便利店速食/胸肌等）统一 `OTHER + CHAT` 领域澄清且不写会话槽位。
- 跨域隔离：显式切换保留旧简报（会话 JSON 继续持久化 `planBrief`/`mealPlanBrief`），当前领域投影经 `HealthInputNormalizer.project`，"回到餐食/训练计划"恢复暂停简报（`HealthPlanIntentMatcher.PLAN_PHRASES`）。
- 作息路由：`isRoutineFact`/`explicitDomain`/`RoutineModule.KEYWORD_CATEGORY` 补"锻炼"关键词，"晚上几点后停止锻炼"返回 ROUTINE 事实卡且全部带 `sourceName`。
- 综合计划：无计划词的综合引导保持 `COMPOSITE + RECOMMEND`；组合计划词命中固定 `COMPOSITE + PLAN`（与 health-eval-v2 基准 32/32 域命中、任务断言一致）。
- 快捷问题：`frontend/assets/js/pages/chat.js` 首批五入口（餐食计划/训练计划/综合计划/餐食推荐/训练动作推荐）。
- 测试：`IntentRuleServiceTest`（20，含新会话模糊短句矩阵与活动上下文继承）、`HealthOrchestratorServiceTest`（38，含新会话澄清、五类明确任务词、停止锻炼作息隔离、餐食简报暂停恢复）、`HealthEvalRunnerTest` 36 条基准全绿。
