package com.diet.health.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 餐食 facet 规范词表与稳定来源键算法（标签唯一事实源的 Java 侧入口，ADR-0017）。
 * <p>
 * canonical 文件是版本化的 {@code data/meal/facets.json}，由 ETL 逐字节同步到
 * classpath {@code db/seed/meal_facets.json}；词表顺序即稳定键轮换顺序。
 * 稳定来源键与 Python ETL / MySQL V24 迁移逐字节对齐：
 * 复合键 = trim(source_name) + "\0" + trim(source_id)；
 * 纯十进制 source_id 用任意精度十进制取模；其他值用 UTF-8 CRC32 无符号值取模；
 * 模 n 的 n 为对应词表长度。禁止使用自增主键或裸 source_id 之外的猜测。
 * <p>
 * 演示分类边界：稳定键轮换产生的标签是演示语料的确定性占位（facetSource=STABLE_KEY_DEMO），
 * 不是人工考证的地域事实；API、页面与 Agent 文案不得将其描述为真实菜系来源。
 */
public final class MealFacetVocabulary {

    /** 维度键：菜系。 */
    public static final String CUISINE = "cuisine";
    /** 维度键：餐食类型。 */
    public static final String FOOD_TYPE = "foodType";

    private static final String RESOURCE = "db/seed/meal_facets.json";

    /** 启动即加载、缺失即失败的共享实例（种子校验、启动兜底、归一器共用同一词表）。 */
    public static final MealFacetVocabulary INSTANCE = load();

    private final String version;
    private final List<String> cuisines;
    private final List<String> foodTypes;

    private MealFacetVocabulary(String version, List<String> cuisines, List<String> foodTypes) {
        this.version = version;
        this.cuisines = List.copyOf(cuisines);
        this.foodTypes = List.copyOf(foodTypes);
    }

    public String version() {
        return version;
    }

    public List<String> cuisines() {
        return cuisines;
    }

    public List<String> foodTypes() {
        return foodTypes;
    }

    /** 维度词表（有序，顺序即轮换顺序）；未知维度快速失败。 */
    public List<String> values(String dimension) {
        return switch (dimension) {
            case CUISINE -> cuisines;
            case FOOD_TYPE -> foodTypes;
            default -> throw new IllegalArgumentException("未知 facet 维度：" + dimension);
        };
    }

    /** 值是否在该维度词表内（单字碎片不可能命中词表，天然被拒）。 */
    public boolean isLegal(String dimension, String value) {
        return value != null && values(dimension).contains(value);
    }

    /** 规范稳定来源键演示分类标签（与 ETL、V24 共用同一算法与词表顺序）。 */
    public String stableKeyDemoLabel(String dimension, String sourceName, String sourceId) {
        List<String> vocabulary = values(dimension);
        return vocabulary.get(stableKeyIndex(sourceName, sourceId, vocabulary.size()));
    }

    /** 稳定来源键取模索引：纯十进制 source_id 走任意精度十进制取模，否则走 UTF-8 CRC32 无符号取模。 */
    public int stableKeyIndex(String sourceName, String sourceId, int vocabularySize) {
        if (vocabularySize <= 0) {
            throw new IllegalArgumentException("词表长度必须为正");
        }
        String name = sourceName == null ? "" : sourceName.trim();
        String id = sourceId == null ? "" : sourceId.trim();
        // 仅 ASCII 十进制与 Python re.fullmatch(r"[0-9]+") / MySQL REGEXP 对齐；
        // Character.isDigit 会接受其他 Unicode 数字，BigInteger/SQL 对其解释并不一致。
        if (!id.isEmpty() && id.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return new BigInteger(id).mod(BigInteger.valueOf(vocabularySize)).intValue();
        }
        CRC32 crc = new CRC32();
        crc.update((name + "\0" + id).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % vocabularySize);
    }

    private static MealFacetVocabulary load() {
        try (InputStream in = MealFacetVocabulary.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到规范 facet 词表 " + RESOURCE
                        + "：请运行 scripts/build_reviewed_resources.py --facets-only 重新生成");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            return new MealFacetVocabulary(root.path("version").asText(),
                    readValues(root, "cuisines"), readValues(root, "foodTypes"));
        } catch (IOException e) {
            throw new IllegalStateException("规范 facet 词表读取失败：" + RESOURCE, e);
        }
    }

    private static List<String> readValues(JsonNode root, String field) {
        JsonNode array = root.path(field);
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalStateException("规范 facet 词表缺少非空数组字段：" + field);
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>(array.size());
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }
}
