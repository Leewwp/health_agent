-- V4 健康档案与周计划 schema（34 号票）
-- health_profile：当前档案（每匿名用户一行）；health_profile_version：档案版本快照（计划生成依据）。
-- weekly_plan：计划聚合根（DRAFT/ACTIVE/ARCHIVED）；weekly_plan_version：激活/生成时的不可变版本；
-- weekly_plan_item：计划项目（resourceType+resourceId 类型化引用），按 (plan_id, version_no) 归属版本。
-- 所有 JSON 列由应用层序列化，DDL 不绑定具体列类型细节。

SET NAMES utf8mb4;

-- ----------------------------
-- Table structure for health_profile
-- ----------------------------
CREATE TABLE `health_profile`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '匿名身份',
  `age` int NOT NULL COMMENT '年龄（必填，18-100）',
  `sex` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '生理性别 MALE/FEMALE，选填',
  `height_cm` decimal(5,1) NOT NULL COMMENT '身高 cm',
  `weight_kg` decimal(5,1) NOT NULL COMMENT '体重 kg',
  `activity_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '活动水平 SEDENTARY/LIGHT/MODERATE',
  `goal` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主要目标 MAINTAIN/LOSE/GAIN',
  `timezone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区（缺省 Asia/Shanghai）',
  `calorie_low` int NOT NULL COMMENT '日能量区间下限 kcal（确定性公式）',
  `calorie_high` int NOT NULL COMMENT '日能量区间上限 kcal',
  `estimated` tinyint(1) NOT NULL DEFAULT 1 COMMENT '估算标记，恒为 1',
  `version_no` bigint NOT NULL DEFAULT 1 COMMENT '档案当前版本号',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_health_profile_user`(`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for health_profile_version
-- ----------------------------
CREATE TABLE `health_profile_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '匿名身份',
  `profile_id` bigint NOT NULL COMMENT 'health_profile.id',
  `version_no` bigint NOT NULL COMMENT '档案版本号',
  `snapshot_json` json NOT NULL COMMENT '档案快照（生成计划依据，不可变）',
  `calorie_low` int NOT NULL,
  `calorie_high` int NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_profile_version_no`(`profile_id`, `version_no`) USING BTREE,
  INDEX `idx_profile_version_user`(`user_id`) USING BTREE,
  CONSTRAINT `fk_profile_version_profile` FOREIGN KEY (`profile_id`) REFERENCES `health_profile` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for weekly_plan
-- ----------------------------
CREATE TABLE `weekly_plan`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '匿名身份',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/ARCHIVED',
  `week_start` date NOT NULL COMMENT '本地周一日期',
  `timezone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '计划时区',
  `profile_version_no` bigint NOT NULL COMMENT '生成依据的档案版本',
  `calorie_low` int NOT NULL COMMENT '生成时能量区间下限 kcal',
  `calorie_high` int NOT NULL COMMENT '生成时能量区间上限 kcal',
  `rules_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '校验规则版本',
  `validation_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '最近校验 OK/WARNING/HARD_ERROR',
  `validation_json` json NOT NULL COMMENT '最近校验结果（规则命中列表）',
  `note` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '用户备注',
  `source_session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源会话',
  `current_version` bigint NOT NULL DEFAULT 1 COMMENT '当前版本号（项目归属版本）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_plan_user_status`(`user_id`, `status`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for weekly_plan_version
-- ----------------------------
CREATE TABLE `weekly_plan_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL COMMENT 'weekly_plan.id',
  `version_no` bigint NOT NULL COMMENT '版本号',
  `profile_version_no` bigint NOT NULL COMMENT '生成依据档案版本',
  `profile_snapshot_json` json NOT NULL COMMENT '档案快照（含能量区间）',
  `validation_json` json NOT NULL COMMENT '该版本校验结果',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_plan_version_no`(`plan_id`, `version_no`) USING BTREE,
  CONSTRAINT `fk_plan_version_plan` FOREIGN KEY (`plan_id`) REFERENCES `weekly_plan` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for weekly_plan_item
-- ----------------------------
CREATE TABLE `weekly_plan_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL COMMENT 'weekly_plan.id',
  `version_no` bigint NOT NULL COMMENT '归属版本号',
  `resource_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MEAL/EXERCISE/ROUTINE',
  `resource_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型化资源标识',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '展示名快照',
  `local_date` date NOT NULL COMMENT '本地日期',
  `start_time` time NOT NULL COMMENT '本地开始时间',
  `end_time` time NULL DEFAULT NULL COMMENT '本地结束时间',
  `note` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
  `plan_params_json` json NULL COMMENT '计划参数（热量/部位/剂量等）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_plan_item_version`(`plan_id`, `version_no`) USING BTREE,
  CONSTRAINT `fk_plan_item_plan` FOREIGN KEY (`plan_id`) REFERENCES `weekly_plan` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;
