package com.diet.health.plan;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 餐食计划简报必须独立于训练字段；简报完整即可生成，不存在独立确认状态。 */
class MealPlanBriefServiceTest {

    private final MealPlanBriefService service = new MealPlanBriefService();

    @Test
    void 餐食简报解析目标周和餐次且完整即视为就绪() {
        MealPlanBriefService.UpdateResult collected = service.update(MealPlanBrief.empty(),
                "下周安排早餐、午餐和晚餐，想减脂");

        assertEquals(List.of("早餐", "午餐", "晚餐"), collected.brief().mealTimes());
        assertEquals(DayOfWeek.MONDAY, collected.brief().weekStart().getDayOfWeek());
        assertTrue(collected.brief().isComplete());
        assertTrue(collected.missingFields().isEmpty());
    }

    @Test
    void 不完整餐食简报给字段指引且普通训练输入不写入() {
        MealPlanBriefService.UpdateResult partial = service.update(MealPlanBrief.empty(), "下周餐食计划");
        assertFalse(partial.brief().isComplete());
        assertTrue(partial.missingFields().contains("mealTimes"));
        assertTrue(partial.missingFields().contains("healthGoal"));
        assertTrue(partial.guidance().contains("餐次"));

        MealPlanBriefService.UpdateResult unrelated = service.update(partial.brief(), "我想练胸和背");
        assertEquals(BriefInterpretationStatus.UNRELATED, unrelated.status());
        assertEquals(partial.brief(), unrelated.brief());
    }

    @Test
    void 只有目标周和餐次时必须继续追问餐食目标() {
        MealPlanBriefService.UpdateResult partial = service.update(MealPlanBrief.empty(),
                "下周安排早餐、午餐和晚餐");

        assertFalse(partial.brief().isComplete());
        assertEquals(List.of("healthGoal"), partial.missingFields());
        assertTrue(partial.guidance().contains("餐食目标"));
    }

    @Test
    void 原始失败句口味偏好进入简报且不覆盖热量目标() {
        // 原始失败对话第一句：口味偏好必须先于 isUnrelated/looksLikeMealInput 识别
        MealPlanBriefService.UpdateResult taste = service.update(MealPlanBrief.empty(), "我喜欢清淡的餐食");
        assertEquals(BriefInterpretationStatus.EXTRACTED, taste.status());
        assertEquals(List.of("清淡"), taste.brief().tastePreferences());

        // 热量目标永不被口味值覆盖：已有增肌目标时补充清淡不改写 healthGoal
        MealPlanBrief withGoal = new MealPlanBrief(null, List.of(), "增肌");
        MealPlanBriefService.UpdateResult light = service.update(withGoal, "我喜欢清淡的餐食");
        assertEquals("增肌", light.brief().healthGoal());
        assertEquals(List.of("清淡"), light.brief().tastePreferences());
    }

    @Test
    void 原始失败句中餐登记未支持偏好并保留原值() {
        MealPlanBriefService.UpdateResult unsupported = service.update(MealPlanBrief.empty(), "我喜欢中餐");
        assertEquals(BriefInterpretationStatus.EXTRACTED, unsupported.status());
        assertEquals("中餐", unsupported.brief().cuisine(), "形态内未支持原值写入菜系字段");
        assertEquals(List.of("cuisine:中餐"), unsupported.brief().unsupportedPreferences());
    }

    @Test
    void 中餐和川菜采用受支持的川菜并记录中餐() {
        MealPlanBriefService.UpdateResult mixed = service.update(MealPlanBrief.empty(), "我喜欢中餐、川菜");
        assertEquals("川菜", mixed.brief().cuisine(), "空字段恰有一个受支持值时采用它");
        assertEquals(List.of("cuisine:中餐"), mixed.brief().unsupportedPreferences(), "其余未支持值登记");
    }

    @Test
    void 原始失败句烹饪时间短归一为快速() {
        MealPlanBriefService.UpdateResult convenience = service.update(MealPlanBrief.empty(),
                "我喜欢中餐，由于在上班，每天做饭时间有限，希望烹饪时间短一些");
        assertEquals("快速", convenience.brief().convenience());
        assertEquals(List.of("cuisine:中餐"), convenience.brief().unsupportedPreferences());
        assertTrue(convenience.brief().cuisine().contains("中餐"));
    }

    @Test
    void 便利性别名覆盖四种原始表达() {
        for (String phrase : List.of("烹饪时间短", "做饭时间有限", "快手菜", "没时间做饭")) {
            MealPlanBriefService.UpdateResult result = service.update(MealPlanBrief.empty(), phrase);
            assertEquals("快速", result.brief().convenience(), phrase);
        }
    }

    @Test
    void 菜系已有值且无换成语义时保留并提示只能选一个() {
        MealPlanBrief existing = MealPlanBrief.empty().withOptional("川菜", null, null, null);
        MealPlanBriefService.UpdateResult overwrite = service.update(existing, "粤菜");
        assertEquals("川菜", overwrite.brief().cuisine(), "已有单选字段没有换成语义时不得静默覆盖");
        assertTrue(overwrite.guidance().contains("换成"), overwrite.guidance());

        MealPlanBriefService.UpdateResult replaced = service.update(existing, "换成粤菜");
        assertEquals("粤菜", replaced.brief().cuisine(), "“换成”语义允许覆盖单选字段");
    }

    @Test
    void 多个受支持菜系不猜测并要求重选() {
        MealPlanBriefService.UpdateResult conflict = service.update(MealPlanBrief.empty(), "菜系：粤菜、川菜");
        assertTrue(conflict.guidance().contains("一次只能选择一个菜系"), conflict.guidance());
        assertTrue(conflict.guidance().contains("粤菜"));
        assertTrue(conflict.guidance().contains("川菜"));
    }

    @Test
    void 口味偏好多值追加去重且换成语义清除重建() {
        MealPlanBrief base = MealPlanBrief.empty().withOptional(null, List.of("清淡"), null, null);
        MealPlanBriefService.UpdateResult appended = service.update(base, "高蛋白和低油");
        assertEquals(List.of("清淡", "高蛋白", "低油"), appended.brief().tastePreferences());

        MealPlanBriefService.UpdateResult rebuilt = service.update(appended.brief(), "口味换成微辣");
        assertEquals(List.of("微辣"), rebuilt.brief().tastePreferences());
    }

    @Test
    void 营养偏好低油高蛋白进入tastePreferences而非healthGoal() {
        MealPlanBriefService.UpdateResult result = service.update(MealPlanBrief.empty(), "希望低油、高蛋白");
        assertEquals(java.util.Set.of("低油", "高蛋白"), java.util.Set.copyOf(result.brief().tastePreferences()));
        assertTrue(result.brief().healthGoal() == null, "低油/高蛋白是营养偏好，不得覆盖热量目标字段");
    }

    @Test
    void 未支持偏好不因其他字段更新丢失() {
        MealPlanBrief base = service.update(MealPlanBrief.empty(), "我喜欢中餐").brief();
        MealPlanBriefService.UpdateResult appended = service.update(base, "下周安排早餐");
        assertTrue(appended.brief().unsupportedPreferences().contains("cuisine:中餐"),
                "JSON/后续更新往返后未支持偏好不得丢失");
        assertEquals("中餐", appended.brief().cuisine());
    }

    @Test
    void 范围外输入给可补充项枚举指引且简报不变() {
        MealPlanBrief base = service.update(MealPlanBrief.empty(), "下周安排早餐").brief();
        MealPlanBriefService.UpdateResult invalid = service.update(base, "餐食随便安排一下");
        assertEquals(BriefInterpretationStatus.INVALID, invalid.status());
        assertEquals(base, invalid.brief(), "INVALID 时简报不变");
        assertTrue(invalid.guidance().contains("还可以补充"), invalid.guidance());
        assertTrue(invalid.guidance().contains("菜系"), invalid.guidance());
    }

    @Test
    void 简报摘要包含可选偏好与未支持项() {
        MealPlanBrief brief = service.update(MealPlanBrief.empty(),
                "我喜欢中餐，希望烹饪时间短一些").brief();
        String summary = service.summary(brief);
        assertTrue(summary.contains("菜系：中餐"), summary);
        assertTrue(summary.contains("烹饪时长：快速"), summary);
        assertTrue(summary.contains("暂不支持：cuisine:中餐"), summary);
    }

    @Test
    void 可补充项只列未填项() {
        MealPlanBrief empty = MealPlanBrief.empty();
        List<com.diet.health.model.SupplementableItem> items = service.supplementable(empty);
        assertEquals(3, items.size());
        assertTrue(items.stream().allMatch(item -> !item.filled()));
        assertTrue(items.stream().anyMatch(item -> "cuisine".equals(item.key())));
        assertTrue(items.stream().anyMatch(item -> "tastePreferences".equals(item.key())));
        assertTrue(items.stream().anyMatch(item -> "convenience".equals(item.key())));

        MealPlanBrief filled = empty.withOptional("川菜", List.of("清淡"), "快速", null);
        List<com.diet.health.model.SupplementableItem> remaining = service.supplementable(filled);
        assertTrue(remaining.isEmpty(), "已填项不重复出现");
    }
}
