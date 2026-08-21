# 本轮规格审计记录

日期：2026-08-21

## 已核验

- 本地 `/Users/pp/Downloads/exercises.json` 可解析，记录数为 1324。
- 每条记录有 `name`、`category`、`body_part`、`equipment`、`target`、`muscle_group`、`secondary_muscles` 和 `instructions`；`instructions.zh` 存在。
- `images` 与 `gif` 字段在 1324 条记录中均为空或缺失。
- 源 schema 不包含 `difficulty`、`movement_pattern`、`risk_tags`、`plan_ready`；这些字段只能由确定性派生/审核流程产生。
- 当前前端原型入口为 `#/prototype/my-plan`，实现使用内存 fixture，不连接新后端接口。

## 已修复

- `spec.md` 不再把中文、媒体、难度和计划资格误写成源数据已有字段，增加来源字段与派生字段边界。
- #96 票据增加媒体许可、中文步骤审核和资格版本的交付前提，避免无依据宣称 1324 条全部可入计划。
- `map.md` 增加旧收藏反馈语义与新独立集合的冲突说明，以及原型不能证明生产契约完成的说明。

## 仍需确认

1. 生产媒体权威目录、许可证和署名字段由哪个文件/服务提供？若没有，是否接受所有动作先以 `media_state=NONE` 发布？
2. `instructions.zh` 是否直接视为展示文案，还是必须有人工审核状态和责任人？
3. `VISIBLE`、`RECOMMENDABLE`、`PLAN_READY` 的硬门槛、版本格式和降级策略是什么？当前代码中的自动资格补全不能替代产品决策。
4. 独立收藏上线后，旧 `recommend_feedback` 的 FAVORITE/UNFAVORITE 是否只保留读取兼容，还是需要明确迁移/切断时间点？

在上述问题获得答案前，#96 可以先实现 dry-run、字段保真和差异报告；不能把媒体完整性、中文审核完成或全量 `PLAN_READY` 作为已满足验收。
