-- V5 类型化反馈（41 号票）
-- recommend_feedback 增加类型化资源列，保留 item_id 兼容窗口：
-- resource_type+resource_id 标识偏好资源（MEAL/EXERCISE，作息事实不参与偏好）；
-- plan_id/plan_item_id 标识周计划归属；source 区分来源（LEGACY_DIET=旧饮食接口，HEALTH_CHAT=健康反馈接口）。
-- 旧数据回填：item_id 非空视为旧饮食餐食事件（MEAL + 主键字符串 + LEGACY_DIET）；
-- item_id 为空的旧事件是会话级反馈，不伪造资源身份，保持 resource_id NULL。

SET NAMES utf8mb4;

ALTER TABLE `recommend_feedback`
    ADD COLUMN `resource_type` varchar(16) NULL COMMENT '资源类型 MEAL/EXERCISE' AFTER `item_id`,
    ADD COLUMN `resource_id` varchar(64) NULL COMMENT '类型化资源标识' AFTER `resource_type`,
    ADD COLUMN `plan_id` bigint NULL COMMENT '周计划 ID' AFTER `resource_id`,
    ADD COLUMN `plan_item_id` bigint NULL COMMENT '周计划项目 ID' AFTER `plan_id`,
    ADD COLUMN `source` varchar(32) NULL COMMENT '反馈来源 LEGACY_DIET/HEALTH_CHAT' AFTER `reason`;

UPDATE recommend_feedback
SET resource_type = 'MEAL',
    resource_id = CAST(item_id AS CHAR),
    source = 'LEGACY_DIET'
WHERE item_id IS NOT NULL;
