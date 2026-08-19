# 04 — Trace 最小诊断工作台

Status: resolved

GitHub: [#87](https://github.com/Leewwp/health_agent/issues/87)

**What to build:** 让管理员在一个可读页面中解释餐食推荐、动作推荐和训练计划的耗时、模型、解析、Guard 与降级路径，而不建设完整观测平台。

**Blocked by:** None — 03 / #86 已完成；01 / #84 已于 2026-08-19 在本地完成。

- [x] Trace 请求摘要同时展示请求状态和独立诊断结果，支持 `SUCCESS + DEGRADED`。
- [x] 展示总耗时、Agent 聚合耗时、调用次数、降级次数、实际模型、token 未提供状态、解析结果和 fallback 原因。
- [x] 按 `stepOrder` 展示路由、检索、Agent、Guard 和持久化时间线，能识别主要耗时阶段。
- [x] 嵌套 payload 在安全可解析时格式化，不可解析时保留文本；原始脱敏 JSON 作为折叠次级视图。
- [x] 长 trace/session ID 和 payload 不撑破页面，桌面和移动端不存在文档级横向溢出。
- [x] 继续用 `ADMIN_TOKEN` 保护管理员 API；普通配置不把 session、trace、模型、内部枚举和版本字段泄漏到用户页面，面试演示配置只允许一个低干扰“查看本次 Trace”入口并在鉴权后定位该请求。
- [x] 后端合同和前端渲染覆盖 `SUCCESS + DEGRADED`、null token、嵌套 JSON、长 ID、超时与完成但解析失败。
- [x] 在真实 Chromium 中从餐食推荐和训练计划入口进入对应 Trace，验证未配置/错误 Admin Token 被拒绝，正确鉴权后时间线和 Agent/fallback 证据可读。

## Answer

- 实现：`AgentTraceService` 诊断聚合与 token 状态、`RequestTraceRow` 合同、`frontend/assets/js/admin/traces.js` Trace 列表/详情/时间线/脱敏 JSON 展示；训练计划 Trace 写入 Agent/FALLBACK、模型、Prompt/契约/Guard 版本和候选元数据。
- 测试：`AgentTraceDiagnosticTest`、Trace 脱敏与既有 admin token 测试随 `mvn test` 670 tests、0 failures 通过；MySQL 门控 670 tests、0 failures；前端行为测试 11/11。
- 浏览器：`http://localhost:18092/#/admin/traces`，桌面 1710×983 与窄视口 390×844。无 token/错误 token 浏览器 fetch 均返回 401；正确 token 后展示 `SUCCESS + DEGRADED`、`Token：未提供`、5 步训练计划 Trace 时间线、FALLBACK 原因和脱敏长 JSON；移动端 `documentWidth=390`，无文档级横向溢出。
