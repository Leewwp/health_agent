-- V15 计划范围契约：清理旧测试计划后，所有新计划必须显式属于 EXERCISE/MEAL/COMPOSITE。
SET NAMES utf8mb4;

ALTER TABLE `weekly_plan`
    ADD COLUMN `plan_scope` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        NOT NULL DEFAULT 'EXERCISE' COMMENT '计划范围 EXERCISE/MEAL/COMPOSITE' AFTER `user_id`;

ALTER TABLE `weekly_plan_version`
    ADD COLUMN `plan_scope` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        NOT NULL DEFAULT 'EXERCISE' COMMENT '版本快照范围 EXERCISE/MEAL/COMPOSITE' AFTER `plan_id`;

-- 旧计划全部属于历史测试数据，按外键依赖顺序清理，不保留 LEGACY_MIXED 兼容分支。
DELETE FROM `weekly_plan_item`;
DELETE FROM `weekly_plan_version`;
DELETE FROM `weekly_plan`;

ALTER TABLE `weekly_plan`
    ADD CONSTRAINT `chk_weekly_plan_scope`
        CHECK (`plan_scope` IN ('EXERCISE', 'MEAL', 'COMPOSITE'));
ALTER TABLE `weekly_plan_version`
    ADD CONSTRAINT `chk_weekly_plan_version_scope`
        CHECK (`plan_scope` IN ('EXERCISE', 'MEAL', 'COMPOSITE'));
