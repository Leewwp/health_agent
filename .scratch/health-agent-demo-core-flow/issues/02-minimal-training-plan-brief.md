# 02 — 最小训练计划需求简报与确认

Status: resolved

GitHub: [#85](https://github.com/Leewwp/health_agent/issues/85)

**What to build:** 让用户通过一到数轮健康聊天提交最小训练偏好，在当前会话中形成可检查的计划需求简报，并出现明确的“生成训练计划”确认操作。

**Blocked by:** None — 01 / #84 已于 2026-08-19 在本地完成；本票进入当前 frontier。

- [x] 简报在会话 JSON 中使用独立 `planBrief` 命名空间，不与普通推荐 `slots` 混用；只包含训练目标、部位、器械、难度、可训练日期、时间范围和可确定执行的硬约束，作息与餐食偏好不作为必填。
- [x] 只有明确 PLAN 上下文会更新简报；普通餐食/动作推荐不污染它。当前会话可以合并和纠正简报字段，纠正会使旧确认失效，新会话不会继承旧简报，旧 session JSON 保持可读取。
- [x] 年龄、身体风险和能量目标只读现有健康档案；缺档案时保留简报并返回类型化“完善健康档案”操作，保存后能回到同一会话继续确认，不使用静默默认档案。
- [x] 可训练日期使用“目标周 `weekStart` + 星期集合”的受控表示，时间范围是每次训练必须落入的可用窗口；模糊绝对日期先澄清，不交给模型自由解释。
- [x] 为 30 个 `plan_ready` 审核动作补齐小型、受控训练目标标签；通过前向 Flyway 数据迁移（或等价可审计迁移）更新既有数据库，同时更新 fresh seed、资源版本和 Provider 映射，不能只改 `INSERT IGNORE` seed；不能支持的目标不会仅靠 Prompt 宣称匹配。
- [x] 缺失字段使用确定性问题收集，达到最小完整度后返回简洁确认摘要和类型化生成操作。
- [x] 用户可以在生成前检查偏好摘要；生成端在服务端重读同一会话中最新且已确认的简报，一次确认只对应一个生成 requestId。
- [x] 简报命名空间、普通推荐隔离、合并、纠正后确认失效、周/星期映射、时间窗口、新会话隔离、缺档案往返、训练目标标签和旧 session 兼容具有确定性测试；fresh DB 与已执行 V1–V12 的 legacy DB 都能得到同一标签结果。
- [x] 在真实 Chromium 中验证一到数轮偏好输入、缺档案引导与返回、摘要展示、纠正和确认按钮。

## Answer

- 实现：`PlanBrief`、`PlanBriefService`、`HealthSessionService` 独立 `planBrief` JSON、健康聊天 PLAN 路由、`HealthAction`、前端聊天/档案/计划入口；V13 `exercise_item.training_goals` 与 Provider 受控映射。
- 测试：`mvn test` 670 tests、0 failures；`mvn test -Ditest.mysql=true` 670 tests、0 failures、3 个 Qdrant 门控跳过；`node --test frontend/tests/*.test.mjs` 11/11；`docker compose config --quiet` 通过。
- 浏览器：`http://localhost:18092/#/chat`，桌面 1710×983；多轮简报、缺档案→保存档案→返回原会话、确认按钮和普通域隔离通过。真实模型 smoke 未在本次 fixture 验收中执行。
