-- V16 计划范围来源标识扩容：MEAL/COMPOSITE 的确定性生成来源需要超过旧版 16 字符边界。
SET NAMES utf8mb4;

ALTER TABLE `weekly_plan`
    MODIFY COLUMN `generation_source` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        NULL COMMENT '计划生成来源（AGENT/FALLBACK/范围确定性合并）';
