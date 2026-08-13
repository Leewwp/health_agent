package com.diet.health.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V8 健康反馈 traceId 精确归因迁移校验（#74）：
 * 新增 trace_id 列（与 diet_request_trace.trace_id 同宽 128，可空）、
 * (user_id, trace_id) 普通索引，且不建外键、不触碰既有兼容列。
 */
class FeedbackTraceMigrationTest {

    private static final String MIGRATION_PATH = "/db/migration/V8__feedback_trace_attribution.sql";

    private String migrationSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(in, "迁移文件不存在: " + MIGRATION_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void V8新增traceId列与userTrace索引() throws IOException {
        String sql = migrationSql();
        assertTrue(sql.contains("ALTER TABLE `recommend_feedback`"));
        assertTrue(sql.contains("ADD COLUMN `trace_id` varchar(128)"), "traceId 必须与 trace 表同宽 128");
        assertTrue(sql.contains("NULL"), "traceId 必须可空，旧数据与旧链路保持 NULL");
        assertTrue(sql.contains("ADD INDEX `idx_feedback_trace`"), "必须提供 (user_id, trace_id) 普通索引");
        assertTrue(sql.contains("`user_id`"), "索引必须从 user_id 开始，支撑按用户+traceId 精确归因");
        assertTrue(sql.contains("`trace_id`"), "索引必须包含 trace_id");
    }

    @Test
    void V8不建立外键且不改变既有兼容列() throws IOException {
        String sql = migrationSql();
        assertTrue(!sql.contains("FOREIGN KEY"), "不得建立指向 diet_request_trace 的外键");
        assertTrue(!sql.contains("ADD COLUMN `item_id`"), "不得改动既有 item_id 兼容窗口");
        assertTrue(!sql.contains("ADD COLUMN `resource_type`") && !sql.contains("ADD COLUMN `resource_id`"),
                "不得改动既有类型化资源列");
    }
}
