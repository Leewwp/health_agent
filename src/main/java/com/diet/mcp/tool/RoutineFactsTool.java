package com.diet.mcp.tool;

import com.diet.health.module.RoutineFact;
import com.diet.health.module.RoutineModule;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具 get_routine_facts（M5 #47）：按关键词查结构化作息事实。
 * <p>
 * 复用 RoutineModule 的确定性关键词匹配（睡眠/咖啡因/午睡/训练时段/作息规律），
 * 返回最多 3 条事实与来源引用；只读。
 */
@Component
public class RoutineFactsTool implements McpToolSpec {

    static final String NAME = "get_routine_facts";

    private final RoutineModule routineModule;

    public RoutineFactsTool(RoutineModule routineModule) {
        this.routineModule = routineModule;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of("type", "string",
                "description", "作息关键词（如'睡多久''咖啡'），未命中时返回睡眠类通用事实"));
        return McpToolSupport.tool(NAME,
                "按关键词查询结构化作息事实与来源引用（只读，最多 3 条）",
                properties, List.of("keyword"),
                outputSchema(),
                this::handle);
    }

    /** 输出契约（#63）：facts 数组 + count；事实元素含来源引用，无内部字段。 */
    private static Map<String, Object> outputSchema() {
        Map<String, Object> fact = McpToolSupport.objectType(Map.of(
                "factId", McpToolSupport.stringType(),
                "category", McpToolSupport.stringType(),
                "fact", McpToolSupport.stringType(),
                "sourceName", McpToolSupport.stringType(),
                "sourceDetail", McpToolSupport.stringType()), List.of("factId", "category", "fact"));
        return McpToolSupport.objectType(Map.of(
                "facts", McpToolSupport.arrayType(fact),
                "count", McpToolSupport.integerType()), List.of("facts", "count"));
    }

    private McpSchema.CallToolResult handle(Map<String, Object> args) {
        String keyword = McpToolSupport.requireString(args, "keyword");
        List<RoutineFact> facts = routineModule.lookup(keyword, Map.of());
        List<Map<String, Object>> results = facts.stream().map(fact -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("factId", fact.factId());
            item.put("category", fact.category());
            item.put("fact", fact.fact());
            item.put("sourceName", fact.sourceName());
            item.put("sourceDetail", fact.sourceDetail());
            return item;
        }).toList();
        return McpToolSupport.success(Map.of("facts", results, "count", results.size()),
                "查得 " + results.size() + " 条作息事实");
    }
}
