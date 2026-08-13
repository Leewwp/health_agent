-- V9 统一健康评估 v2 标注字段（#73）
-- diet_request_trace 新增 evaluation_schema_version（标注契约版本）与 expected_health_json
-- （health-eval-v2 结构化 gold 标注），与旧 expected_intent/expected_slots/expected_clarify_action
-- 并列共存：不迁移、不改写、不伪造旧健康标注；TRACE_AUDIT 单次标注即可写这两列。

SET NAMES utf8mb4;

ALTER TABLE `diet_request_trace`
    ADD COLUMN `evaluation_schema_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '评估标注契约版本（health-eval-v2）' AFTER `label_note`;

ALTER TABLE `diet_request_trace`
    ADD COLUMN `expected_health_json` json NULL COMMENT 'health-eval-v2 结构化 gold 标注（expectedHealth：domain/task/slots/risk/responseType/missingSlots）' AFTER `evaluation_schema_version`;
