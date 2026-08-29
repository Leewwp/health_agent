# 健康聊天计划简报补充回路与推荐前预检修复

## Destination

让计划简报中的自由文本补充不再丢失或被误路由：活跃简报中补充进入简报、明确推荐词才转推荐；综合简报有明确侧归属与生命周期；简报承载菜系/口味/营养/便利性（含未支持偏好的诚实表示与确定性提取）并传入生成；五条主流程执行前展示已确认条件与可补充项。修复以单一结构化共享路由判定、耐久生成幂等和并发安全会话写入收口。

## Notes

- 本规格由 2026-08-29 用户实测四个失败对话收敛，根因已逐条在代码中核实（详见 spec.md Problem Statement）。
- 上一轮规格的“可补充项、菜系/口味/便利性可选条件”只实现一半；本轮补齐遗漏，不改变旧饮食链路。
- v1 -> v2 已补综合侧归属、未支持偏好表示、候选标签接口、前后端契约、按钮规范、生命周期、回归矩阵和关键词分类。
- v2 -> v3 已补生成关闭落点、未知菜系确定性提取、fixture 标签、generationNotes 字段形状、判定优先级和按日回退例。
- v3 -> v3.1 已补生成写入耐久幂等/失败恢复、`isUnrelated` 前的可选偏好解析、营养标签来源、会话并发合并、版本详情 API、确认指纹、ALTERNATIVE/ADJUST 逃生口和完整回退算法。
- v3.1 -> v3.2 已补 `tastePreferences` 到口味/营养标签的逐值映射与跨字段 AND、`persistAndRespond` 整体保存并发保护、候选 1/2 预检断言翻转清单、确认指纹 canonical 序列化、“开始推荐”确认短语、fixture 标签新增措辞及 BOTH 无前缀共享周表达回归例。
- 基线 commit `097be31`；修复前测试基线 749。2026-08-29 完成餐食标签修复规格：菜系/餐食类型均为数组，多选同维度 OR、跨维度 AND；旧规格已由 `../health-agent-meal-facet-repair/spec.md` 取代。
- 2026-08-29 复验：`mvn test` 817 项 0 失败/错误（45 跳过），`mvn test -Ditest.mysql=true` 817 项 0 失败/错误（4 跳过），前端 40/40，ETL 295 条可重跑，真实浏览器 `http://localhost:8092` 通过。五张子票保留历史 resolved 记录，当前总票据转 `ready-for-human`。

## Decisions so far

- 共享结构化判定只保留一份实现，结果为 `briefActive/activeSide/escape/reason`；裁决顺序为风险 > 切域/作息 > 替代/换一批 > 普通推荐 > 生命周期 > 侧归属 > 字段解析。
- 逃生口分为普通推荐 `RECOMMEND`、替代推荐 `ALTERNATIVE`、切域/作息 `DOMAIN_OR_ROUTINE`；其余自由文本在活跃简报中进入简报处理器。
- 生命周期写入 `_meta.briefLifecycle`，MEAL/EXERCISE 各自 OPEN/PAUSED/GENERATED；生成入口通过 `health_plan_write_request` 的 `GENERATE_<scope>` 记录实现耐久幂等（同一用户 requestId 全局唯一，跨 session/scope 冲突），回写通过数据库行锁合并。
- 综合侧归属：NONE -> 餐食；焦点侧跟随未完成侧；BOTH 无前缀必须澄清；显式“餐食：/训练：”可跨侧修改。
- 餐食简报固定字段为 `cuisines`、`foodTypes`、`tastePreferences`、`convenience`、`unsupportedPreferences`；确定性菜系解析先于 unrelated 门槛，模型未知 rawSlots 不得越权产生未支持值。
- 候选字段为 `cuisineTags/tasteTags/nutritionPreferenceTags/convenienceTags`；审核库从 cuisine/taste/health_goal/convenience 投影，fixture M1-M9 使用固定标签表。过滤同字段 OR、跨字段 AND；偏好池为空或无法形成完整组合时整天回退，但保留餐次/唯一性/热量/多样性约束。
- generationNotes 固定为 `{unsupportedPreferences: string[], fallbacks: [{date, mealTimes, unmetPreferences}]}`，写入 metadata 和版本快照，并由计划详情与版本详情 API、计划页展示；旧计划返回空对象。
- 推荐确认使用稳定指纹 `_meta.recommendationConfirmationKey`；槽位、领域、资源版本、替代推荐或新会话变化使确认失效。
- 前端契约固定 `{key, label, examples: string[], filled: boolean}`；计划摘要和 generationNotes 不依赖解析 speechText。
- 被替代的路由类关键词副本删除；字段识别词保留但不得参与领域/任务路由。
- `tastePreferences` 每个值必须按规范类别唯一映射到 `tasteTags` 或 `nutritionPreferenceTags`；字段之间 AND，同字段内 OR。
- 预检确认指纹与确认短语固定为前后端同一合同；候选 1/2 直出断言按预期翻转清单更新。

## Completion Notes

- 共享判定：`HealthBriefRouter` + `BriefRoutingDecision/BriefSide/BriefEscape`；会话新增 `_meta.briefLifecycle`、`_meta.recommendationConfirmationKey`；`HealthSessionService.saveMerged` 行锁合并写 + `markBriefGenerated` 生成关闭。
- 生成幂等：`GenerationIdempotencyService` + `health_plan_write_request` 的 `GENERATE_<scope>` 操作（同事务写入）；`WeeklyPlanService.persistScopedGeneratedDraft` 扩展幂等参数；新增版本详情 API `GET /api/v1/health/plans/{planId}/versions/{versionNo}`。
- 偏好消费：`MealPreferenceFilter` + `MealPlanPicker.pickForDayWithPreferences` + `WeeklyPlanComposerService.composeMealsWithPreferences` → `GenerationNotes`（metadata/版本快照/PlanView/详情 API/版本详情 API/计划页六处可见）。
- 简报字段：`MealPlanBrief` 七字段稳定形状 + `MealCuisineIntentParser` 确定性菜系解析；`HealthChatResponse.supplementable` 可补充项契约。
- 前端：plan-actions chip 渲染、chat.js 结构化摘要与“开始推荐”确认短语、plans.js 生成说明两分区、`app.css` 生成说明样式与 1440 溢出修复。

## Child Tickets

- [01 统一简报续轮判定、侧归属与生命周期](issues/01-unified-brief-routing.md)
- [02 餐食简报可选偏好字段、未支持偏好与词汇](issues/02-meal-brief-optional-preferences.md)
- [03 生成侧偏好消费、可见回退与 generationNotes 契约](issues/03-generation-preference-consumption.md)
- [04 推荐前预检、可补充项契约与文案](issues/04-preflight-and-supplement-copy.md)
- [05 回归矩阵与真实浏览器验收](issues/05-regression-matrix-and-acceptance.md)

依赖：02 依赖 01；03、04 依赖 02；05 依赖全部。GitHub 票据在用户复审确认后再发布。
