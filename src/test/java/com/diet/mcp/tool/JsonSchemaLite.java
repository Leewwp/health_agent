package com.diet.mcp.tool;

import java.util.List;
import java.util.Map;

/**
 * 最小 JSON Schema 校验器（#63 契约测试专用，非生产代码）。
 * <p>
 * 只支持本仓库输出 Schema 使用的子集：type(string/integer/number/boolean/object/array)、
 * properties + required、additionalProperties(boolean 或 Map Schema)、items(单 Schema)。
 * 校验失败返回错误描述，成功返回 null；故意构造缺字段/错误类型/额外内部字段时
 * 必须能失败，以证明 outputSchema 与 structuredContent 契约真实有效。
 */
public final class JsonSchemaLite {

    private JsonSchemaLite() {
    }

    /** 校验 value 是否符合 schema；符合返回 null，否则返回可读错误描述。 */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> schema, Object value) {
        String type = (String) schema.get("type");
        if (type == null) {
            return "schema 缺少 type";
        }
        switch (type) {
            case "string" -> {
                return value instanceof String ? null : "期望 string，实际 " + describe(value);
            }
            case "integer" -> {
                return value instanceof Integer || value instanceof Long
                        ? null : "期望 integer，实际 " + describe(value);
            }
            case "number" -> {
                return value instanceof Number ? null : "期望 number，实际 " + describe(value);
            }
            case "boolean" -> {
                return value instanceof Boolean ? null : "期望 boolean，实际 " + describe(value);
            }
            case "array" -> {
                if (!(value instanceof List<?> list)) {
                    return "期望 array，实际 " + describe(value);
                }
                Map<String, Object> items = (Map<String, Object>) schema.get("items");
                for (int i = 0; i < list.size(); i++) {
                    String error = items == null ? null : validate(items, list.get(i));
                    if (error != null) {
                        return "array[" + i + "] " + error;
                    }
                }
                return null;
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?> map)) {
                    return "期望 object，实际 " + describe(value);
                }
                List<String> required = (List<String>) schema.get("required");
                if (required != null) {
                    for (String key : required) {
                        if (!map.containsKey(key)) {
                            return "缺少必填字段 " + key;
                        }
                    }
                }
                Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (properties == null || !properties.containsKey(key)) {
                        Object additional = schema.get("additionalProperties");
                        if (additional instanceof Map<?, ?> additionalSchema) {
                            String error = validate((Map<String, Object>) additionalSchema, entry.getValue());
                            if (error != null) {
                                return key + " " + error;
                            }
                            continue;
                        }
                        if (Boolean.TRUE.equals(additional)) {
                            continue;
                        }
                        return "出现未声明的额外字段 " + key;
                    }
                    String error = validate((Map<String, Object>) properties.get(key), entry.getValue());
                    if (error != null) {
                        return key + " " + error;
                    }
                }
                return null;
            }
            default -> {
                return "不支持的 schema type: " + type;
            }
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
