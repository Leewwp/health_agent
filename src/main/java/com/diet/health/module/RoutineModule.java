package com.diet.health.module;

import com.diet.health.seed.SeedResources;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 作息领域模块：结构化事实查询，不做向量检索。
 * 匹配规则：输入中的作息相关关键词（睡眠/咖啡因/午睡/训练时段/规律）命中事实类别。
 */
@Service
public class RoutineModule {

    /** 关键词 → 事实类别 的映射（版本化，调整时同步种子版本）。使用有序 Map 固定优先级，更具体的关键词在前。 */
    private static final Map<String, String> KEYWORD_CATEGORY = createKeywordMap();

    private static Map<String, String> createKeywordMap() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("睡多久", "睡眠");
        map.put("几点睡", "睡眠");
        map.put("几点起", "睡眠");
        map.put("早起", "睡眠");
        map.put("咖啡", "咖啡因");
        map.put("午睡", "午睡");
        map.put("午休", "午睡");
        map.put("训练", "训练时段");
        map.put("运动", "训练时段");
        map.put("生物钟", "作息规律");
        map.put("规律", "作息规律");
        map.put("睡眠", "睡眠");
        return map;
    }

    /** 按关键词匹配返回事实，最多 3 条；无命中返回通用睡眠与规律事实。 */
    public List<RoutineFact> lookup(String userInput, Map<String, List<String>> slots) {
        List<RoutineFact> all = SeedResources.ROUTINE_FACTS;
        String matchedCategory = findCategory(userInput);
        if (matchedCategory != null) {
            final String category = matchedCategory;
            List<RoutineFact> hits = all.stream()
                    .filter(fact -> fact.category().equals(category))
                    .limit(3)
                    .toList();
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        return all.stream().limit(3).toList();
    }

    /** 从输入中匹配第一个作息关键词，返回事实类别。 */
    private String findCategory(String userInput) {
        if (userInput == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : KEYWORD_CATEGORY.entrySet()) {
            if (userInput.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 事实 → 类型化资源（结构化事实标识）。 */
    public HealthResource toResource(RoutineFact fact) {
        return new HealthResource(
                "FACT",
                fact.factId(),
                fact.fact(),
                "SOURCE",
                fact.sourceName(),
                null,
                false,
                Map.of("category", List.of(fact.category()))
        );
    }
}
