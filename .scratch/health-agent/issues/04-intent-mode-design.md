# 04 跨品类意图与模式体系设计

- Type: grilling
- Status: resolved
- Blocked by: —

## Question

状态机如何扩展以支持**饮食/健身/作息三品类**与"**单次推荐 vs 规划**"的区分？前序方向是继续扩展意图枚举并增加会话模式；本次审计要求先比较该方案与“品类 × 任务类型”的正交模型，避免同时存在 `EXERCISE_PLAN` 和 `mode=PLAN` 这类重复状态。本 ticket 细化到可实现的规格：

1. **分类结果结构**：推荐拆为 `domain = MEAL / EXERCISE / ROUTINE / COMPOSITE`、`task = CHAT / RECOMMEND / PLAN / ADJUST`、`riskFlags`，而不是为每个组合继续增加枚举。比较两种接口对路由、持久化和测试的影响后再裁决。
2. **CLARIFY_NEEDED 在多品类下的澄清顺序**：先定品类再定细节？跨品类时澄清怎么编排？
3. **任务类型与会话阶段**：区分用户本轮想做什么（task）和状态机当前走到哪里（phase）；避免用一个 `mode` 同时承担两种含义。
4. **HEALTH_RISK 扩展**：是否覆盖健身（伤痛风险、过度训练）与作息（熬夜危害、睡眠债）提示。
5. **MEAL_ADJUST 的跨品类对应**：换动作、换时段、换食物类别的调整诉求怎么识别与处理。

约束：复用现有 intent→slot→clarify→recommend/plan→risk→persist 骨架；改动要控制在 1-2 周可实现范围内。

技能：/grilling、/domain-modeling。产出：分类结果 schema + 会话阶段定义 + 澄清顺序规则 + 迁移兼容方式 + 对 orchestrator 与 prompt 文件的改动点。

## Answer

2026-08-10 已决策：采用正交分类，不继续为每个品类和任务组合增加独立枚举。

```text
domain = MEAL | EXERCISE | ROUTINE | COMPOSITE
task = CHAT | RECOMMEND | PLAN | ADJUST
riskFlags = [...]
phase = 会话当前执行阶段
```

- 综合周计划使用 `domain=COMPOSITE`、`task=PLAN`。
- 换动作、换餐食或换作息时段统一使用 `task=ADJUST`，由具体资源类型和槽位决定调整范围。
- `task` 表示用户本轮意图，`phase` 表示状态机进度，两者不再由同一个 `mode` 承担。
- 健身伤痛、训练过量和作息风险进入 `riskFlags`，不再扩展为新的意图枚举。
- 多品类澄清先确定品类和任务；若用户明确要求综合周计划，则进入综合计划澄清，按健康档案、硬约束、时间安排和领域细节的顺序补齐。

迁移时保留现有饮食意图的兼容映射，由新分类结果驱动路由。
