package com.diet.health.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V6 计划版本生成依据与 ACTIVE 不变量迁移校验（42 号票）：
 * 版本表补生成依据列 + 旧版本回填 + ACTIVE 生成列唯一索引与重复 ACTIVE 预清理。
 */
class PlanVersionProvenanceMigrationTest {

    private static final String MIGRATION_PATH = "/db/migration/V6__plan_version_provenance_and_active_constraint.sql";

    private String migrationSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(in, "迁移文件不存在: " + MIGRATION_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void V6版本表新增生成依据列() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("ALTER TABLE `weekly_plan_version`"));
        assertTrue(sql.contains("ADD COLUMN `rules_version` varchar(32)"));
        assertTrue(sql.contains("ADD COLUMN `source_session_id` varchar(64)"));
        assertTrue(sql.contains("ADD COLUMN `fact_sources_json` json"));
        assertTrue(sql.contains("ADD COLUMN `resource_snapshot_json` json"));
    }

    @Test
    void V6旧版本从计划根回填规则版本与会话() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("UPDATE `weekly_plan_version`"));
        assertTrue(sql.contains("JOIN `weekly_plan`"));
        assertTrue(sql.contains("v.rules_version = p.rules_version"));
        assertTrue(sql.contains("v.source_session_id = p.source_session_id"));
    }

    @Test
    void V6重复ACTIVE预清理后建立唯一约束() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("HAVING COUNT(*) > 1"), "应检测同一用户多条 ACTIVE");
        assertTrue(sql.contains("wp.status = 'ARCHIVED'"), "多余 ACTIVE 归档");
        assertTrue(sql.contains("GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `user_id` ELSE NULL END)"),
                "非 ACTIVE 映射为 NULL 的生成列");
        assertTrue(sql.contains("ADD UNIQUE INDEX `uk_plan_active_user_key`"), "ACTIVE 用户唯一索引");
    }
}
