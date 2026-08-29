-- V21：统一餐食菜系与餐食类型标签。旧 cuisine 中的类型类标签迁移至 food_type。
ALTER TABLE `meal_item`
    ADD COLUMN `food_type` json NULL COMMENT '餐食类型标签（素食/海鲜/家常等）' AFTER `cuisine`;

CREATE TEMPORARY TABLE `_meal_facets_v21` (
    `id` bigint NOT NULL PRIMARY KEY,
    `cuisine` json NOT NULL,
    `food_type` json NOT NULL
);

INSERT INTO `_meal_facets_v21` (`id`, `cuisine`, `food_type`)
SELECT m.id,
       COALESCE((SELECT JSON_ARRAYAGG(x.value)
                 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(value VARCHAR(64) PATH '$')) x
                 WHERE x.value NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃')),
                JSON_ARRAY()),
       COALESCE((SELECT JSON_ARRAYAGG(x.value)
                 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(value VARCHAR(64) PATH '$')) x
                 WHERE x.value IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃')),
                JSON_ARRAY())
FROM meal_item m;

UPDATE meal_item m
JOIN `_meal_facets_v21` f ON f.id = m.id
SET m.cuisine = f.cuisine,
    m.food_type = f.food_type;

DROP TEMPORARY TABLE `_meal_facets_v21`;

ALTER TABLE `meal_item`
    MODIFY COLUMN `food_type` json NOT NULL COMMENT '餐食类型标签（素食/海鲜/家常等）';

-- 演示数据补齐核心菜系，便于 Agent 输入与餐食库筛选保持同一词汇。
UPDATE meal_item SET cuisine = JSON_ARRAY('粤菜') WHERE source_type = 'PUBLIC' AND id IN (1, 2, 3);
UPDATE meal_item SET cuisine = JSON_ARRAY('川菜') WHERE source_type = 'PUBLIC' AND id = 4;

-- 槽位字典与餐食库使用同一组可见词汇；旧混合类别移到 foodType。
DELETE FROM diet_slot_option WHERE slot_name = 'cuisine'
    AND option_value IN ('海鲜','甜品','粥汤','素食','轻食','粉面','小吃','家常','快餐','火锅','烧烤');
INSERT INTO diet_slot_option (slot_name, option_value, sort_order, enabled, created_at, updated_at)
SELECT 'cuisine', v.value, v.sort_order, 1, NOW(), NOW()
FROM (SELECT '粤菜' value, 10 sort_order UNION ALL SELECT '川菜',20 UNION ALL SELECT '湘菜',30
      UNION ALL SELECT '江浙菜',40 UNION ALL SELECT '东北菜',50 UNION ALL SELECT '鲁菜',60
      UNION ALL SELECT '闽南菜',70 UNION ALL SELECT '云南菜',80 UNION ALL SELECT '新疆菜',90
      UNION ALL SELECT '西餐',100 UNION ALL SELECT '日料',110 UNION ALL SELECT '韩餐',120
      UNION ALL SELECT '东南亚菜',130) v
WHERE NOT EXISTS (SELECT 1 FROM diet_slot_option o WHERE o.slot_name = 'cuisine' AND o.option_value = v.value);
INSERT INTO diet_slot_option (slot_name, option_value, sort_order, enabled, created_at, updated_at)
SELECT 'foodType', v.value, v.sort_order, 1, NOW(), NOW()
FROM (SELECT '素食' value, 10 sort_order UNION ALL SELECT '海鲜',20 UNION ALL SELECT '家常',30
      UNION ALL SELECT '轻食',40 UNION ALL SELECT '粉面',50 UNION ALL SELECT '粥汤',60
      UNION ALL SELECT '快餐',70 UNION ALL SELECT '甜品',80 UNION ALL SELECT '火锅',90
      UNION ALL SELECT '小吃',100 UNION ALL SELECT '烧烤',110) v
WHERE NOT EXISTS (SELECT 1 FROM diet_slot_option o WHERE o.slot_name = 'foodType' AND o.option_value = v.value);
