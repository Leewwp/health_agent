package com.diet.health.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一综合周计划迁移只保留用户级 ENABLED 真相和写请求幂等表。 */
class UnifiedPlanLifecycleMigrationTest {

    @Test
    void 迁移清理旧计划并建立用户级启用唯一约束() throws Exception {
        String sql = read("db/migration/V18__unified_plan_lifecycle_and_write_idempotency.sql").toLowerCase();
        assertTrue(sql.contains("drop table if exists `health_current_assignment`"));
        assertTrue(sql.contains("delete from `weekly_plan_item`"));
        assertTrue(sql.contains("delete from `weekly_plan_version`"));
        assertTrue(sql.contains("delete from `weekly_plan`"));
        assertTrue(sql.indexOf("delete from `weekly_plan_item`") < sql.indexOf("delete from `weekly_plan_version`"));
        assertTrue(sql.indexOf("delete from `weekly_plan_version`") < sql.indexOf("delete from `weekly_plan`"));
        assertTrue(sql.contains("drop index `uk_plan_active_user_scope_key`"));
        assertTrue(sql.contains("drop column `active_user_scope_key`"));
        assertTrue(sql.contains("'draft', 'unenabled', 'enabled', 'history'"));
        assertTrue(sql.contains("uk_plan_enabled_user_key"));
        assertTrue(sql.contains("create table `health_plan_write_request`"));
        assertTrue(!sql.contains("create table `health_current_assignment`"));
    }

    private String read(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
