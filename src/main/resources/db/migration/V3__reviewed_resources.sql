-- V3 审核资源 schema（33 号票）
-- meal_item 扩展营养/来源/媒体/审核字段；新增 exercise_item、routine_fact、meal_item_embedding。
-- 所有新列可空或带默认值，保证旧 diet 链路的插入/查询不回归。
-- 媒体无再分发许可时 media_url 保持 NULL（稳定无图状态），仅保留署名 media_credit。

SET NAMES utf8mb4;

-- ----------------------------
-- meal_item 扩展
-- ----------------------------
ALTER TABLE `meal_item`
    ADD COLUMN `name_en` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '英文原名' AFTER `name`,
    ADD COLUMN `aliases` json NULL COMMENT '中文别名' AFTER `name_en`,
    ADD COLUMN `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '中文描述/简介' AFTER `aliases`,
    ADD COLUMN `ingredients_json` json NULL COMMENT '食材 JSON' AFTER `description`,
    ADD COLUMN `serving_count` int NULL DEFAULT NULL COMMENT '食谱份数' AFTER `ingredients_json`,
    ADD COLUMN `serving_size` decimal(8,2) NULL DEFAULT NULL COMMENT '每份量数值' AFTER `serving_count`,
    ADD COLUMN `serving_unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '每份量单位' AFTER `serving_size`,
    ADD COLUMN `calories_kcal` decimal(8,2) NULL DEFAULT NULL COMMENT '每份热量 kcal' AFTER `serving_unit`,
    ADD COLUMN `protein_g` decimal(8,2) NULL DEFAULT NULL COMMENT '每份蛋白质 g' AFTER `calories_kcal`,
    ADD COLUMN `fat_g` decimal(8,2) NULL DEFAULT NULL COMMENT '每份脂肪 g' AFTER `protein_g`,
    ADD COLUMN `carbohydrate_g` decimal(8,2) NULL DEFAULT NULL COMMENT '每份碳水 g' AFTER `fat_g`,
    ADD COLUMN `nutrition_basis` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '营养口径，如 foodcom_source_value' AFTER `carbohydrate_g`,
    ADD COLUMN `nutrition_estimated` tinyint(1) NOT NULL DEFAULT 0 COMMENT '营养是否估算值' AFTER `nutrition_basis`,
    ADD COLUMN `allergen_json` json NULL COMMENT '过敏原 JSON' AFTER `nutrition_estimated`,
    ADD COLUMN `allergen_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '过敏原审核状态 PENDING/REVIEWED' AFTER `allergen_json`,
    ADD COLUMN `review_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '审核状态 PENDING/APPROVED/REJECTED' AFTER `allergen_status`,
    ADD COLUMN `source_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源数据集名' AFTER `review_status`,
    ADD COLUMN `source_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源原始 ID' AFTER `source_name`,
    ADD COLUMN `source_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源版本' AFTER `source_id`,
    ADD COLUMN `media_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '媒体外链（无许可必须 NULL）' AFTER `source_version`,
    ADD COLUMN `media_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE' COMMENT '媒体状态 NONE/LICENSED' AFTER `media_url`,
    ADD COLUMN `media_credit` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '媒体署名' AFTER `media_status`,
    ADD UNIQUE INDEX `uk_meal_source`(`source_name`, `source_id`) USING BTREE;

-- ----------------------------
-- Table structure for exercise_item
-- ----------------------------
CREATE TABLE `exercise_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源数据集名',
  `source_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源原始 ID',
  `source_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源版本',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '中文展示名',
  `name_en` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '英文原名',
  `aliases` json NULL COMMENT '中文别名',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据集分类（部位）',
  `body_part` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主训练部位',
  `target_muscles` json NOT NULL COMMENT '目标肌群',
  `secondary_muscles` json NULL COMMENT '次要肌群',
  `equipment` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '器材',
  `difficulty` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '难度 入门/中级/进阶',
  `movement_pattern` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '动作模式 推/拉/蹲/髋/核心/踝/等长',
  `risk_tags` json NOT NULL COMMENT '风险标签',
  `alternative_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '替代动作组',
  `review_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'APPROVED' COMMENT '审核状态',
  `plan_ready` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否可进入自动周计划',
  `instructions_zh` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '中文步骤说明',
  `steps_json` json NULL COMMENT '中文分步 JSON',
  `media_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE' COMMENT '媒体状态 NONE/LICENSED',
  `media_credit` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '媒体署名（Gym visual）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_exercise_source`(`source_name`, `source_id`) USING BTREE,
  INDEX `idx_exercise_plan_ready`(`plan_ready`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for routine_fact
-- ----------------------------
CREATE TABLE `routine_fact`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事实主题',
  `fact_zh` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事实内容（中文）',
  `scope` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '适用范围',
  `source` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源机构',
  `source_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源版本/日期',
  `ref_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源引用 ID',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_routine_fact_ref`(`ref_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for meal_item_embedding
-- ----------------------------
CREATE TABLE `meal_item_embedding`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `meal_id` bigint NOT NULL COMMENT 'meal_item.id',
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Embedding 模型',
  `model_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型版本',
  `dimension` int NOT NULL COMMENT '向量维度',
  `vector` json NOT NULL COMMENT '归一化向量',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_meal_embedding`(`meal_id`, `model`, `model_version`) USING BTREE,
  CONSTRAINT `fk_embedding_meal` FOREIGN KEY (`meal_id`) REFERENCES `meal_item` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;
