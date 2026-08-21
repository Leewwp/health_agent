# 动作数据、推荐前确认、个人收藏与“我的计划”重构地图

Status: resolved
Spec: ./spec.md
GitHub parent: https://github.com/Leewwp/health_agent/issues/95

## Destination

恢复 1324 条动作的权威字段和可验证详情，建立独立个人收藏集合，给餐食/动作推荐增加确认门，并完成可用的综合周计划 TimeGrid 编辑页面。

## Implementation order

1. [动作权威导入与字段映射](./issues/01-authoritative-exercise-import.md) — GitHub #96
2. [个人收藏集合](./issues/02-personal-resource-collection.md) — GitHub #97
3. [推荐前确认](./issues/03-recommendation-preflight.md) — GitHub #98
4. [计划后端编辑契约](./issues/04-plan-edit-contract.md) — GitHub #99
5. [“我的计划”原型与页面](./issues/05-my-plan-timegrid-prototype.md) — GitHub #100

## Decisions so far

- 以完整 `exercises.json` 为动作权威源；不做模型补全。
- 收藏集合独立于旧推荐反馈。
- “为我推荐”复用现有推荐请求；“换一批”继续使用排除集合。
- 计划页面采用桌面周 TimeGrid + 移动端单日时间轴。
- 资源选择使用搜索/筛选/收藏弹窗，项目编辑使用详情抽屉。

## Readiness notes

- `/Users/pp/Downloads/exercises.json` 的 1324 条基线已核验，源字段实际为 `image`/`gif_url` 且均有引用，但这些引用不等于可再分发许可；`difficulty`、`movement_pattern`、`risk_tags` 不属于源 schema。#96 必须把媒体许可与派生资格作为独立交付项，不能以“权威源已有可发布媒体/资格”作为前提。
- #97 与旧反馈规格的冲突已解决：新收藏写入 `health_resource_favorite`，`recommend_feedback` 仅保留喜欢/不喜欢/采纳及历史反馈审计语义。
- #100 已接入真实计划 API、审核 Reader 和独立收藏集合；生产页面已通过保存失败保留 dirty 状态、保存成功版本递增和移动端浏览器验收。
- 动作源文件只有媒体引用，媒体状态仍按无独立许可的 `SOURCE_REFERENCE_ONLY`/`NONE` 处理；中文步骤、派生标签和三层资格由确定性导入/审核版本标记，不能被模型在线补写。媒体许可目录和人工审核责任仍是后续内容治理输入，不影响本地目录浏览与当前资格门槛。
- 原型最终冻结为 `#/prototype/my-plan`：可折叠计划库侧栏 + 横向七日卡片工作区；原型决策见 [06-my-plan-final-prototype-decision.md](./issues/06-my-plan-final-prototype-decision.md)。
