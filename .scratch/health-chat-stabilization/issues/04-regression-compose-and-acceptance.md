# 04 — 全链路回归、Compose 修复和基本验收

**Parent:** GitHub #78

**GitHub:** [#82](https://github.com/Leewwp/health_agent/issues/82)

**What to build:** 从新匿名会话开始，餐食、健身、作息、无关对话、风险和跨领域切换都能稳定演示；Compose 配置可以通过静态校验。该票只收口验证和必要的部署边界，不扩展新的业务能力。

**Blocked by:** #79 / 01 — 健身最小推荐闭环与意图安全基础；#80 / 02 — 餐食推荐、调整和跨品类切换闭环；#81 / 03 — 作息事实和计划入口最小闭环。

**Status:** ready-for-human

- [x] 修复 Compose `depends_on` YAML 结构，`docker compose config --quiet` 成功，且不改变现有 Qdrant 可选降级语义。
- [x] 新匿名会话完成“帮我推荐一下”“中午吃什么”“适合新手的轻量训练”“胸肌/大腿/臀部”“换一批”“停止喝咖啡”“推荐电影”和餐食/健身互切脚本。
- [x] 所有响应的 `displayBlocks` 类型与 `domain` 一致；风险、旧饮食接口、计划、反馈和资源读取边界不回归。
- [ ] 执行编译、聚焦测试、完整测试、可用时的 MySQL 门控，以及一次真实模型和浏览器冒烟；模型文案不做易变的精确断言。
- [x] 验收证据记录用户输入、响应阶段、领域、资源类型和环境门控结果；不记录真实 key、完整 prompt 或敏感用户数据。

## Answer

确定性与集成收口已完成：`docker compose config --quiet`、656 个完整测试、34 个真实 MySQL 门控和浏览器 UI 复验均通过。真实模型最小探测对 `deepseek-v4-flash-0731` 与 `qwen-turbo` 均在模型选择前返回 `403 access_denied / API-Key restrictions`，需要维护者在百炼控制台解除 Key 的 IP/来源/业务空间限制，或重新签发可访问当前兼容端点的 Key 后完成最后一次 live smoke。
