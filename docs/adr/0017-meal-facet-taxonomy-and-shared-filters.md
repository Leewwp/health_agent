# 餐食菜系与类型标签统一

Status: accepted

餐食库的筛选字段必须是 Agent 餐食输入字段的可见子集，避免用户从餐食库选项推断出 Agent 实际不支持的条件。我们将地域/烹饪体系定义为 `cuisine`，将素食、海鲜、轻食、粉面、粥汤等定义为 `foodType`；两者均支持多选，同维度内为 OR，不同维度之间为 AND。数据库新增 `food_type` JSON 字段，旧记录中的混合类别迁移到该字段，并补充少量演示菜系数据；旧会话中的单值 `cuisine` 仍可读为单元素集合，新写入只使用数组。心情、场景、过敏原、热量和自由文本继续作为 Agent 上下文条件，只有实际被生成或推荐逻辑消费时才向用户承诺其作用。

## Consequences

- 餐食库、旧饮食链路、健康推荐和周计划共享同一组基础菜系/类型词汇；“三餐”作为早餐、午餐和晚餐的通用候选统一处理。
- 需要一次数据库迁移、种子 ETL 更新以及推荐、计划和前端筛选器的字段升级。
- 演示数据的标签是产品筛选分类，不等同于原始食谱的营养或地域事实；来源和营养字段仍保持原有口径。

## 增补（2026-08-29，餐食标签加固规格）：三条收敛决策

### 决策一：稳定来源键演示分类算法

演示语料中数据无法推导 facet 的行，由**复合稳定来源键**轮换生成确定性演示分类，替代此前 V22/启动兜底按自增主键 `MOD(id, n)` 轮换的伪造机制。算法在 Python ETL（`scripts/build_reviewed_resources.py`）、Java（`MealFacetVocabulary`）与 MySQL（V24 迁移）三处逐字节对齐：

- 复合键输入：`trim(source_name) + "\0" + trim(source_id)`；禁止使用数据库自增 id 或裸 `source_id` 以外的任何猜测。
- 取模规则：`source_id` 为纯十进制时用任意精度十进制取模（Python `int` / Java `BigInteger` / MySQL `CAST(source_id AS DECIMAL(65,0)) MOD n`）；其他值用复合键 UTF-8 字节的 CRC32 无符号值取模（`zlib.crc32 & 0xFFFFFFFF` / `java.util.zip.CRC32.getValue()` / MySQL `CRC32()`）。
- 词表与轮换顺序：`data/meal/facets.json`（canonical，含版本），模 n 的 n 为对应维度词表长度，索引 0 起。
- 分层修复契约：V24 纠正迁移只修复演示语料行上**空/非法**（非数组、词表外值、单字碎片）的 facet；V22 轮换出的"合法但非规范"标签不由迁移猜测改写，由启动种子导入（餐食语句 `INSERT … AS new ON DUPLICATE KEY UPDATE` 同步 `cuisine/food_type`）收敛——ETL 是标签唯一事实源。seed 不在库内的演示行由启动兜底按同一算法只填空；缺少复合来源身份的旧行不生成任何标签，出现非法 facet 时迁移失败并给出行级报告。

### 决策二：演示分类的 provenance 边界

稳定键轮换产生的标签是**演示分类**（`facetSource=STABLE_KEY_DEMO`），不是人工考证的地域事实：

- ETL 报告与 corpus manifest 必须记录 `facetSource` 溯源、规范词表哈希与人工策展输入（`scripts/meal_curation/manual_meals.json`）哈希。
- API、页面与 Agent 文案不得把轮换标签描述为经过人工考证的菜系来源；数据可推导的标签（`facetSource=DATA`）保持既有口径。
- 若将语料用于真实地域筛选或对外声称"菜系事实"，必须先完成逐行人工策展并移除演示分类，不得把轮换结果包装成真实来源证据。

### 决策三：餐食类型词表外的诚实通道

`foodType` 获得与 cuisine 对称的未支持通道（`MealFoodTypeIntentParser`）：

- 只有显式类型形态（`餐食类型：X`、`餐食类型是 X`、`想吃 X`、`X 类型`）允许登记词表外原值；标签前缀形态最显式，原值一律登记；弱形态（想吃/X 类型）候选先过跨槽位守卫（属于餐次/口味/便利等其他餐食槽位的表达不登记）。
- 词表外值保留在 `foodTypes` 数组中并按稳定键 `foodType:<value>` 登记一次到 `unsupportedPreferences`；不参与筛选或生成；正向/否定（剥除否定范围）/"换成/改为"替换语义与 cuisine 完全对称；支持中文标点、和/或/以及、多值去重。
- 模型 raw slot 中未被显式解析器认可的未知值仍被丢弃（只有受限解析器能产生未支持原值）；简报重启恢复必须完整还原 `foodTypes` 数组与未支持集合。
