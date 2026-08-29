-- V23：修复 V21/V22 后的空 JSON 与遗漏类型，保证旧库和新种子遵守同一非空约束。
-- 已应用迁移不回写；本迁移只把可确定的旧类型（含烧烤）移入 food_type。
UPDATE meal_item
SET food_type = JSON_ARRAY()
WHERE food_type IS NULL OR JSON_TYPE(food_type) = 'NULL';

CREATE TEMPORARY TABLE `_meal_facets_v23` (
    `id` bigint NOT NULL PRIMARY KEY,
    `cuisine` json NOT NULL,
    `food_type` json NOT NULL
);

INSERT INTO `_meal_facets_v23` (`id`, `cuisine`, `food_type`)
SELECT m.id,
       COALESCE((SELECT JSON_ARRAYAGG(x.value)
                 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(value VARCHAR(64) PATH '$')) x
                 WHERE x.value NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')),
                JSON_ARRAY()),
       JSON_MERGE_PRESERVE(
           CASE WHEN JSON_TYPE(m.food_type) = 'ARRAY' THEN m.food_type ELSE JSON_ARRAY() END,
           COALESCE((SELECT JSON_ARRAYAGG(x.value)
                     FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(value VARCHAR(64) PATH '$')) x
                     WHERE x.value IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')),
                    JSON_ARRAY()))
FROM meal_item m
WHERE JSON_LENGTH(m.cuisine) > 0;

UPDATE meal_item m
JOIN `_meal_facets_v23` f ON f.id = m.id
SET m.cuisine = f.cuisine,
    m.food_type = f.food_type;

DROP TEMPORARY TABLE `_meal_facets_v23`;

ALTER TABLE meal_item
    MODIFY COLUMN `food_type` json NOT NULL COMMENT '餐食类型标签（素食/海鲜/家常等）';
