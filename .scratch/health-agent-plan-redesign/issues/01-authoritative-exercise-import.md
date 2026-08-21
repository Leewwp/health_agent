# 动作权威导入与字段映射

Status: resolved
Type: task
Priority: P0
Blocked by: none

## Goal

以 `exercises.json` 为权威源恢复 1324 条动作的完整字段，建立可重复、可审计的导入流程。

## Scope

- dry-run/apply 两阶段导入；
- `(source_name, source_id)` Upsert，保留数据库内部 ID；
- 独立保存源字段 category、body_part、target、muscle_group、secondary_muscles、equipment、原始 instructions 和媒体字段；difficulty、movement_pattern、risk_tags、中文标签与 plan_ready 必须作为带版本的派生/审核字段保存；
- 完整中文映射表和未映射报告；
- VISIBLE/RECOMMENDABLE/PLAN_READY 资格报告；
- Reader、详情 API 和动作页面展示字段。

## Acceptance

- 1324 条基线可重复导入；
- 现有 source key 的内部 ID 保持不变；
- JSON 中的核心字段不会被空值覆盖；
- 详情能展示映射后的训练部位、目标肌群、器材和 `instructions.zh` 转换的中文步骤，并明确标注其来源/审核状态；
- 未映射值可被发现，不由模型猜测；
- 导入失败不会留下半成品。

## Dependencies and fallback

- 依赖本地 JSON 和 MySQL；当前 JSON 的 `image`/`gif_url` 只有源引用，不能把它们当作已获许可的可发布媒体；
- dry-run 可在无 MySQL 时执行；
- 导入失败时继续使用旧审核子集，不阻断聊天主流程。没有独立许可媒体目录、中文步骤审核规则和资格门槛版本时，本票不得自动宣称 1324 条全部 `PLAN_READY`。
