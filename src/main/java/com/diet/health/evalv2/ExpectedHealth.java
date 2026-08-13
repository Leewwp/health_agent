package com.diet.health.evalv2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * health-eval-v2 结构化 gold 标注（契约 §2）：只保存需要人工判断的事实。
 * <p>
 * 字段缺失表示该样本不评估对应指标；显式空对象/数组表示 gold 明确应为空。
 * 同时用于 BENCHMARK JSONL（gold.expectedHealth）与 V9 迁移列 expected_health_json。
 */
public record ExpectedHealth(
        String schemaVersion,
        String expectedDomain,
        String expectedTask,
        Map<String, List<String>> expectedSlots,
        String expectedRiskLevel,
        String expectedResponseType,
        List<String> expectedMissingSlots
) {

    public static final String SCHEMA_VERSION = "health-eval-v2";

    public ExpectedHealth {
        expectedSlots = expectedSlots == null ? Map.of() : expectedSlots;
        expectedMissingSlots = expectedMissingSlots == null ? List.of() : expectedMissingSlots;
    }

    /** 从 JSON 节点解析；null/缺失节点返回 null（表示该样本无 gold）。 */
    public static ExpectedHealth parse(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new ExpectedHealth(
                text(node, "schemaVersion"),
                text(node, "expectedDomain"),
                text(node, "expectedTask"),
                slots(node.path("expectedSlots")),
                text(node, "expectedRiskLevel"),
                text(node, "expectedResponseType"),
                stringList(node.path("expectedMissingSlots"))
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static Map<String, List<String>> slots(JsonNode node) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!node.isObject()) {
            return result;
        }
        node.properties().forEach(entry -> {
            List<String> values = stringList(entry.getValue());
            if (!values.isEmpty()) {
                result.put(entry.getKey(), values);
            }
        });
        return result;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText().trim());
                }
            });
        } else if (node.isTextual() && !node.asText().isBlank()) {
            result.add(node.asText().trim());
        }
        return result;
    }

    /** 单条槽位事实（微精确率/召回率的统计单元）。 */
    public record SlotFact(String slotName, String value) {
    }
}
