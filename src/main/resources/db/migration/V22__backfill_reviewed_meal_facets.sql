-- V22：演示库历史审核餐食缺少新标签时，按稳定主键补齐可见 facets。
-- 仅填充空标签，不覆盖已有审核数据、营养数据或资源身份。
UPDATE meal_item
SET cuisine = JSON_ARRAY(CASE MOD(id, 13)
    WHEN 0 THEN '粤菜' WHEN 1 THEN '川菜' WHEN 2 THEN '湘菜' WHEN 3 THEN '江浙菜'
    WHEN 4 THEN '东北菜' WHEN 5 THEN '鲁菜' WHEN 6 THEN '闽南菜' WHEN 7 THEN '云南菜'
    WHEN 8 THEN '新疆菜' WHEN 9 THEN '西餐' WHEN 10 THEN '日料' WHEN 11 THEN '韩餐'
    ELSE '东南亚菜' END)
WHERE source_type = 'PUBLIC' AND review_status = 'APPROVED' AND JSON_LENGTH(cuisine) = 0;

UPDATE meal_item
SET food_type = JSON_ARRAY(CASE MOD(id, 11)
    WHEN 0 THEN '素食' WHEN 1 THEN '海鲜' WHEN 2 THEN '家常' WHEN 3 THEN '轻食'
    WHEN 4 THEN '粉面' WHEN 5 THEN '粥汤' WHEN 6 THEN '快餐' WHEN 7 THEN '甜品'
    WHEN 8 THEN '火锅' WHEN 9 THEN '小吃' ELSE '烧烤' END)
WHERE source_type = 'PUBLIC' AND review_status = 'APPROVED' AND JSON_LENGTH(food_type) = 0;
