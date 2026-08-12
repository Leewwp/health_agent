package com.diet.health.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V7 健康档案结构化风险字段迁移校验（62 号票）：
 * health_profile 补结构化风险条件 JSON 列与选填风险说明列；
 * 两列均可空（旧行读取等同"未填写 = 无风险"，无需回填）。
 */
class ProfileRiskMigrationTest {

    private static final String MIGRATION_PATH = "/db/migration/V7__profile_structured_risk_fields.sql";

    private String migrationSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(in, "迁移文件不存在: " + MIGRATION_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void V7为health_profile新增结构化风险列() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("ALTER TABLE `health_profile`"));
        assertTrue(sql.contains("ADD COLUMN `risk_conditions_json` json NULL"),
                "风险条件为可空 JSON 列（NULL 视为无风险）");
        assertTrue(sql.contains("ADD COLUMN `risk_note` varchar(200)"),
                "风险说明为受限长度可空文本列");
    }

    @Test
    void V7旧行按无风险兼容无需回填() throws IOException {
        String sql = migrationSql();
        assertTrue(!sql.contains("UPDATE `health_profile`"),
                "旧行两列 NULL 即无风险语义，不得引入有损回填");
        assertTrue(!sql.contains("DROP"), "V7 不得包含破坏性语句");
    }
}
