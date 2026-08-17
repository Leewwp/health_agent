# 01 — 健身最小推荐闭环与意图安全基础

**Parent:** GitHub #78

**GitHub:** [#79](https://github.com/Leewwp/health_agent/issues/79)

**What to build:** 用户输入“帮我推荐一份适合新手的轻量训练”后，系统进入健身澄清；用户继续输入“胸肌”“大腿”“臀部”等常见说法后，返回仅包含健身动作的推荐结果。该闭环同时建立后续餐食和作息复用的最小意图修正、输入归一和领域安全边界。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] `EXERCISE` 请求缺少部位和目标时只追问健身相关问题；补充“胸肌/胸部/胸大肌”“大腿/小腿/腿部”“臀部/臀肌/臀大肌”等输入后返回 `EXERCISE` 展示块。
- [x] 模型输出和 fallback 共用最小输入归一；“新手/初学者/轻量”归一为入门，“减肥/瘦身”归一为减脂，“自重/无器械/不用器械”归一为徒手。
- [x] 增加 `OTHER + CHAT` 出口；“推荐电影”“你是 AI 吗”等输入不进入餐食、健身或作息检索，也不返回健康资源。
- [x] “不用器械”不产生正向器械约束，“不要练胸”不产生正向胸部约束；未知、冲突或无法安全解释的输入进入澄清。
- [x] 保持现有健康聊天 JSON、会话存储、Provider/Reader 边界和资源类型契约不变；API、现有页面展示和自动化测试覆盖该闭环。

## Answer

已通过 `HealthInputNormalizer`、`HealthIntentRevisionService`、意图 Agent/fallback 共用归一和编排层类型校验完成闭环；聚焦测试与完整 656 测试回归通过。
