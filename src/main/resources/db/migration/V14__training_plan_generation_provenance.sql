-- V14 训练计划生成来源：支持详情页和 Trace 之外的计划快照直接区分 Agent/规则降级。
ALTER TABLE `weekly_plan`
    ADD COLUMN `generation_source` varchar(16) NULL COMMENT '训练安排来源 AGENT/FALLBACK/LEGACY',
    ADD COLUMN `generation_metadata_json` json NULL COMMENT '训练简报确认版本、候选和契约版本快照';
