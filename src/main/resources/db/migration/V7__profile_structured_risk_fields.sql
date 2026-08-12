-- V7 健康档案结构化风险字段（62 号票）
-- 档案风险信息必须结构化、版本化，不得只保存自由文本后依赖 LLM 判断：
-- risk_conditions_json 保存 ProfileRiskCondition 枚举列表（孕产/当前伤病/术后康复/进食障碍/需医疗干预慢病），
-- risk_note 为选填自由说明（仅展示，不参与风险判定）。
-- 旧行两列均为 NULL，读取语义等同"未填写风险条件 = 无风险"，无需回填。

SET NAMES utf8mb4;

ALTER TABLE `health_profile`
  ADD COLUMN `risk_conditions_json` json NULL COMMENT '结构化风险条件（ProfileRiskCondition 枚举列表，选填，NULL 视为无风险）',
  ADD COLUMN `risk_note` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '风险说明（选填，最长 200 字符，仅作补充说明不参与判定）';
