# 统一简报续轮判定、侧归属与生命周期

Type: task
Status: ready-for-agent
Blocked by: none
Priority: P0

## Goal

把“计划简报会话活跃”变成一等路由证据，并收敛为返回结构化结果、带固定裁决优先级的单一共享判定（briefActive + activeSide + escape + reason）；定义综合简报侧归属、简报生命周期和生成幂等恢复；删除被替代的路由类关键词清单副本。

## Acceptance

- 新增共享结构化判定，输出 `briefActive`、`activeSide`（MEAL/EXERCISE/BOTH/NONE）、`escape`（RECOMMEND/ALTERNATIVE/DOMAIN_OR_ROUTINE/NONE）、`reason`；模型前续轮、模型后修正、编排器简报门槛三处复用同一实现，不建第二套路由状态机。
- 固定裁决优先级：风险阻断 > 明确领域切换/作息提问 > 明确替代/换一批 > 明确普通推荐逃生口 > 简报生命周期（GENERATED/PAUSED 不捕获）> activeSide 归属 > 字段解析。三个调用点不得覆写；测试断言 reason、escape、activeSide、briefActive。
- 简报活跃时，除上述逃生口外的自由文本一律进入对应简报处理器。可选偏好（“中餐”“烹饪时间短”等）必须在 `isUnrelated`/旧 `looksLike*` 启发式之前完成识别。
- 生命周期 JSON 固定为 `_meta.briefLifecycle = {"MEAL":"OPEN|PAUSED|GENERATED", "EXERCISE":"OPEN|PAUSED|GENERATED"}`。旧会话按对应简报有内容且 task 为 PLAN 推导 OPEN，否则无状态；MEAL-only/EXERCISE-only 只更新对应侧，COMPOSITE 才同时更新两侧。
- 生命周期转移：进入简报处理器/澄清 -> OPEN；显式切换其他领域 -> PAUSED（暂停不等于关闭，显式计划词恢复 OPEN）；对应范围生成成功 -> GENERATED；新会话/重置 -> 清空。已 GENERATED 不得被旧请求回写为 OPEN/PAUSED。
- 所有简报和生命周期更新通过 `HealthSessionService` 事务性行锁读取最新 JSON、合并目标字段后写回；不得用生成入口的旧快照覆盖并发补充。单实例锁可复用，跨实例以数据库 `FOR UPDATE` 为准。
- 三个生成入口使用既有 `health_plan_write_request` 的 `operation=GENERATE_<scope>` 记录生成幂等；`requestId` 对同一用户全局唯一，记录与 weekly plan/版本快照同一事务提交。命中记录时恢复原响应，只执行幂等生命周期补偿，不重新生成；同一 requestId 用于不同 session/scope 时返回幂等冲突；Trace 仅作诊断。
- 计划持久化成功但生命周期回写失败时返回 5xx；重试命中生成写记录并重新回写，最终只保留一份草稿。任一计划写入、幂等记录或会话回写失败，不得留下无法恢复的已生成状态。
- 综合侧归属：NONE -> 餐食；无前缀且餐食未完整 -> MEAL；餐食完整训练未完整 -> EXERCISE；两侧完整（BOTH）无前缀不猜测，要求“餐食：/训练：”；显式侧前缀优先且允许跨侧修改。activeSide 只决定写入目标，不参与领域/任务路由。
- 社交短句小清单在未关闭会话中只返回确认并保留简报；已生成态的显式计划词重新打开补充。普通推荐与“换一批/替代推荐”分别进入 RECOMMEND 和 ALTERNATIVE/ADJUST。
- 新会话模糊短句仍为 OTHER + CHAT；五个快捷入口和跨域隔离保持不变；无活跃简报且无计划词的兜底文案改为可行动指引。
- 关键词清单按路由类与字段内容类分离：路由类只保留共享判定的一份实现，字段解析类不得参与领域/任务路由。
- 最高接缝测试覆盖原始失败句、综合 NONE/焦点侧/BOTH/显式前缀、生成后“谢谢”、重新打开、MEAL-only/COMPOSITE 关闭范围、幂等重试和聊天/生成并发合并。
- v3.2 并发收紧：编排器 `persistAndRespond` 的整体保存路径必须改为合并写，或在写入前保留数据库最新 `_meta.briefLifecycle`、`_meta.recommendationConfirmationKey` 及并发简报字段；必须有测试证明旧聊天快照不能把 `GENERATED` 打回 `OPEN/PAUSED`。

## Comments
