# 23 健康数据 Schema 与迁移契约

- Type: grilling
- Status: resolved
- Blocked by: 09, 12, 13, 16, 21

## Question

冻结健康 Agent 的最终数据模型和迁移策略：健康档案及版本、动作资源与媒体/来源、作息事实、扩展后的餐食、embedding、类型化反馈、周计划及版本快照、幂等结果和 Trace 扩展分别落在哪些表和字段？

必须同时决定从当前 `diet_db.sql` 导入新环境的 Flyway 基线、旧 `diet_sessions` JSON 的兼容窗口、数据归属索引和唯一约束、快照不可变边界、seed/ETL 的执行顺序、备份与回滚方式。产出应是一份可评审的 DDL/迁移顺序，而不是在实现阶段边改表边猜语义。

## Answer

2026-08-10 已按“满足项目需求的最小实现”确认：

- 将当前 `diet_db.sql` 视为 `V1__legacy_baseline`。新环境先创建旧饮食表，再执行健康 Agent 的增量迁移；已存在的旧数据库只执行 Flyway baseline，不重新执行带 `DROP TABLE` 的 dump。后续迁移只增加表、字段、索引和约束，不删除旧字段。
- 保留并扩展 `meal_item`，新增独立的 `exercise_item` 和 `routine_fact`，不建立万能 `health_resource` 表。资源身份由应用层的 `resourceType + resourceId` 表达，领域表保留各自明确字段。
- 餐食和动作资源增加来源、媒体、许可和可用状态字段；动作增加 `plan_ready`。作息事实保存适用范围、数值/文本内容、来源名称、来源链接和版本信息。正式资源只由通过字段、来源和安全门槛的 ETL 写入；媒体不合格时必须清除外链并写入稳定无图状态，而不是排除合格文本资源。
- 健康档案与计划采用以下结构：`health_profile`、`health_profile_version`、`weekly_plan`、`weekly_plan_version`、`weekly_plan_item`。当前档案可以更新；计划生成时保存档案版本。DRAFT 项目可编辑，ACTIVE 版本不可直接修改；编辑 ACTIVE 时复制为新的 DRAFT。
- `weekly_plan_item` 保存 `resourceType`、`resourceId`、日期、时间、备注和计划参数。计划版本激活后，其项目记录和生成依据不可变；历史版本保留，不使用事件溯源。
- `recommend_feedback` 增加 `resource_type`、`resource_id`、`plan_id`、`plan_item_id` 和 `source`；旧数据回填 `resource_type=MEAL`。反馈仍按事件记录，不新增画像表或事件总线。
- `diet_request_trace` 增加 `request_id` 和已保存响应 JSON，通过 `user_id + session_id + request_id` 唯一约束实现请求幂等；失败请求按状态保留，重复成功请求直接返回原响应。
- 迁移与数据装载顺序固定为：旧表基线 → 健康领域 DDL → 字典和作息事实 seed → 餐食/动作 ETL → embedding 生成 → 索引和验收。Seed 使用幂等写入，embedding 失败不阻塞结构化资源上线。
- 生产迁移前必须备份数据库；不执行破坏性回滚，问题通过前向修复或恢复备份处理。旧 `diet_sessions` 在兼容窗口内继续读写，健康会话使用新状态字段/表，不能把旧 JSON 直接当作完整健康档案。

### 推荐表边界

```text
旧表：diet_messages、diet_sessions、diet_slot_option、diet_request_trace、meal_item、recommend_feedback
新增：health_profile、health_profile_version、exercise_item、routine_fact
新增：weekly_plan、weekly_plan_version、weekly_plan_item
新增：meal_item_embedding
```

### 实施约束

1. 新 DDL 必须提供用户归属索引、资源类型/标识索引、计划版本唯一约束和幂等唯一约束。
2. 计划版本激活后禁止原地更新；草稿编辑只能改变草稿项目，不修改资源表中的营养、动作或事实内容。
3. 旧餐食接口继续使用 `meal_item` 和旧字段语义，新健康接口通过适配层返回类型化资源。
4. 最终字段类型、索引名称和 Flyway 版本号在实现前由本票与 22、24、27 号票据共同形成最终 DDL。
