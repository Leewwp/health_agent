package com.diet.health.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** V15 在已有 V1-V14 数据库上按外键顺序清理旧测试计划并增加范围字段。 */
class PlanScopeMigrationTest {

    @Test
    void V15按外键顺序清理并声明范围约束() throws Exception {
        String sql;
        try (var input = new ClassPathResource("db/migration/V15__plan_scope_and_test_data_cleanup.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(sql.contains("add column `plan_scope`"));
        assertTrue(sql.contains("delete from `weekly_plan_item`"));
        assertTrue(sql.contains("delete from `weekly_plan_version`"));
        assertTrue(sql.contains("delete from `weekly_plan`"));
        assertTrue(sql.indexOf("delete from `weekly_plan_item`") < sql.indexOf("delete from `weekly_plan_version`"));
        assertTrue(sql.indexOf("delete from `weekly_plan_version`") < sql.indexOf("delete from `weekly_plan`"));
        assertTrue(sql.contains("'exercise', 'meal', 'composite'"));
    }
}
