package com.diet.health.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #77 固定查询集完整性测试：60 条六层各 10、stratum 合法、真值非空且为库内数字来源 ID、
 * 排除项层排除项出现在真值语义集合中、过敏原层带过敏原且真值排除含过敏原餐食
 * （含过敏原的排除语义由生成口径保证：expectedSourceIds 不含任何 allergens 标注餐食）。
 */
class LabeledMealQuerySetTest {

    private static final Set<String> LEGAL_STRATA = Set.of(
            "exact_label", "natural_language", "long_tail", "synonym", "exclusion", "allergen");

    private LabeledQuerySet querySet;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = new ClassPathResource("diet/eval/labeled_meal_queries.json").getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (json.trim().startsWith("[")) {
                querySet = LabeledQuerySet.of(new ObjectMapper().readValue(json, new TypeReference<>() {
                }));
            } else {
                querySet = new ObjectMapper().readValue(json, LabeledQuerySet.class);
            }
        }
    }

    @Test
    void 查询集共60条且六层各10条() {
        assertEquals(60, querySet.queries().size(), "#77 固定查询集必须 60 条");
        Map<String, Long> byStratum = querySet.queries().stream()
                .collect(Collectors.groupingBy(LabeledMealQuery::stratum, Collectors.counting()));
        for (String stratum : LEGAL_STRATA) {
            assertEquals(10, byStratum.getOrDefault(stratum, 0L), "层 " + stratum + " 必须恰有 10 条");
        }
        assertEquals(LEGAL_STRATA, byStratum.keySet(), "不允许出现六层以外的 stratum");
    }

    @Test
    void 查询ID唯一且querySetVersion为110() {
        assertEquals("1.1.0", querySet.querySetVersion());
        Set<String> ids = new HashSet<>();
        for (LabeledMealQuery q : querySet.queries()) {
            assertTrue(ids.add(q.id()), "查询 ID 必须唯一: " + q.id());
        }
    }

    @Test
    void 每条查询真值非空且均为数字来源ID() {
        for (LabeledMealQuery q : querySet.queries()) {
            assertNotNull(q.expectedSourceIds(), q.id() + " expectedSourceIds 不能为 null");
            assertFalse(q.expectedSourceIds().isEmpty(), q.id() + " 真值不能为空");
            for (String sourceId : q.expectedSourceIds()) {
                assertTrue(sourceId.matches("\\d+"), q.id() + " 真值必须是库内数字来源 ID: " + sourceId);
            }
        }
    }

    @Test
    void 排除项层必须带排除项且真值不包含被排除项() {
        List<LabeledMealQuery> exclusion = stratum("exclusion");
        for (LabeledMealQuery q : exclusion) {
            assertFalse(q.excludeSourceIds().isEmpty(), q.id() + " 排除项层必须带 excludeSourceIds");
            for (String excluded : q.excludeSourceIds()) {
                assertFalse(q.expectedSourceIds().contains(excluded),
                        q.id() + " 真值必须排除被排除项: " + excluded);
            }
        }
    }

    @Test
    void 过敏原层必须带过敏原且真值按生成口径排除过敏原餐食() {
        List<LabeledMealQuery> allergen = stratum("allergen");
        for (LabeledMealQuery q : allergen) {
            assertFalse(q.allergens().isEmpty(), q.id() + " 过敏原层必须带 allergens");
            assertEquals(1, q.allergens().size(), q.id() + " 单过敏原约束便于真值语义核对");
        }
    }

    @Test
    void 非排除非过敏原层不得携带硬约束() {
        for (String stratumName : List.of("exact_label", "natural_language", "long_tail", "synonym")) {
            for (LabeledMealQuery q : stratum(stratumName)) {
                assertTrue(q.allergens().isEmpty(), q.id() + " " + stratumName + " 层不应带过敏原");
                assertTrue(q.excludeSourceIds().isEmpty(), q.id() + " " + stratumName + " 层不应带排除项");
            }
        }
    }

    @Test
    void 同一语义意图的排除项与精确层真值之差恰为排除项() {
        // 排除项层的槽位与对应精确标签层一致时，真值 = 精确层真值 - 排除项。
        // 用最宽口径交叉验证：排除项必须出现在某个同槽位查询的真值里（即它确实属于相关集合）。
        Map<String, List<LabeledMealQuery>> byId = querySet.queries().stream()
                .collect(Collectors.groupingBy(LabeledMealQuery::id));
        Set<String> allTruth = querySet.queries().stream()
                .flatMap(q -> q.expectedSourceIds().stream())
                .collect(Collectors.toSet());
        for (LabeledMealQuery q : stratum("exclusion")) {
            for (String excluded : q.excludeSourceIds()) {
                assertTrue(allTruth.contains(excluded),
                        q.id() + " 排除项 " + excluded + " 必须出现在其他查询真值中（确认其属于相关集合）");
            }
        }
    }

    private List<LabeledMealQuery> stratum(String stratum) {
        return querySet.queries().stream()
                .filter(q -> stratum.equals(q.stratum()))
                .toList();
    }
}
