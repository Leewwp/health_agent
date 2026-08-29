package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 餐食计划偏好过滤（简报补充回路规格 v3.2）：
 * fixture M1-M9 确定性标签基准、同字段 OR / 跨字段 AND、整天回退边界例、
 * 回退日保留餐次/唯一性/多样性约束、纯热量回退不记偏好未满足。
 */
class MealPlanPreferenceFilterTest {

    private final HealthInputNormalizer normalizer = new HealthInputNormalizer();

    private MealPreferenceFilter filterOf(MealPlanBrief brief) {
        return MealPreferenceFilter.from(brief, normalizer);
    }

    @Test
    void fixture候选携带规格确定性标签基准() {
        List<PlanMealCandidate> candidates = new SeedResourceProvider().planMealCandidates();
        PlanMealCandidate m7 = candidates.stream().filter(c -> c.resourceId().equals("M7")).findFirst().orElseThrow();
        assertEquals(List.of("粤菜"), m7.cuisineTags());
        assertEquals(List.of("轻食"), m7.foodTypeTags());
        assertEquals(List.of("清淡"), m7.tasteTags());
        assertEquals(List.of("低油", "高蛋白", "减脂"), m7.nutritionPreferenceTags());
        assertEquals(List.of("快速"), m7.convenienceTags());

        PlanMealCandidate m1 = candidates.stream().filter(c -> c.resourceId().equals("M1")).findFirst().orElseThrow();
        assertEquals(List.of("维持健康"), m1.nutritionPreferenceTags(), "M1 健康目标标签基准");
        assertEquals(List.of("粤菜"), m1.cuisineTags());
        assertEquals(List.of("粥汤"), m1.foodTypeTags());

        PlanMealCandidate m6 = candidates.stream().filter(c -> c.resourceId().equals("M6")).findFirst().orElseThrow();
        assertEquals(List.of("番茄味"), m6.tasteTags());
        assertEquals(List.of("慢享"), m6.convenienceTags());
    }

    @Test
    void 口味与营养偏好逐值映射且跨字段AND() {
        // {清淡, 高蛋白}：清淡→tasteTags，高蛋白→nutritionPreferenceTags，两者必须同时命中
        MealPlanBrief brief = MealPlanBrief.empty().withOptional(null, List.of("清淡", "高蛋白"), null, null);
        MealPreferenceFilter filter = filterOf(brief);
        assertTrue(filter.matches(candidate("M7", List.of("清淡"), List.of("高蛋白"))),
                "M7 同时命中两个字段");
        assertTrue(filter.matches(candidate("M4", List.of("清淡"), List.of("高蛋白"))),
                "M4（高蛋白基准）同样同时命中两个字段");
        assertFalse(filter.matches(candidate("甜面包", List.of(), List.of("高蛋白"))),
                "只命中营养字段不满足清淡口味要求");
        assertFalse(filter.matches(candidate("白粥", List.of("清淡"), List.of())),
                "只命中口味字段不满足高蛋白营养要求");
    }

    @Test
    void 同字段多值OR() {
        MealPlanBrief brief = MealPlanBrief.empty().withOptional(null, List.of("清淡", "咸鲜"), null, null);
        MealPreferenceFilter filter = filterOf(brief);
        assertTrue(filter.matches(candidate("a", List.of("清淡"), List.of())));
        assertTrue(filter.matches(candidate("b", List.of("咸鲜"), List.of())));
        assertFalse(filter.matches(candidate("c", List.of("酸甜"), List.of())));
    }

    @Test
    void 未支持偏好不进入过滤条件() {
        // “中餐”是未支持菜系：既不过滤也不记录未满足
        MealPlanBrief brief = new MealPlanBrief(null, List.of(), null, "中餐", List.of(), null,
                List.of("cuisine:中餐"));
        MealPreferenceFilter filter = filterOf(brief);
        assertTrue(filter.isEmpty(), "未支持偏好单独存在时不参与过滤");
    }

    @Test
    void 偏好过滤命中候选ID且无回退() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.planMealCandidates()).thenReturn(List.of(
                candidateWithSlots("1", "清淡快速A", 400, List.of("早餐"), List.of("清淡"), List.of(), List.of("快速")),
                candidateWithSlots("2", "重口B", 500, List.of("早餐"), List.of("麻辣"), List.of(), List.of("快速")),
                candidateWithSlots("3", "清淡快速C", 600, List.of("午餐"), List.of("清淡"), List.of(), List.of("快速")),
                candidateWithSlots("4", "清淡快速D", 700, List.of("晚餐"), List.of("清淡"), List.of(), List.of("快速"))));
        MealPlanPicker picker = new MealPlanPicker(provider);
        MealPreferenceFilter filter = filterOf(MealPlanBrief.empty().withOptional(null, List.of("清淡"), "快速", null));

        MealPlanPicker.PreferencePickResult result =
                picker.pickForDayWithPreferences(1200, 1800, List.of("早餐", "午餐", "晚餐"), new HashMap<>(), filter);
        assertFalse(result.fallback());
        assertTrue(result.picks().stream().allMatch(pick -> List.of("1", "3", "4").contains(pick.resourceId())),
                "偏好过滤应只命中清淡+快速候选，实际 " + result.picks());
    }

    @Test
    void 某日早餐偏好池为空时整天回退且保留所选餐次() {
        // 偏好 {家常+快速}：早餐池（家常+快速）为空 → 该日整天回退，但仍生成早餐+午餐+晚餐
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.planMealCandidates()).thenReturn(List.of(
                candidateWithSlots("b1", "清淡早餐", 350, List.of("早餐"), List.of("清淡"), List.of(), List.of("快速")),
                candidateWithSlots("l1", "家常快速午餐", 700, List.of("午餐"), List.of("家常"), List.of(), List.of("快速")),
                candidateWithSlots("d1", "家常快速晚餐", 650, List.of("晚餐"), List.of("家常"), List.of(), List.of("快速"))));
        MealPlanPicker picker = new MealPlanPicker(provider);
        MealPreferenceFilter filter = filterOf(MealPlanBrief.empty()
                .withOptional(List.of(), List.of("家常"), null, "快速", null));

        MealPlanPicker.PreferencePickResult result =
                picker.pickForDayWithPreferences(1200, 1800, List.of("早餐", "午餐", "晚餐"), new HashMap<>(), filter);
        assertTrue(result.fallback(), "早餐偏好池为空必须整天回退");
        assertEquals(List.of("早餐", "午餐", "晚餐"), result.picks().stream().map(MealPlanPicker.MealPick::mealTime).toList(),
                "回退日仍保留所选餐次，不做单餐半回退");
        assertEquals(List.of("foodType:家常", "convenience:快速"), result.unmetPreferences(),
                "回退日按“字段:值”粒度记录未满足偏好");
        assertTrue(result.picks().stream().anyMatch(pick -> "b1".equals(pick.resourceId())),
                "回退绕过偏好但保留热量约束（清淡早餐回到池中）");
    }

    @Test
    void 回退日仍遵守跨日多样性与唯一性() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.planMealCandidates()).thenReturn(List.of(
                candidateWithSlots("b", "早餐", 400, List.of("早餐"), List.of("清淡"), List.of(), List.of("快速")),
                candidateWithSlots("l1", "午餐A", 800, List.of("午餐"), List.of("家常"), List.of(), List.of("快速")),
                candidateWithSlots("l2", "午餐B", 800, List.of("午餐"), List.of("家常"), List.of(), List.of("快速")),
                candidateWithSlots("d", "晚餐", 700, List.of("晚餐"), List.of("清淡"), List.of(), List.of("快速"))));
        MealPlanPicker picker = new MealPlanPicker(provider);
        MealPreferenceFilter filter = filterOf(MealPlanBrief.empty().withOptional("家常", null, null, null));

        // 第一天正常偏好过滤（午餐池命中），第二天回退：同日内不得重复同一候选
        Map<String, Integer> usage = new HashMap<>();
        MealPlanPicker.PreferencePickResult day1 =
                picker.pickForDayWithPreferences(1500, 2000, List.of("早餐", "午餐", "晚餐"), usage, filter);
        day1.picks().forEach(pick -> usage.merge(pick.resourceId(), 1, Integer::sum));
        MealPlanPicker.PreferencePickResult day2 =
                picker.pickForDayWithPreferences(1500, 2000, List.of("早餐", "午餐", "晚餐"), usage, filter);
        long distinct = day2.picks().stream().map(MealPlanPicker.MealPick::resourceId).distinct().count();
        assertEquals(day2.picks().size(), distinct, "回退日三餐不得重复");
    }

    @Test
    void 纯热量回退不记录偏好未满足() {
        // 偏好池非空、组合存在：热量就近的普通 fallbackPick 不触发偏好回退记录
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.planMealCandidates()).thenReturn(List.of(
                candidateWithSlots("1", "清淡A", 300, List.of("早餐"), List.of("清淡"), List.of(), List.of("快速")),
                candidateWithSlots("2", "清淡B", 500, List.of("午餐"), List.of("清淡"), List.of(), List.of("快速")),
                candidateWithSlots("3", "清淡C", 400, List.of("晚餐"), List.of("清淡"), List.of(), List.of("快速"))));
        MealPlanPicker picker = new MealPlanPicker(provider);
        MealPreferenceFilter filter = filterOf(MealPlanBrief.empty().withOptional(null, List.of("清淡"), "快速", null));
        MealPlanPicker.PreferencePickResult result =
                picker.pickForDayWithPreferences(1200, 1800, List.of("早餐", "午餐", "晚餐"), new HashMap<>(), filter);
        assertFalse(result.fallback(), "偏好池可组合时不得误记偏好回退");
        assertTrue(result.unmetPreferences().isEmpty());
    }

    @Test
    void 无偏好时挑选行为与现状一致() {
        MealPreferenceFilter empty = filterOf(MealPlanBrief.empty());
        assertTrue(empty.isEmpty());
        MealPlanPicker seedPicker = new MealPlanPicker(new SeedResourceProvider());
        MealPlanPicker.PreferencePickResult result =
                seedPicker.pickForDayWithPreferences(1200, 1800, List.of("早餐", "午餐", "晚餐"),
                        new HashMap<>(), empty);
        assertFalse(result.fallback());
        assertEquals(3, result.picks().size());
        assertEquals(List.of("早餐", "午餐", "晚餐"), result.picks().stream().map(MealPlanPicker.MealPick::mealTime).toList());
    }

    @Test
    void 组合器整天回退写入generationNotes且其余日期正常() {
        MealPlanPicker picker = new MealPlanPicker(new SeedResourceProvider());
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(new SeedResourceProvider(), picker, normalizer);
        // 偏好 {家常+清淡}：种子早餐池没有家常 → 每日整天回退，说明带日期与未满足键
        MealPlanBrief brief = MealPlanBrief.empty().withOptional(List.of(), List.of("家常"),
                List.of("清淡"), null, null);
        WeeklyPlanComposerService.MealCompositionResult result = composer.composeMealsWithPreferences(
                1500, 2200, java.time.LocalDate.of(2026, 8, 31), List.of("早餐", "午餐", "晚餐"), brief);
        GenerationNotes notes = result.generationNotes();
        assertEquals(List.of(), notes.unsupportedPreferences());
        assertFalse(notes.fallbacks().isEmpty(), "偏好池为空的日期应记录回退");
        assertTrue(notes.fallbacks().stream().allMatch(day -> day.unmetPreferences().contains("foodType:家常")),
                notes.fallbacks().toString());
        assertEquals(java.time.LocalDate.of(2026, 8, 31).toString(), notes.fallbacks().get(0).date());
        assertEquals(List.of("早餐", "午餐", "晚餐"), notes.fallbacks().get(0).mealTimes());
        // 仍然只生成所选餐次
        assertTrue(result.items().stream().allMatch(item ->
                        List.of("早餐", "午餐", "晚餐").contains(item.planParams().get("mealTime"))));
    }

    private PlanMealCandidate candidate(String id, List<String> taste, List<String> nutrition) {
        return new PlanMealCandidate("MEAL", id, id, List.of(), 500, 1,
                List.of(), taste, nutrition, List.of());
    }

    private PlanMealCandidate candidateWithSlots(String id, String name, int kcal, List<String> mealTimes,
                                                 List<String> taste, List<String> nutrition, List<String> convenience) {
        return new PlanMealCandidate("MEAL", id, name, mealTimes, kcal, 1,
                List.of(), taste, nutrition, convenience);
    }
}
