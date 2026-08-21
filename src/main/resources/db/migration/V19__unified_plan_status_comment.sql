-- 统一综合周计划：同步修正运行库状态列注释，避免继续暴露旧 ACTIVE/ARCHIVED 语义。
SET NAMES utf8mb4;

ALTER TABLE `weekly_plan`
    MODIFY COLUMN `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/UNENABLED/ENABLED/HISTORY';
