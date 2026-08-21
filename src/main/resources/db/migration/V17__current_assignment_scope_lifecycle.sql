-- 当前安排与计划生命周期分离：餐食/训练各自最多一条引用，取消只删除引用。
SET NAMES utf8mb4;

ALTER TABLE `weekly_plan`
    DROP INDEX `uk_plan_active_user_key`,
    DROP COLUMN `active_user_key`,
    ADD COLUMN `active_user_scope_key` varchar(80)
        GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE'
            THEN CONCAT(`user_id`, ':', `plan_scope`) ELSE NULL END) STORED
        COMMENT '同一用户同一计划范围最多一条 ACTIVE';

ALTER TABLE `weekly_plan`
    ADD UNIQUE INDEX `uk_plan_active_user_scope_key`(`active_user_scope_key`) USING BTREE;

CREATE TABLE `health_current_assignment` (
    `user_id` bigint NOT NULL COMMENT '匿名身份',
    `plan_scope` varchar(16) NOT NULL COMMENT 'MEAL/EXERCISE/COMPOSITE',
    `plan_id` bigint NOT NULL COMMENT 'weekly_plan.id',
    `version_no` bigint NOT NULL COMMENT '当前引用版本',
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`user_id`, `plan_scope`),
    INDEX `idx_current_assignment_plan`(`plan_id`),
    CONSTRAINT `fk_current_assignment_plan` FOREIGN KEY (`plan_id`)
        REFERENCES `weekly_plan` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `chk_current_assignment_scope`
        CHECK (`plan_scope` IN ('MEAL', 'EXERCISE', 'COMPOSITE'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;
