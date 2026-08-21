# 本轮规格审计记录

日期：2026-08-21

## 已核验

- 本地 `/Users/pp/Downloads/exercises.json` 可解析，记录数为 1324。
- 每条记录有 `name`、`category`、`body_part`、`equipment`、`target`、`muscle_group`、`secondary_muscles` 和 `instructions`；`instructions.zh` 存在。
- 源字段实际为 `image` 与 `gif_url`，1324 条均有媒体引用；引用本身不提供独立再分发许可，导入时不能直接视为可发布媒体。
- 源 schema 不包含 `difficulty`、`movement_pattern`、`risk_tags`、`plan_ready`；这些字段只能由确定性派生/审核流程产生。
- 当前前端原型入口为 `#/prototype/my-plan`，实现使用内存 fixture，不连接新后端接口。

## 已修复

- `spec.md` 不再把中文、媒体、难度和计划资格误写成源数据已有字段，增加来源字段与派生字段边界。
- #96 票据增加媒体许可、中文步骤审核和资格版本的交付前提，避免无依据宣称 1324 条全部可入计划。
- `map.md` 增加旧收藏反馈语义与新独立集合的冲突说明，以及原型不能证明生产契约完成的说明。
- 生产实现已完成上述冲突收敛：新收藏只写 `health_resource_favorite`；`recommend_feedback` 不再作为新收藏来源；计划状态统一为 `DRAFT/UNENABLED/ENABLED/HISTORY`。

## 仍需确认

1. 生产媒体权威目录、许可证和署名字段由哪个文件/服务提供？若没有，是否接受所有动作先以 `media_state=NONE` 发布？
2. `instructions.zh` 是否直接视为展示文案，还是必须有人工审核状态和责任人？
3. `VISIBLE`、`RECOMMENDABLE`、`PLAN_READY` 的硬门槛、版本格式和降级策略是什么？当前代码中的自动资格补全不能替代产品决策。
4. 已决定并实现：独立收藏上线后，旧 `recommend_feedback` 的 FAVORITE/UNFAVORITE 只保留历史/兼容读取，不迁移为新集合，也不作为新收藏写入口。

上述内容治理问题仍需后续产品确认；本轮交付不宣称源数据自带媒体或模型生成字段。当前本地验收以字段保真、确定性中文规范化、明确媒体状态和服务端 `plan_ready` 资格门槛为准。
