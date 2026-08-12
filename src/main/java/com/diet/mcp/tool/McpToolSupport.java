package com.diet.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四个公共 MCP Tools 的共享构造与校验助手（M5 #47）。
 * <p>
 * 工具只读或纯计算：不开放写档案、生成/激活计划、写反馈、读取 Trace 或任意数据库查询。
 * 参数错误抛 {@link McpError#INVALID_PARAMS}，领域资源不存在抛 {@link McpError#RESOURCE_NOT_FOUND}，
 * 其余业务失败以 isError 结果返回——符合 MCP 错误语义。
 */
@Component
public final class McpToolSupport {

    /** search_meals 等列表工具的 limit 上限。 */
    public static final int MAX_LIMIT = 10;

    private McpToolSupport() {
    }

    /** 构造工具规格：Schema 必须显式声明每个参数类型与 required，供客户端校验与白名单审计。 */
    public static McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                               Map<String, Object> properties,
                                                               List<String> required,
                                                               Map<String, Object> outputSchema,
                                                               ToolHandler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema("object", properties, required, null, null, null))
                .outputSchema(outputSchema)
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, args) -> handler.handle(args));
    }

    // ---- 输出 Schema 构造（#63：每个 Tool 声明明确 outputSchema，structuredContent 必须通过校验） ----

    public static Map<String, Object> stringType() {
        return Map.of("type", "string");
    }

    public static Map<String, Object> integerType() {
        return Map.of("type", "integer");
    }

    public static Map<String, Object> numberType() {
        return Map.of("type", "number");
    }

    public static Map<String, Object> booleanType() {
        return Map.of("type", "boolean");
    }

    public static Map<String, Object> stringArrayType() {
        return Map.of("type", "array", "items", stringType());
    }

    /** 字符串 → 字符串数组的标签对象（餐食 tags 形状）。 */
    public static Map<String, Object> stringListTagsType() {
        return Map.of("type", "object", "additionalProperties", stringArrayType());
    }

    public static Map<String, Object> arrayType(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    /** 封闭对象 Schema：额外字段一律拒绝（结构化结果必须与契约完全一致）。 */
    public static Map<String, Object> objectType(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 读取必填 String 参数；缺失/非字符串抛 INVALID_PARAMS。 */
    public static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw invalidParams("参数 " + key + " 必须是非空字符串");
        }
        return s;
    }

    /** 读取可选 String 参数，缺失返回 null。 */
    public static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String s ? s : null;
    }

    /** 读取可选整数参数，缺失返回默认值。 */
    public static int optionalInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw invalidParams("参数 " + key + " 必须是整数");
        }
        return number.intValue();
    }

    /** 读取可选整数列表参数，缺失返回空列表。 */
    public static List<Long> optionalLongList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalidParams("参数 " + key + " 必须是整数数组");
        }
        List<Long> result = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Number number)) {
                throw invalidParams("参数 " + key + " 的元素必须是整数");
            }
            result.add(number.longValue());
        }
        return result;
    }

    /** 读取必填槽位 Map（值必须为字符串数组），缺失或非对象抛 INVALID_PARAMS。 */
    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> requireSlots(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidParams("参数 " + key + " 必须是对象");
        }
        Map<String, List<String>> slots = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String k) || !(entry.getValue() instanceof List<?> list)) {
                throw invalidParams("参数 " + key + " 的值必须是字符串数组");
            }
            slots.put(k, list.stream().map(String::valueOf).toList());
        }
        return slots;
    }

    /** 成功结果：structuredContent 携带机器可读结果，textContent 为人类可读摘要。 */
    public static McpSchema.CallToolResult success(Object structuredContent, String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .structuredContent(structuredContent)
                .build();
    }

    public static McpError invalidParams(String message) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                .message(message)
                .build();
    }

    public static McpError resourceNotFound(String message) {
        return McpError.builder(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND)
                .message(message)
                .build();
    }

    @FunctionalInterface
    public interface ToolHandler {
        McpSchema.CallToolResult handle(Map<String, Object> args);
    }
}
