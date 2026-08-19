-- V13 训练目标受控标签（#85）：动作目标标签来自确定性动作模式映射，不由 Prompt 宣称。
ALTER TABLE `exercise_item`
    ADD COLUMN `training_goals` json NULL COMMENT '受控训练目标标签（增肌/减脂/耐力/力量/保持健康）' AFTER `plan_ready`;

UPDATE `exercise_item`
SET `training_goals` = CASE
    WHEN `movement_pattern` = '有氧' THEN JSON_ARRAY('减脂', '耐力', '保持健康')
    ELSE JSON_ARRAY('增肌', '力量', '保持健康')
END
WHERE `training_goals` IS NULL;
