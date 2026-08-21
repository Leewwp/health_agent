-- V20：动作源字段保真与可复现资格审计。
-- 原始字段与展示/资格派生字段分列保存；不依赖模型补全事实。
ALTER TABLE `exercise_item`
    ADD COLUMN `source_category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 category 原值' AFTER `category`,
    ADD COLUMN `source_body_part` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 body_part 原值' AFTER `body_part`,
    ADD COLUMN `source_equipment` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 equipment 原值' AFTER `equipment`,
    ADD COLUMN `source_target` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 target 原值' AFTER `target_muscles`,
    ADD COLUMN `source_muscle_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 muscle_group 原值' AFTER `source_target`,
    ADD COLUMN `source_secondary_muscles` json NULL
        COMMENT '源 secondary_muscles 原值' AFTER `secondary_muscles`,
    ADD COLUMN `source_instructions_json` json NULL
        COMMENT '源 instructions 多语言 JSON' AFTER `steps_json`,
    ADD COLUMN `source_media_image` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 image 相对路径' AFTER `source_instructions_json`,
    ADD COLUMN `source_media_gif` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源 gif 相对路径' AFTER `source_media_image`,
    ADD COLUMN `instructions_zh_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SOURCE_TRANSLATION'
        COMMENT '中文步骤来源状态 SOURCE_TRANSLATION/REVIEWED_DISPLAY' AFTER `instructions_zh`,
    ADD COLUMN `qualification_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '资格派生规则版本' AFTER `plan_ready`,
    ADD COLUMN `qualification_visible` tinyint(1) NOT NULL DEFAULT 0
        COMMENT 'VISIBLE 资格' AFTER `qualification_version`,
    ADD COLUMN `qualification_recommendable` tinyint(1) NOT NULL DEFAULT 0
        COMMENT 'RECOMMENDABLE 资格' AFTER `qualification_visible`,
    ADD COLUMN `qualification_plan_ready` tinyint(1) NOT NULL DEFAULT 0
        COMMENT 'PLAN_READY 资格' AFTER `qualification_recommendable`,
    ADD COLUMN `qualification_report_json` json NULL
        COMMENT '资格与映射诊断报告' AFTER `qualification_plan_ready`,
    ADD COLUMN `source_hash` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '源记录规范化哈希' AFTER `source_version`;

ALTER TABLE `exercise_item`
    ADD INDEX `idx_exercise_qualification` (`qualification_visible`, `qualification_recommendable`, `qualification_plan_ready`);

-- 现有审核记录仅补齐可观察的资格快照，不伪造缺失的源字段。
UPDATE `exercise_item`
SET `qualification_version` = COALESCE(`qualification_version`, 'legacy-reviewed-v1'),
    `qualification_visible` = CASE WHEN `review_status` = 'APPROVED' THEN 1 ELSE 0 END,
    `qualification_recommendable` = CASE WHEN `review_status` = 'APPROVED' THEN 1 ELSE 0 END,
    `qualification_plan_ready` = `plan_ready`,
    `instructions_zh_status` = COALESCE(NULLIF(`instructions_zh_status`, ''), 'SOURCE_TRANSLATION')
WHERE `qualification_version` IS NULL;

CREATE TABLE `health_resource_favorite` (
    `user_id` bigint NOT NULL COMMENT '匿名身份 ID',
    `resource_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MEAL/EXERCISE',
    `resource_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型化资源 ID',
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`user_id`, `resource_type`, `resource_id`),
    INDEX `idx_favorite_user_type_created` (`user_id`, `resource_type`, `created_at`, `resource_id`),
    CONSTRAINT `chk_favorite_resource_type` CHECK (`resource_type` IN ('MEAL', 'EXERCISE'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;
