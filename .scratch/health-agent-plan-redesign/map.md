# 动作数据、推荐前确认、个人收藏与“我的计划”重构地图

Status: ready-for-agent
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

- `/Users/pp/Downloads/exercises.json` 的 1324 条基线已核验，但 `images`/`gif` 全量为空，且 `difficulty`、`movement_pattern`、`risk_tags` 不属于源 schema；#96 必须把媒体与派生资格作为独立交付项，不能以“权威源已有完整媒体/资格”作为前提。
- #97 与旧反馈规格存在真实冲突：本目录要求独立收藏持久化，旧文档要求沿用 `recommend_feedback`。实现前应先按本目录更新旧文档，确保只有一个新收藏写入口，旧反馈仅作历史/兼容读取。
- #100 依赖 #99，当前 `frontend/assets/js/pages/my-plan-prototype.js` 明确是内存 fixture；它不能作为后端编辑契约已完成的证据。生产页面接入前必须补齐真实 API、错误回滚和浏览器证据。
- 三个需产品/维护者确认的输入仍未冻结：媒体许可目录、中文步骤审核责任/状态、三层资格（VISIBLE/RECOMMENDABLE/PLAN_READY）的硬门槛和版本格式。没有这些输入时，#96 应保持 `ready-for-agent` 但将“全量计划资格/媒体完整”验收标记为未满足，而不是猜测默认值。
- 原型最终冻结为 `#/prototype/my-plan`：可折叠计划库侧栏 + 横向七日卡片工作区；原型决策见 [06-my-plan-final-prototype-decision.md](./issues/06-my-plan-final-prototype-decision.md)。
