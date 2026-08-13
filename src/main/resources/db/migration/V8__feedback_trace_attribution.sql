-- V8 健康反馈 traceId 精确归因（#74）
-- recommend_feedback 增加 trace_id 列（与 diet_request_trace.trace_id 同宽 128），
-- 用于健康聊天反馈按 trace 一对一精确归因，评估器不再依赖 session 近似；
-- 不建外键（保留 diet_request_trace 的写入自由度），不改变既有 item_id/类型化资源兼容列；
-- 旧反馈与旧饮食链路（LEGACY_DIET）保持 trace_id NULL。

SET NAMES utf8mb4;

ALTER TABLE `recommend_feedback`
    ADD COLUMN `trace_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '健康聊天链路 traceId（关联 diet_request_trace.trace_id，不建外键）' AFTER `session_id`;

ALTER TABLE `recommend_feedback`
    ADD INDEX `idx_feedback_trace`(`user_id` ASC, `trace_id` ASC) USING BTREE COMMENT '按用户+traceId 精确归因查询';
