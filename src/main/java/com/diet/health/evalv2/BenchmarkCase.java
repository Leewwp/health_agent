package com.diet.health.evalv2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BENCHMARK 单条样本（契约 §2）：最小评估单位是一轮请求对应的一条 Trace，
 * 多轮样本以 caseId + turnIndex 关联；无法唯一判断的样本标记 excludedReason。
 * <p>
 * 字段：datasetId/datasetVersion/caseId/turnIndex/caseType/input/initialContext/gold；
 * PLAN_VALIDATION 样本额外带 planInput，直接调用 PlanValidationService 校验。
 * 单标注者两遍复核：labeledAt/reviewedAt/reviewStatus=REVIEWED，不声称多人标注。
 */
public record BenchmarkCase(
        String datasetId,
        String datasetVersion,
        String caseId,
        int turnIndex,
        String caseType,
        String input,
        Map<String, Object> initialContext,
        ExpectedHealth gold,
        PlanGold planGold,
        PlanValidationInput planInput,
        String excludedReason,
        String labeledAt,
        String reviewedAt,
        String reviewStatus
) {

    /** REVIEWED 状态常量（单标注者两遍复核完成）。 */
    public static final String REVIEW_STATUS_REVIEWED = "REVIEWED";

    /** 计划校验样本类型：直接调 PlanValidationService，不从聊天 Trace 推断。 */
    public static final String CASE_TYPE_PLAN_VALIDATION = "PLAN_VALIDATION";

    /** 计划样本的 gold：期望校验等级与命中规则码。 */
    public record PlanGold(String expectedLevel, List<String> expectedRuleCodes) {
    }

    /** 样本是否被排除（不进入任何指标分母）。 */
    public boolean excluded() {
        return excludedReason != null && !excludedReason.isBlank();
    }

    /** TRACE_AUDIT：从真实 Trace 行构造评估样本（caseId=traceId，gold 来自 V9 expected_health_json）。 */
    public static BenchmarkCase audit(String caseId, ExpectedHealth gold) {
        return new BenchmarkCase(
                "health-eval-v2-trace-audit", "1.0.0", caseId, 1, "TRACE_AUDIT",
                null, Map.of(), gold, null, null, null, null, null, null
        );
    }

    /** 从 JSONL 单行解析；必填字段缺失或非法时抛 IllegalArgumentException。 */
    public static BenchmarkCase parse(JsonNode node) {
        String datasetId = requiredText(node, "datasetId");
        String datasetVersion = requiredText(node, "datasetVersion");
        String caseId = requiredText(node, "caseId");
        int turnIndex = node.path("turnIndex").asInt(1);
        String caseType = requiredText(node, "caseType");
        String input = requiredText(node, "input");
        Map<String, Object> initialContext = object(node.path("initialContext"));
        ExpectedHealth gold = ExpectedHealth.parse(node.path("gold").path("expectedHealth"));
        PlanGold planGold = planGold(node.path("gold").path("planValidation"));
        PlanValidationInput planInput = PlanValidationInput.parse(node.path("planInput"));
        String excludedReason = text(node, "excludedReason");
        if (excludedReason != null && gold == null && planInput == null) {
            throw new IllegalArgumentException("样本被排除也必须保留 gold 或 planInput 供诊断: " + caseId);
        }
        return new BenchmarkCase(
                datasetId, datasetVersion, caseId, turnIndex, caseType, input, initialContext,
                gold, planGold, planInput, excludedReason,
                text(node, "labeledAt"), text(node, "reviewedAt"), text(node, "reviewStatus")
        );
    }

    private static PlanGold planGold(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String level = node.path("expectedLevel").asText(null);
        List<String> rules = new ArrayList<>();
        JsonNode rulesNode = node.path("expectedRuleCodes");
        if (rulesNode.isArray()) {
            rulesNode.forEach(item -> {
                if (item.isTextual()) {
                    rules.add(item.asText());
                }
            });
        }
        return level == null ? null : new PlanGold(level, rules);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText(null) == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("JSONL 样本缺少必填字段: " + field);
        }
        return value.asText().trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static Map<String, Object> object(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** PLAN_VALIDATION 输入：固定 Profile + PlanItemDraft 列表 + 资源目录。 */
    public record PlanValidationInput(ProfileInput profile, CatalogInput catalog, List<ItemInput> items) {

        public record ProfileInput(int age, int calorieLow, int calorieHigh) {
        }

        public record CatalogInput(List<String> planReadyExerciseIds, List<String> knownExerciseIds,
                                   List<String> knownRoutineFactIds) {
        }

        public record ItemInput(String resourceType, String resourceId, String name, String localDate,
                                String startTime, String endTime, Map<String, Object> planParams) {
        }

        public static PlanValidationInput parse(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            JsonNode profile = node.path("profile");
            if (!profile.isObject()) {
                throw new IllegalArgumentException("planInput.profile 必须存在");
            }
            JsonNode catalog = node.path("catalog");
            List<ItemInput> items = new ArrayList<>();
            JsonNode itemsNode = node.path("items");
            if (itemsNode.isArray()) {
                itemsNode.forEach(item -> items.add(new ItemInput(
                        requiredText(item, "resourceType"),
                        requiredText(item, "resourceId"),
                        item.path("name").asText(null),
                        text(item, "localDate"),
                        text(item, "startTime"),
                        text(item, "endTime"),
                        toObject(item.path("planParams"))
                )));
            }
            return new PlanValidationInput(
                    new ProfileInput(
                            profile.path("age").asInt(30),
                            profile.path("calorieLow").asInt(1500),
                            profile.path("calorieHigh").asInt(2500)),
                    new CatalogInput(
                            stringList(catalog.path("planReadyExerciseIds")),
                            stringList(catalog.path("knownExerciseIds")),
                            stringList(catalog.path("knownRoutineFactIds"))),
                    items
            );
        }

        private static Map<String, Object> toObject(JsonNode node) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (node.isObject()) {
                node.properties().forEach(entry -> result.put(entry.getKey(), unwrap(entry.getValue())));
            }
            return result;
        }

        /** 把 Jackson 标量节点解包为 Java 基本类型（IntNode 不是 Number，planParams 数值会被 PlanItemDraft 消费）。 */
        private static Object unwrap(JsonNode value) {
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isIntegralNumber()) {
                return value.asLong();
            }
            if (value.isFloatingPointNumber()) {
                return value.asDouble();
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual()) {
                return value.asText();
            }
            return value;
        }

        private static List<String> stringList(JsonNode node) {
            List<String> result = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual()) {
                        result.add(item.asText());
                    }
                });
            }
            return result;
        }
    }
}
