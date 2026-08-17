-- V10 动作授权媒体地址：无许可时保持 NULL，仅 LICENSED 状态允许对外展示。
ALTER TABLE `exercise_item`
    ADD COLUMN `media_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL
        COMMENT '授权媒体地址（无许可必须 NULL）' AFTER `steps_json`;
