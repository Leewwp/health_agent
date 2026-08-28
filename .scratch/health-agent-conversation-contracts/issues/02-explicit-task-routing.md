# 健康聊天显式任务路由与跨域隔离

Type: task
Status: ready-for-agent
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
