package com.diet.mcp.tool;

import com.diet.health.module.HealthResource;
import com.diet.health.module.MealModule;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具 search_meals（M5 #47）：按健康槽位检索审核餐食。
 * <p>
 * 复用健康链路 recommendMeals（hybrid/结构化检索 + 审核资源 + 硬约束 + 偏好），
 * 不绕开领域规则；只读，不产生任何业务写入。
 */
@Component
public class MealSearchTool implements McpToolSpec {

    static final String NAME = "search_meals";

    private final MealModule mealModule;

    public MealSearchTool(MealModule mealModule) {
        this.mealModule = mealModule;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("slots", Map.of("type", "object",
                "description", "健康槽位：mealTime/mood/scene/healthGoal/cuisine/taste/convenience/allergen 等，值为字符串数组"));
        properties.put("text", Map.of("type", "string",
                "description", "嵌入语义文本（如'增肌晚餐'），为空时用槽位值拼接兜底"));
        properties.put("excludeIds", Map.of("type", "array", "items", Map.of("type", "integer"),
                "description", "需要排除的餐食 ID（如已推荐过的）"));
        properties.put("limit", Map.of("type", "integer",
                "description", "返回条数上限（默认 5，最大 " + McpToolSupport.MAX_LIMIT + "）"));
        return McpToolSupport.tool(NAME,
                "按健康槽位检索审核通过的公共餐食（只读，走与健康聊天相同的硬约束与检索规则）",
                properties, List.of("slots"),
                outputSchema(),
                this::handle);
    }

    /** 输出契约（#63）：meals 数组 + count；meals 元素为资源标识/名称/来源/标签封闭对象。 */
    private static Map<String, Object> outputSchema() {
        Map<String, Object> meal = McpToolSupport.objectType(Map.of(
                "resourceId", McpToolSupport.stringType(),
                "name", McpToolSupport.stringType(),
                "sourceType", McpToolSupport.stringType(),
                "tags", McpToolSupport.stringListTagsType()), List.of("resourceId", "name", "tags"));
        return McpToolSupport.objectType(Map.of(
                "meals", McpToolSupport.arrayType(meal),
                "count", McpToolSupport.integerType()), List.of("meals", "count"));
    }

    private McpSchema.CallToolResult handle(Map<String, Object> args) {
        Map<String, List<String>> slots = McpToolSupport.requireSlots(args, "slots");
        String text = McpToolSupport.optionalString(args, "text");
        List<Long> excludeIds = McpToolSupport.optionalLongList(args, "excludeIds");
        int limit = McpToolSupport.optionalInt(args, "limit", 5);
        if (limit < 1 || limit > McpToolSupport.MAX_LIMIT) {
            throw McpToolSupport.invalidParams("limit 必须在 1 到 " + McpToolSupport.MAX_LIMIT + " 之间");
        }
        List<String> typedExcludeIds = excludeIds.stream().map(String::valueOf).toList();
        List<HealthResource> meals = mealModule.recommendMeals(slots, typedExcludeIds, text);
        List<Map<String, Object>> results = meals.stream().limit(limit)
                .map(meal -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("resourceId", meal.resourceId());
                    item.put("name", meal.name());
                    item.put("sourceType", meal.sourceType());
                    item.put("tags", meal.tags());
                    return item;
                })
                .toList();
        return McpToolSupport.success(Map.of("meals", results, "count", results.size()),
                "检索到 " + results.size() + " 个餐食候选");
    }
}
