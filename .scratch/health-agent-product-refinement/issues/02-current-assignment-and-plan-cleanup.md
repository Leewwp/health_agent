# 当前安排关系与计划清理

Type: task
Status: ready-for-agent
Blocked by: none
Priority: P1
Estimated complexity: 高
External dependency: 无
Database migration: 是

## Goal

让餐食和训练可以分别拥有当前安排，并提供可理解的计划清理动作。

## Scope

- 建立按用户与 planScope 区分的当前安排引用。
- “设为当前安排”更新对应品类，不影响另一品类。
- 聊天按本地日期读取当前安排作为软上下文。
- 草稿提供确认后的永久删除。
- 提供取消当前餐食安排/训练安排，只解除引用，不删除历史计划。
- ACTIVE/历史计划禁止直接删除；历史默认保留。

## Data and API Contract

新增独立当前安排关系，逻辑键为 `(user_id, plan_scope)`，值至少包含 `plan_id`、`version_no`、`created_at`、`updated_at`。不要把当前安排直接编码成 `weekly_plan.status`，也不要削弱现有计划生命周期语义。

- `POST /plans/{id}/current`：仅允许用户自己的已确认/ACTIVE 版本，重复调用幂等；按 scope 替换同品类引用，不影响另一品类。
- `DELETE /plans/current?scope=MEAL|EXERCISE`：只解除引用，计划和历史版本保留；重复取消响应需固定。
- 设为当前、取消、草稿删除使用事务和用户归属校验；计划被归档/删除时禁止留下悬空引用。
- 聊天读取使用计划时区的本地日期；当前安排低于本轮明确需求、临时排除和风险规则。

## Acceptance

- 餐食和训练当前安排可并存。
- 当前安排被聊天读取，但用户本轮明确需求优先。
- 删除草稿会级联清理其版本、项目和允许清理的反馈关系。
- 取消当前安排不删除计划。
- 越权、删除 ACTIVE/历史和并发操作有明确错误。
- 当前安排指向版本、归档/删除清理、幂等和并发错误码均有自动化覆盖。
