package com.diet.health.rag;

import com.diet.enums.SourceMode;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构化检索器测试（33 号票 RAG seam）。
 * 验证：硬约束（排除 ID、过敏原）先于打分过滤；结构化排序；
 * 结果模式标记为 STRUCTURED。
 */
class StructuredMealRetrieverTest {

    private MealMapper mealMapper;
    private StructuredMealRetriever retriever;

    @BeforeEach
    void setUp() {
        mealMapper = mock(MealMapper.class);
        retriever = new StructuredMealRetriever(mealMapper, new JsonService(new ObjectMapper()), new MealRankService());
    }

    @Test
    void 排除ID在检索后过滤() {
        when(mealMapper.search(any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(row(1L, "A", "[]"), row(2L, "B", "[]")));
        RetrievalResult result = retriever.retrieve(query(List.of(2L), List.of()), 10);
        assertEquals(1, result.items().size());
        assertEquals(1L, result.items().get(0).meal().id());
    }

    @Test
    void 过敏原硬约束先过滤再排序() {
        when(mealMapper.search(any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        row(1L, "花生鸡丁", "[\"花生\"]"),
                        row(2L, "清蒸鱼", "[\"鱼\"]"),
                        row(3L, "番茄蛋汤", "[]")));
        RetrievalResult result = retriever.retrieve(query(List.of(), List.of("花生")), 10);
        assertEquals(List.of(2L, 3L), result.items().stream().map(r -> r.meal().id()).toList(),
                "花生过敏的餐食必须被过滤");
    }

    @Test
    void 结构化标签命中越多排越前() {
        when(mealMapper.search(any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        row(1L, "普通米饭", "[]", "[\"晚餐\"]", "[\"清淡\"]", "[]", "[]"),
                        row(2L, "高蛋白鸡胸", "[]", "[\"晚餐\"]", "[\"清淡\"]", "[]", "[\"鲜\"]")));
        RetrievalResult result = retriever.retrieve(
                new MealRetrievalQuery(Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡"), "taste", List.of("鲜")),
                        List.of(), List.of(), "鲜味晚餐"),
                10);
        assertEquals(2L, result.items().get(0).meal().id(), "标签命中多的必须排前面");
    }

    @Test
    void 空候选返回空结果() {
        when(mealMapper.search(any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        RetrievalResult result = retriever.retrieve(query(List.of(), List.of()), 10);
        assertTrue(result.items().isEmpty());
        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertNull(result.degradationReason());
    }

    @Test
    void 结果模式为STRUCTURED且语义分为空() {
        when(mealMapper.search(any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(row(1L, "A", "[]")));
        RetrievalResult result = retriever.retrieve(query(List.of(), List.of()), 10);
        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertNull(result.items().get(0).semanticScore());
        assertTrue(result.items().get(0).structuredScore() >= 0);
    }

    @Test
    void 未审核餐食不进入检索候选() {
        List<MealItemRow> rows = List.of(row(1L, "已审核", "[]"), row(2L, "未审核", "[]"));
        rows.get(0).setReviewStatus("APPROVED");
        rows.get(1).setReviewStatus("PENDING");
        when(mealMapper.search(any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn(rows);

        RetrievalResult result = retriever.retrieve(query(List.of(), List.of()), 10);

        assertEquals(List.of(1L), result.items().stream().map(r -> r.meal().id()).toList(),
                "PENDING 餐食必须被排除在审核检索链路之外");
    }

    private MealRetrievalQuery query(List<Long> excludeIds, List<String> allergens) {
        return new MealRetrievalQuery(
                Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡")),
                excludeIds, allergens, "高蛋白晚餐");
    }

    private MealItemRow row(Long id, String name, String allergens) {
        return row(id, name, allergens, "[\"晚餐\"]", "[\"清淡\"]", "[]", "[]");
    }

    private MealItemRow row(Long id, String name, String allergens, String mealTime, String healthGoal,
                            String convenience, String taste) {
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setSourceType(SourceMode.PUBLIC.name());
        row.setName(name);
        row.setMealTime(mealTime);
        row.setMood("[]");
        row.setScene("[]");
        row.setHealthGoal(healthGoal);
        row.setCuisine("[]");
        row.setTaste(taste);
        row.setConvenience(convenience);
        row.setAllergenJson(allergens);
        row.setReviewStatus("APPROVED");
        return row;
    }
}
