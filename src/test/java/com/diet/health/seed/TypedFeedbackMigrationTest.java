package com.diet.health.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V5 类型化反馈迁移校验（41 号票）：
 * 新增列定义与旧数据回填语句（item_id 非空 → MEAL + 主键字符串 + LEGACY_DIET）。
 */
class TypedFeedbackMigrationTest {

    private static final String MIGRATION_PATH = "/db/migration/V5__typed_feedback.sql";

    private String migrationSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(in, "迁移文件不存在: " + MIGRATION_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void V5新增类型化反馈列() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("ALTER TABLE `recommend_feedback`"));
        assertTrue(sql.contains("ADD COLUMN `resource_type` varchar(16)"));
        assertTrue(sql.contains("ADD COLUMN `resource_id` varchar(64)"));
        assertTrue(sql.contains("ADD COLUMN `plan_id` bigint"));
        assertTrue(sql.contains("ADD COLUMN `plan_item_id` bigint"));
        assertTrue(sql.contains("ADD COLUMN `source` varchar(32)"));
    }

    @Test
    void V5旧数据回填为MEAL资源与LEGACY来源() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("UPDATE recommend_feedback"));
        assertTrue(sql.contains("resource_type = 'MEAL'"));
        assertTrue(sql.contains("CAST(item_id AS CHAR)"));
        assertTrue(sql.contains("source = 'LEGACY_DIET'"));
        assertTrue(sql.contains("WHERE item_id IS NOT NULL"));
    }
}
