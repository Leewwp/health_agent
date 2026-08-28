package com.diet.health.module;

import com.diet.health.resource.HealthResourceProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作息领域模块：结构化事实查询，不做向量检索。
 * 匹配规则：输入中的作息相关关键词（睡眠/咖啡因/午睡/训练时段/规律）命中事实类别；
 * 事实来源为统一审核资源 Provider（正式=数据库审核子集 topic，fixture=内存种子类别）。
 */
@Service
public class RoutineModule {

    /** 关键词 → 事实类别 的映射（版本化，调整时同步种子版本）。使用有序 Map 固定优先级，更具体的关键词在前。 */
    private static final Map<String, String> KEYWORD_CATEGORY = createKeywordMap();

    /**
     * 事实类别 → 匹配的 topic/类别 集合。
     * 正式模式事实的 category 即数据库 topic（如"睡眠时长"），fixture 模式为种子类别（如"睡眠"），
     * 集合同时收录两者，保证关键词在两种模式下命中同一类事实，为 43 号作息关键词命中打基础。
     */
    private static final Map<String, List<String>> CATEGORY_TOPICS = Map.of(
            "睡眠", List.of("睡眠", "睡眠时长", "睡眠时长下限", "屏幕蓝光", "晚间训练", "晚间高强度"),
            "咖啡因", List.of("咖啡因"),
            "午睡", List.of("午睡", "午睡时长", "午睡时段", "午睡上限风险"),
            "训练时段", List.of("训练时段", "晚间训练", "晚间高强度", "身体活动"),
            "作息规律", List.of("作息规律")
    );

    private final HealthResourceProvider resourceProvider;

    public RoutineModule(HealthResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
    }

    private static Map<String, String> createKeywordMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("睡多久", "睡眠");
        map.put("几点睡", "睡眠");
        map.put("几点起", "睡眠");
        map.put("早起", "睡眠");
        map.put("咖啡", "咖啡因");
        map.put("午睡", "午睡");
        map.put("午休", "午睡");
        map.put("训练", "训练时段");
        map.put("运动", "训练时段");
        map.put("锻炼", "训练时段");
        map.put("生物钟", "作息规律");
        map.put("规律", "作息规律");
        map.put("睡眠", "睡眠");
        return map;
    }

    /** 按关键词匹配返回事实，最多 3 条；无明确事实关键词时返回空，避免把未知问题猜成睡眠事实。 */
    public List<RoutineFact> lookup(String userInput, Map<String, List<String>> slots) {
        List<RoutineFact> all = resourceProvider.routineFacts();
        String matchedCategory = findCategory(userInput);
        if (matchedCategory != null) {
            List<RoutineFact> hits = filterByCategory(all, matchedCategory);
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        return List.of();
    }

    /** 明确的通用事实问题可直接查询；个性化作息安排仍交给澄清或计划入口。 */
    public boolean supportsFactQuery(String userInput) {
        if (userInput == null || findCategory(userInput) == null) {
            return false;
        }
        return !containsAny(userInput, "帮我安排", "帮我规划", "制定", "调整我的", "适合我的作息", "我的作息计划");
    }

    /** 按类别对应的 topic/类别 集合过滤，取前 3 条（Provider 返回有序，保持确定性）。 */
    private List<RoutineFact> filterByCategory(List<RoutineFact> facts, String category) {
        List<String> topics = CATEGORY_TOPICS.getOrDefault(category, List.of());
        return facts.stream()
                .filter(fact -> topics.contains(fact.category()))
                .limit(3)
                .toList();
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

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 事实 → 类型化资源（结构化事实标识，resourceType 统一 ROUTINE）。 */
    public HealthResource toResource(RoutineFact fact) {
        return new HealthResource(
                "ROUTINE",
                fact.factId(),
                fact.fact(),
                "SOURCE",
                fact.sourceName(),
                null,
                false,
                Map.of("category", List.of(fact.category()))
        );
    }

    /** 全部作息事实 ID（供计划校验资源目录使用）。 */
    public List<String> allFactIds() {
        return resourceProvider.allFactIds();
    }
}
