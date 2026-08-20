# 替代推荐与反馈语义

Type: task
Status: ready-for-agent
Blocked by: none
Priority: P0
Estimated complexity: 高
External dependency: 无
Database migration: 优先复用会话 JSON；若迁移必须可回滚

## Goal

让用户能直接获取不重复的替代候选，并理解反馈行为。

## Scope

- 推荐响应底部增加“换一批”。
- 会话按资源类型累计已展示 ID，并设置有界容量。
- 保留已确认槽位和临时约束。
- 候选耗尽时返回显式放宽/重复选项。
- 用户侧只展示收藏和减少推荐。
- 收藏不参与排序提升；资源级减少推荐保持默认行为；类别级偏好只由明确类别表达产生。

## Action Contract

“换一批”复用健康聊天幂等通道，action 至少携带：`resourceType`、`baseTraceId`、`addedExclusions`、`allowRepeat`。服务端按 `(sessionId, resourceType)` 保存最多 50 个已展示/排除 ID。

- 默认 `allowRepeat=false`；只有用户明确选择“重复上一批”才允许旧结果。
- 保留已确认槽位和请求级临时约束；新排除不写入长期偏好。
- 新会话、明确重置或新任务上下文清除对应品类集合；跨品类聊天不清除另一品类集合。
- 候选耗尽返回稳定的 `CANDIDATES_EXHAUSTED`，并提供“放宽条件”和“重复已展示结果”两个显式 action，不得静默放宽。

## Feedback Contract

用户只展示“收藏”和“减少推荐”。旧 `LIKE/DISLIKE/ADOPT` 数据保留兼容读取；新写入统一映射为 `FAVORITE/UNFAVORITE` 或 `REDUCE_RECOMMENDATION/UNDO_REDUCE_RECOMMENDATION`。收藏与减少推荐是独立状态，可以同时存在；失败必须回滚乐观 UI。

## Acceptance

- 连续点击“换一批”不重复当前会话已展示资源。
- 新增排除条件能影响后续候选。
- 候选耗尽不静默放宽。
- 收藏状态独立于推荐倾向。
- 反馈控件有可见状态、错误回滚和必要撤销路径。
- 连续换批、幂等重试、候选耗尽、减少推荐撤销和收藏并存均有接口与浏览器证据。
