-- 统一综合周计划生命周期：清理旧 ACTIVE/ARCHIVED 语义、撤销分域当前安排。
SET NAMES utf8mb4;

-- 兼容本地曾运行过的过渡版本；该表不是新模型的一部分。
DROP TABLE IF EXISTS `health_current_assignment`;

DELETE FROM `weekly_plan_item`
 WHERE `plan_id` IN (SELECT `id` FROM `weekly_plan` WHERE `status` IN ('ACTIVE', 'ARCHIVED'));
DELETE FROM `weekly_plan_version`
 WHERE `plan_id` IN (SELECT `id` FROM `weekly_plan` WHERE `status` IN ('ACTIVE', 'ARCHIVED'));
DELETE FROM `weekly_plan` WHERE `status` IN ('ACTIVE', 'ARCHIVED');

ALTER TABLE `weekly_plan`
    DROP INDEX `uk_plan_active_user_scope_key`,
    DROP COLUMN `active_user_scope_key`;

ALTER TABLE `weekly_plan`
    ADD COLUMN `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        NULL COMMENT '用户可见计划名称' AFTER `plan_scope`;

UPDATE `weekly_plan`
SET `name` = CONCAT('每周综合计划 ', DATE_FORMAT(`week_start`, '%Y-%m-%d'))
WHERE `name` IS NULL OR TRIM(`name`) = '';

ALTER TABLE `weekly_plan`
    MODIFY COLUMN `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        NOT NULL COMMENT '用户可见计划名称';

ALTER TABLE `weekly_plan`
    ADD CONSTRAINT `chk_weekly_plan_status`
        CHECK (`status` IN ('DRAFT', 'UNENABLED', 'ENABLED', 'HISTORY'));

ALTER TABLE `weekly_plan`
    ADD COLUMN `enabled_user_key` bigint
        GENERATED ALWAYS AS (CASE WHEN `status` = 'ENABLED' THEN `user_id` ELSE NULL END) STORED
        COMMENT '同一用户最多一份 ENABLED 计划',
    ADD UNIQUE INDEX `uk_plan_enabled_user_key`(`enabled_user_key`) USING BTREE;

CREATE TABLE `health_plan_write_request` (
    `user_id` bigint NOT NULL COMMENT '匿名身份',
    `request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `plan_id` bigint NOT NULL,
    `operation` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `response_json` json NOT NULL COMMENT '成功响应快照',
    `created_at` datetime NOT NULL,
    PRIMARY KEY (`user_id`, `request_id`),
    INDEX `idx_plan_write_request_plan`(`plan_id`),
    CONSTRAINT `fk_plan_write_request_plan` FOREIGN KEY (`plan_id`)
        REFERENCES `weekly_plan` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;
