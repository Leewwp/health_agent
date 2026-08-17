-- 动作来源固定 revision 使用完整 Git SHA-1（40 位），保留完整溯源信息。
ALTER TABLE `exercise_item`
    MODIFY COLUMN `source_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
        COMMENT '来源数据集版本或固定 revision';
