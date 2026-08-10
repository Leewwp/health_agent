-- V6 计划版本生成依据与 ACTIVE 数据库不变量（42 号票）
-- weekly_plan_version 补齐规则版本、来源会话、作息事实来源与资源快照；
-- weekly_plan 通过生成列实现"同一用户至多一条 ACTIVE"的数据库级唯一约束。
-- 时区约定：时区不是版本字段，历史版本从计划根 weekly_plan.timezone 继承
-- （计划创建时固定，无时区编辑入口，历史读取不依赖未来可变字段）。

SET NAMES utf8mb4;

-- ----------------------------
-- weekly_plan_version 补版本生成依据（不可变，历史版本只读）
-- ----------------------------
ALTER TABLE `weekly_plan_version`
  ADD COLUMN `rules_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '校验规则版本（版本生成依据，不可变）',
  ADD COLUMN `source_session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源会话（版本生成依据，不可变）',
  ADD COLUMN `fact_sources_json` json NULL COMMENT '作息事实来源快照（版本生成依据，不可变）',
  ADD COLUMN `resource_snapshot_json` json NULL COMMENT '资源快照：Provider 模式/版本 + 每项类型化资源的来源与计划参数（版本生成依据，不可变）';

-- 旧版本回填：规则版本与来源会话从计划根一次性继承（此后版本独立保存，不再跟随计划根变化）。
-- 旧版本的 fact_sources_json / resource_snapshot_json 保持 NULL，可由 weekly_plan_item 的
-- 不可变 name / plan_params_json 与档案快照重建，不做 SQL 级推断。
UPDATE `weekly_plan_version` v
JOIN `weekly_plan` p ON p.id = v.plan_id
SET v.rules_version = p.rules_version,
    v.source_session_id = p.source_session_id;

-- ----------------------------
-- ACTIVE 唯一约束：生成列 + 唯一索引（非 ACTIVE 映射为 NULL，允许多 DRAFT/ARCHIVED）
-- ----------------------------
-- 预检查与清理：同一用户存在多条 ACTIVE（历史脏数据，不应发生）时保留最新一条，其余归档。
UPDATE `weekly_plan` wp
JOIN (
    SELECT user_id, MAX(id) AS keep_id
    FROM `weekly_plan`
    WHERE status = 'ACTIVE'
    GROUP BY user_id
    HAVING COUNT(*) > 1
) dup ON dup.user_id = wp.user_id
SET wp.status = 'ARCHIVED', wp.updated_at = CURRENT_TIMESTAMP
WHERE wp.status = 'ACTIVE' AND wp.id <> dup.keep_id;

ALTER TABLE `weekly_plan`
  ADD COLUMN `active_user_key` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `user_id` ELSE NULL END) STORED COMMENT 'ACTIVE 唯一约束生成列',
  ADD UNIQUE INDEX `uk_plan_active_user_key`(`active_user_key`) USING BTREE;
