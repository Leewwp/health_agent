-- 本地动作资料库：卡片缩略图与详情 GIF 分离，media_url 保持详情动画地址。
ALTER TABLE `exercise_item`
    ADD COLUMN `thumbnail_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL
        COMMENT '授权缩略图地址（本地动作资料库卡片用）' AFTER `steps_json`;
