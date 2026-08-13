package com.diet.health.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V9 统一健康评估 v2 标注字段迁移校验（#73）：
 * diet_request_trace 新增 evaluation_schema_version varchar(32) 与 expected_health_json JSON 两列，
 * 保留旧 expected_intent/expected_slots/expected_clarify_action 原义不迁移、不改写。
 */
class HealthEvalAnnotationMigrationTest {

    private static final String MIGRATION_PATH = "/db/migration/V9__health_eval_annotation_fields.sql";

    private String migrationSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(in, "迁移文件不存在: " + MIGRATION_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void V9新增评估schema版本与expectedHealthJSON列() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("ALTER TABLE `diet_request_trace`"), "必须作用于 diet_request_trace");
        assertTrue(sql.contains("`evaluation_schema_version` varchar(32)"), "schema 版本列必须为 varchar(32)");
        assertTrue(sql.contains("NULL"), "schema 版本列必须可空（旧 trace 与旧饮食链路保持 NULL）");
        assertTrue(sql.contains("`expected_health_json` json"), "gold 标注列必须为 JSON 类型");
    }

    @Test
    void V9不迁移不改写旧标注列() throws IOException {
        String sql = migrationSql().replaceAll("(?m)^\\s*--.*$", "");
        assertTrue(!sql.contains("expected_intent"), "不得改写旧 expected_intent 列");
        assertTrue(!sql.contains("expected_slots"), "不得改写旧 expected_slots 列");
        assertTrue(!sql.contains("expected_clarify_action"), "不得改写旧 expected_clarify_action 列");
        assertTrue(!sql.contains("UPDATE"), "不得回填或改写任何既有行");
        assertTrue(!sql.contains("DROP"), "不得删除任何既有列");
        assertTrue(!sql.contains("FOREIGN KEY"), "不得建立外键");
    }

    @Test
    void V9携带注释说明契约版本与不迁移语义() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("COMMENT"), "新列必须携带中文注释");
        assertTrue(sql.contains("health-eval-v2"), "注释必须说明评估契约版本");
    }
}
