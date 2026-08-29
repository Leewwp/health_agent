-- V24：餐食 facet 纠正迁移（餐食标签数据与回归网加固规格 / ADR-0017）。
-- 职责：修复已入库行上的空 facet、非空非法 facet（单字碎片、词表外值、旧混合类别残留），
--       统一收敛为规范稳定来源键演示分类；合法既有标签（数据可推导或人工事实）一律保留。
--       V22 按自增 id 轮换产生的"合法但非规范"标签不由本迁移猜测改写：标签唯一事实源是 ETL，
--       启动种子导入（INSERT … ON DUPLICATE KEY UPDATE 同步 cuisine/food_type）负责把旧库收敛到种子真值。
-- 范围：仅限演示语料行（source_type='PUBLIC'、owner_user_id IS NULL、review_status='APPROVED'）。
--       缺少复合来源身份（source_name/source_id 为空）的旧行不得猜测分类：保留合法既有标签，
--       出现非法 facet 时迁移失败并给出行级报告，绝不回退自增 id 轮换。
-- 稳定来源键（与 Python ETL / Java MealFacetVocabulary 逐字节对齐，见 ADR-0017）：
--   纯十进制 source_id → CAST(source_id AS DECIMAL(65,0)) MOD n（任意精度十进制取模）；
--   其他值 → CRC32(CONCAT(trim(source_name), CHAR(0), trim(source_id))) MOD n（UTF-8 CRC32 无符号取模）；
--   模 n 的 n 为对应词表长度，ELT 常量的词表与顺序即 data/meal/facets.json（漂移由 fresh-schema 集成测试阻挡）。

CREATE TEMPORARY TABLE `_meal_facets_v24` (
    `id` bigint NOT NULL PRIMARY KEY,
    `cuisine` json NOT NULL,
    `food_type` json NOT NULL
);

-- 修复函数：保留维度内合法值；合法值为空（原本为空、全部非法或残留单字碎片）时
-- 按稳定来源键重算单个演示分类。只处理至少一个维度需要修复的演示语料行。
INSERT INTO `_meal_facets_v24` (`id`, `cuisine`, `food_type`)
SELECT m.id,
       COALESCE(
           (SELECT JSON_ARRAYAGG(x.v)
            FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
            WHERE x.v IN ('粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜')
              AND CHAR_LENGTH(x.v) > 1),
           JSON_ARRAY(CASE WHEN m.source_id REGEXP '^[0-9]+$'
               THEN ELT(CAST(m.source_id AS DECIMAL(65,0)) MOD 13 + 1,
                    '粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜')
               ELSE ELT(CRC32(CONCAT(TRIM(m.source_name), CHAR(0 USING utf8mb4), TRIM(m.source_id))) MOD 13 + 1,
                    '粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜')
           END)),
       COALESCE(
           (SELECT JSON_ARRAYAGG(x.v)
            FROM JSON_TABLE(m.food_type, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
            WHERE x.v IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')
              AND CHAR_LENGTH(x.v) > 1),
           JSON_ARRAY(CASE WHEN m.source_id REGEXP '^[0-9]+$'
               THEN ELT(CAST(m.source_id AS DECIMAL(65,0)) MOD 11 + 1,
                    '素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')
               ELSE ELT(CRC32(CONCAT(TRIM(m.source_name), CHAR(0 USING utf8mb4), TRIM(m.source_id))) MOD 11 + 1,
                    '素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')
           END))
FROM meal_item m
WHERE m.source_type = 'PUBLIC'
  AND m.owner_user_id IS NULL
  AND m.review_status = 'APPROVED'
  AND m.source_name IS NOT NULL AND m.source_name <> ''
  AND m.source_id IS NOT NULL AND m.source_id <> ''
  AND (
      m.cuisine IS NULL OR JSON_TYPE(m.cuisine) <> 'ARRAY' OR JSON_LENGTH(m.cuisine) = 0
      OR EXISTS (SELECT 1 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                 WHERE x.v NOT IN ('粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜')
                    OR CHAR_LENGTH(x.v) < 2)
      OR m.food_type IS NULL OR JSON_TYPE(m.food_type) <> 'ARRAY' OR JSON_LENGTH(m.food_type) = 0
      OR EXISTS (SELECT 1 FROM JSON_TABLE(m.food_type, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                 WHERE x.v NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')
                    OR CHAR_LENGTH(x.v) < 2)
  );

UPDATE meal_item m
JOIN `_meal_facets_v24` f ON f.id = m.id
SET m.cuisine = f.cuisine,
    m.food_type = f.food_type;

DROP TEMPORARY TABLE `_meal_facets_v24`;

-- 守卫一：演示语料行（含复合来源身份）修复后仍不得为空或非法。
SET @v24_cohort_bad := (
    SELECT COUNT(1)
    FROM meal_item m
    WHERE m.source_type = 'PUBLIC' AND m.owner_user_id IS NULL AND m.review_status = 'APPROVED'
      AND m.source_name IS NOT NULL AND m.source_name <> ''
      AND m.source_id IS NOT NULL AND m.source_id <> ''
      AND (m.cuisine IS NULL OR JSON_TYPE(m.cuisine) <> 'ARRAY' OR JSON_LENGTH(m.cuisine) = 0
           OR EXISTS (SELECT 1 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                      WHERE x.v NOT IN ('粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜') OR CHAR_LENGTH(x.v) < 2)
           OR m.food_type IS NULL OR JSON_TYPE(m.food_type) <> 'ARRAY' OR JSON_LENGTH(m.food_type) = 0
           OR EXISTS (SELECT 1 FROM JSON_TABLE(m.food_type, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                      WHERE x.v NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤') OR CHAR_LENGTH(x.v) < 2))
);

-- 守卫二：缺少复合来源身份的演示库行不得猜测分类——保留合法既有标签，
-- 出现非法 facet（非数组、词表外值、单字碎片）时迁移失败并报告（空 facet 属用户/历史数据，保留不猜）。
SET @v24_identity_less_bad := (
    SELECT COUNT(1)
    FROM meal_item m
    WHERE m.source_type = 'PUBLIC' AND m.owner_user_id IS NULL
      AND (m.source_name IS NULL OR m.source_name = '' OR m.source_id IS NULL OR m.source_id = '')
      AND ((m.cuisine IS NOT NULL AND (JSON_TYPE(m.cuisine) <> 'ARRAY'
              OR EXISTS (SELECT 1 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                         WHERE x.v NOT IN ('粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜') OR CHAR_LENGTH(x.v) < 2)))
           OR (m.food_type IS NOT NULL AND (JSON_TYPE(m.food_type) <> 'ARRAY'
              OR EXISTS (SELECT 1 FROM JSON_TABLE(m.food_type, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                         WHERE x.v NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤') OR CHAR_LENGTH(x.v) < 2))))
);

-- 行级报告：迁移失败时 MESSAGE_TEXT 携带问题行数与样例行（id + 名称 + 当前 facet）。
SET @v24_sample := (
    SELECT SUBSTRING(GROUP_CONCAT(CONCAT('#', t.id, ' ', t.name, ' cuisine=', COALESCE(JSON_TYPE(t.cuisine), 'NULL'),
                                         ' food_type=', COALESCE(JSON_TYPE(t.food_type), 'NULL')) SEPARATOR '；'), 1, 380)
    FROM (
        SELECT m.id, m.name, m.cuisine, m.food_type
        FROM meal_item m
        WHERE m.source_type = 'PUBLIC' AND m.owner_user_id IS NULL
          AND (m.source_name IS NULL OR m.source_name = '' OR m.source_id IS NULL OR m.source_id = ''
               OR (m.review_status = 'APPROVED' AND m.source_name IS NOT NULL AND m.source_id IS NOT NULL))
          AND ((m.cuisine IS NOT NULL AND (JSON_TYPE(m.cuisine) <> 'ARRAY'
                  OR EXISTS (SELECT 1 FROM JSON_TABLE(m.cuisine, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                             WHERE x.v NOT IN ('粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜') OR CHAR_LENGTH(x.v) < 2)
                  OR (m.review_status = 'APPROVED' AND JSON_LENGTH(m.cuisine) = 0)))
               OR (m.food_type IS NOT NULL AND (JSON_TYPE(m.food_type) <> 'ARRAY'
                  OR EXISTS (SELECT 1 FROM JSON_TABLE(m.food_type, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x
                             WHERE x.v NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤') OR CHAR_LENGTH(x.v) < 2)
                  OR (m.review_status = 'APPROVED' AND JSON_LENGTH(m.food_type) = 0)))
               OR m.cuisine IS NULL OR m.food_type IS NULL)
        ORDER BY m.id
        LIMIT 5
    ) t
);

-- 守卫通过一次性存储过程触发（SIGNAL 不能进入 PREPARE 协议）；失败时 MESSAGE_TEXT
-- 携带问题行数与行级样例，迁移回滚、Flyway 标记失败；重试前先清理残留过程。
DROP PROCEDURE IF EXISTS _v24_report_guard;
CREATE PROCEDURE _v24_report_guard(IN cohort_bad INT, IN identity_less_bad INT, IN sample TEXT)
BEGIN
    DECLARE msg VARCHAR(512);
    IF cohort_bad > 0 OR identity_less_bad > 0 THEN
        SET msg = CONCAT('V24 餐食 facet 纠正失败：演示语料行残留空/非法 facet=', cohort_bad,
                '；无复合来源身份行含非法 facet=', identity_less_bad,
                '。行级样例：', COALESCE(sample, '无'),
                '。处理原则：合法既有标签保留，空/非法 facet 需人工核对来源身份后修复，禁止按自增 id 轮换伪造。');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = msg;
    END IF;
END;

CALL _v24_report_guard(@v24_cohort_bad, @v24_identity_less_bad, @v24_sample);
DROP PROCEDURE IF EXISTS _v24_report_guard;
