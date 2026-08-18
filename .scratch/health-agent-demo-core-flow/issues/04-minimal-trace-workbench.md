# 04 — Trace 最小诊断工作台

Status: ready-for-agent

GitHub: [#87](https://github.com/Leewwp/health_agent/issues/87)

**What to build:** 让管理员在一个可读页面中解释餐食推荐、动作推荐和训练计划的耗时、模型、解析、Guard 与降级路径，而不建设完整观测平台。

**Blocked by:** 01 — 推荐主流程延迟与交互闭环；03 — 受约束 Agent 训练计划草稿闭环。

- [ ] Trace 请求摘要同时展示请求状态和独立诊断结果，支持 `SUCCESS + DEGRADED`。
- [ ] 展示总耗时、Agent 聚合耗时、调用次数、降级次数、实际模型、token 未提供状态、解析结果和 fallback 原因。
- [ ] 按 `stepOrder` 展示路由、检索、Agent、Guard 和持久化时间线，能识别主要耗时阶段。
- [ ] 嵌套 payload 在安全可解析时格式化，不可解析时保留文本；原始脱敏 JSON 作为折叠次级视图。
- [ ] 长 trace/session ID 和 payload 不撑破页面，桌面和移动端不存在文档级横向溢出。
- [ ] 继续用 `ADMIN_TOKEN` 保护管理员 API；普通配置不把 session、trace、模型、内部枚举和版本字段泄漏到用户页面，面试演示配置只允许一个低干扰“查看本次 Trace”入口并在鉴权后定位该请求。
- [ ] 后端合同和前端渲染覆盖 `SUCCESS + DEGRADED`、null token、嵌套 JSON、长 ID、超时与完成但解析失败。
- [ ] 在真实 Chromium 中从餐食推荐、动作推荐和训练计划的演示入口进入各自对应 Trace，验证未配置/错误 Admin Token 被拒绝，正确鉴权后时间线和 Agent/fallback 证据可读。
