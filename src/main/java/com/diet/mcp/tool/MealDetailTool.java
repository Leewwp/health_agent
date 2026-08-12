package com.diet.mcp.tool;

import com.diet.health.module.HealthResource;
import com.diet.health.resource.HealthResourceProvider;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具 get_meal_detail（M5 #47）：按资源 ID 查审核餐食详情。
 * <p>
 * 只读统一审核资源 Provider（REVIEWED_DB/FIXTURE_SEED），不存在时返回 RESOURCE_NOT_FOUND。
 */
@Component
public class MealDetailTool implements McpToolSpec {

    static final String NAME = "get_meal_detail";

    private final HealthResourceProvider resourceProvider;

    public MealDetailTool(HealthResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mealId", Map.of("type", "integer",
                "description", "餐食资源 ID（审核资源模式为数据库主键，取 search_meals 返回的 resourceId）"));
        return McpToolSupport.tool(NAME,
                "按资源 ID 查询审核通过的餐食详情（只读）",
                properties, List.of("mealId"),
                outputSchema(),
                this::handle);
    }

    /** 输出契约（#63）：餐食详情封闭对象，不泄露内部字段。 */
    private static Map<String, Object> outputSchema() {
        return McpToolSupport.objectType(Map.of(
                "resourceId", McpToolSupport.stringType(),
                "name", McpToolSupport.stringType(),
                "sourceType", McpToolSupport.stringType(),
                "sourceName", McpToolSupport.stringType(),
                "tags", McpToolSupport.stringListTagsType()),
                List.of("resourceId", "name", "sourceType", "sourceName", "tags"));
    }

    private McpSchema.CallToolResult handle(Map<String, Object> args) {
        Object rawId = args.get("mealId");
        if (rawId == null || !(rawId instanceof Number)) {
            throw McpToolSupport.invalidParams("参数 mealId 必须是整数");
        }
        String mealId = String.valueOf(((Number) rawId).longValue());
        HealthResource meal = resourceProvider.mealById(mealId)
                .orElseThrow(() -> McpToolSupport.resourceNotFound("餐食 " + mealId + " 不存在或未通过审核"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resourceId", meal.resourceId());
        detail.put("name", meal.name());
        detail.put("sourceType", meal.sourceType());
        detail.put("sourceName", meal.sourceName());
        detail.put("tags", meal.tags());
        return McpToolSupport.success(detail, "餐食详情：" + meal.name());
    }
}
