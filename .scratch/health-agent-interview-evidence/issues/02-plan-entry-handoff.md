# P0：将健康聊天 PLAN 意图衔接到已上线周计划流程

- Type: task
- Status: resolved
- Triage: ready-for-human
- Priority: P0
- Estimate: 0.5-1 天
- GitHub: https://github.com/Leewwp/health_agent/issues/72

## Question

如何移除健康聊天中“周计划即将上线”的过期行为，让 `PLAN` 意图稳定引导用户进入已存在的档案与周计划流程，同时避免未经确认直接产生业务写入？

## Scope

- 后端 `PLAN` 回复改为描述真实可用的档案/计划流程，不宣称功能尚未上线；
- 前端在 `task=PLAN` 的回复中提供明确的计划入口，优先复用现有 hash 路由和按钮样式；
- **已冻结实现契约**：保持 `HealthChatResponse` 现有 JSON 结构，不新增 CTA DTO 或 `HealthNextAction` 枚举值；前端在收到 `task=PLAN` 的回复时派生一个指向 `#/plans` 的入口按钮；
- 入口按钮只负责导航，计划页继续由用户明确点击“生成新草稿”触发写入；
- 本票不在聊天请求内自动调用 `WeeklyPlanService.createDraft`；
- `COMPOSITE` 保持跨品类澄清语义，不与 `PLAN` 混为同一写入动作；
- 补后端契约测试和前端核心交互验证。

## Done when

用户在聊天提出周计划请求后能看到准确文案并通过明确操作进入计划页面；不会因单次聊天自动创建重复草稿；真实浏览器验证 URL、PLAN 输入、入口点击和目标页面加载均通过。

## Implementation contract

- 后端 PLAN 文案应说明“进入周计划页面；如未完善档案先完善档案”，不得出现“即将上线”。
- 前端按钮由 `task === "PLAN"` 派生，目标固定为 `#/plans`；不依赖新增后端字段。
- 契约测试断言 PLAN 响应仍为非写入的 `ANSWER`，且聊天请求不会调用 `WeeklyPlanService.createDraft`。

## Answer

已在提交 `6374494` 完成 PLAN 文案、意图关键词收紧、`#/plans` 导航按钮及会话 4xx 重试；真实浏览器已验证入口导航且聊天不创建草稿。

2026-08-18 #82 live 验收补充覆盖前端快捷问题“帮我安排一下这周的健身计划”：模型与兜底路径共享 PLAN 匹配器，返回 `EXERCISE / PLAN / RESPOND`；点击入口到达 `#/plans` 且无草稿写入。验收中一并修复“新会话”复用匿名默认会话、继承历史风险信号的问题。
