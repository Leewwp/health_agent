# 03 — 受约束 Agent 训练计划草稿闭环

Status: resolved

GitHub: [#86](https://github.com/Leewwp/health_agent/issues/86)

**What to build:** 用户确认训练偏好后，Plan Agent 在审核动作候选集中真实选择动作和排期，系统通过确定性 Guard 或 fallback 生成可查看、可激活、可追溯的每周个人计划草稿。

**Blocked by:** None — 02 / #85 已完成。

- [x] 对调用方提供一个高层训练计划生成操作，内部封装 Prompt、结构化解析、候选白名单、Guard、fallback 和 Trace 元数据。
- [x] Agent 输入只包含必要档案摘要、服务端重读的同会话已确认偏好和正式 Provider 返回的 `plan_ready` 候选；客户端不得提交可替换权威简报的副本。
- [x] Agent 只能选择候选资源 ID、训练日期、开始时间和单次时长；组数、次数和其他训练剂量由受控 Java 规则生成。
- [x] 未知 ID、非计划资格动作、偏好过滤不命中、非可训练日、训练时段未包含在用户时间窗口、周日期越界、时间冲突、连续部位、风险和数量/时长边界均由确定性 Guard 拒绝。
- [x] 模型生成与初步校验发生在事务外；只有最终计划进入短事务，失败不产生半成品或额外 ACTIVE。
- [x] 超时、上游异常、非法 JSON、越界候选或校验失败不重试模型，立即使用确定性组合器生成草稿；fallback 与 Agent 共用已确认简报、过滤后候选和 Guard，必须同样满足日期、时间、器械、难度、部位、目标与硬约束，不能退回旧固定排期。
- [x] 计划版本快照和 Trace 记录 `AGENT/FALLBACK`、实际模型、契约/Prompt 版本、候选来源、Guard 版本和降级原因。
- [x] 高层生成响应返回 `planId`、`traceId`、`generationSource` 和用户可读状态；计划页优先展示训练安排，并把 `AGENT/FALLBACK` 映射为“Agent 生成/规则降级”等中文来源标签；确定性餐食/作息降为辅助内容，不暗示整份周计划由模型生成。
- [x] 同一用户/会话/requestId 的并发或重复生成只调用一次 Agent、只创建一份草稿，并复用同一结果；不同 requestId 才能创建新草稿。
- [x] 用户点击一次生成后展示等待动画和非实时阶段文案；本地 Chromium 生成在 20 秒内完成并进入计划页，可查看七日训练安排、辅助餐食/作息并激活有效草稿。
- [x] 最高层 fixture 场景跑通“简报确认 → fallback 草稿 → 查看 → 激活 → Trace”，并覆盖 Agent 契约与 fallback 两条路径。
- [x] 真实 MySQL 测试覆盖失败回滚、requestId 防重复、唯一 ACTIVE、激活重检和模型等待期间无长事务。
- [x] 发布前有一次有界真实模型成功生成 smoke；模型不可用时同一份已确认简报仍可 fallback，且结果不违反偏好与硬约束。

## Answer

- 实现：`TrainingPlanGenerationService`、`PlanAgentOutput`、`GenerateTrainingPlanRequest`、`TrainingPlanGenerationResponse`、`training-plan-agent.txt`、V14 来源/元数据迁移；复用 `PlanValidationService`、`WeeklyPlanService` 和审核 `plan_ready` Provider。
- 测试：`TrainingPlanGenerationServiceTest` 覆盖合法输出、非法 JSON/未知动作、时间窗口、fallback、requestId 幂等；完整 `mvn test` 684 tests、0 failures；MySQL 门控 684 tests、0 failures；显式 `LiveTrainingPlanSmokeTest` 使用 `qwen-turbo` 真实成功，来源为 `AGENT`、解析为 `PARSED`。
- 浏览器：`http://localhost:8092/#/chat` → `#/plans` → `#/admin/traces`，桌面 1710×983、移动端 390×844；确认按钮直接提交，真实 Agent 生成 3 个审核训练项目和七日视图，计划页可直达对应 Trace，原始 JSON 默认折叠。截图见 `docs/evidence/issue-86-agent-*.png`。
