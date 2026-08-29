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
        assertEquals(List.of("川菜", "中餐"), mixed.brief().cuisines(), "受支持值先于未支持值保留");
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
    void 菜系已有值且无换成语义时追加并支持显式替换() {
        MealPlanBrief existing = MealPlanBrief.empty().withOptional("川菜", null, null, null);
        MealPlanBriefService.UpdateResult overwrite = service.update(existing, "粤菜");
        assertEquals(List.of("川菜", "粤菜"), overwrite.brief().cuisines(), "多选菜系追加新值");

        MealPlanBriefService.UpdateResult replaced = service.update(existing, "换成粤菜");
        assertEquals(List.of("粤菜"), replaced.brief().cuisines(), "“换成”语义允许覆盖菜系集合");
    }

    @Test
    void 多个受支持菜系不猜测并要求重选() {
        MealPlanBriefService.UpdateResult conflict = service.update(MealPlanBrief.empty(), "菜系：粤菜、川菜");
        // 受支持值输出顺序跟随用户表达顺序（加固规格：不随词表/别名表顺序漂移）
        assertEquals(List.of("粤菜", "川菜"), conflict.brief().cuisines());
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
        assertEquals(4, items.size());
        assertTrue(items.stream().allMatch(item -> !item.filled()));
        assertTrue(items.stream().anyMatch(item -> "cuisines".equals(item.key())));
        assertTrue(items.stream().anyMatch(item -> "foodTypes".equals(item.key())));
        assertTrue(items.stream().anyMatch(item -> "tastePreferences".equals(item.key())));
        assertTrue(items.stream().anyMatch(item -> "convenience".equals(item.key())));

        MealPlanBrief filled = empty.withOptional(List.of("川菜"), List.of("素食"),
                List.of("清淡"), "快速", null);
        List<com.diet.health.model.SupplementableItem> remaining = service.supplementable(filled);
        assertTrue(remaining.isEmpty(), "已填项不重复出现");
    }

    // ---- 餐食类型未支持通道矩阵（餐食标签加固规格 / ADR-0017）----

    @Test
    void 显式类型形态登记词表外原值且不参与筛选语义() {
        MealPlanBriefService.UpdateResult result = service.update(MealPlanBrief.empty(),
                "下周三餐，减脂，餐食类型：生酮");

        assertTrue(result.brief().foodTypes().contains("生酮"), "词表外类型保留在 foodTypes");
        assertTrue(result.brief().unsupportedPreferences().contains("foodType:生酮"),
                "未支持类型按 foodType:<value> 稳定键登记");
    }

    @Test
    void 想吃混合表达只把受支持类型写入支持列表并登记未支持类型() {
        MealPlanBriefService.UpdateResult result = service.update(MealPlanBrief.empty(),
                "下周想吃素和生酮");

        assertTrue(result.brief().foodTypes().contains("素食"));
        assertTrue(result.brief().foodTypes().contains("生酮"));
        assertTrue(result.brief().unsupportedPreferences().contains("foodType:生酮"));
        assertFalse(result.brief().unsupportedPreferences().contains("foodType:素食"));
    }

    @Test
    void 否定类型不写入正向列表() {
        MealPlanBriefService.UpdateResult result = service.update(MealPlanBrief.empty(),
                "下周三餐减脂，不想吃生酮");

        assertTrue(result.brief().foodTypes().isEmpty());
        assertTrue(result.brief().unsupportedPreferences().isEmpty());
    }

    @Test
    void 类型替换重建集合且保留其他字段() {
        MealPlanBrief base = service.update(MealPlanBrief.empty(),
                "下周三餐，减脂，想吃轻食").brief();
        assertTrue(base.foodTypes().contains("轻食"));

        MealPlanBriefService.UpdateResult replaced = service.update(base, "餐食类型换成生酮");
        assertTrue(replaced.brief().foodTypes().contains("生酮"));
        assertFalse(replaced.brief().foodTypes().contains("轻食"), "替换语义清除旧类型");
        assertEquals(base.weekStart(), replaced.brief().weekStart(), "替换类型保留目标周");
        assertEquals(base.mealTimes(), replaced.brief().mealTimes(), "替换类型保留餐次");
        assertEquals(base.healthGoal(), replaced.brief().healthGoal(), "替换类型保留目标");
    }

    @Test
    void 未支持类型仍提示可补充餐食类型() {
        MealPlanBrief brief = service.update(MealPlanBrief.empty(), "餐食类型：生酮").brief();
        List<com.diet.health.model.SupplementableItem> items = service.supplementable(brief);
        assertTrue(items.stream().anyMatch(item -> "foodTypes".equals(item.key())),
                "仅含未支持类型时仍应提供受支持类型的可补充项");
    }

    @Test
    void 类型摘要与重启恢复保持数组和未支持集合完整() {
        MealPlanBrief brief = service.update(MealPlanBrief.empty(), "下周想吃素和生酮").brief();
        String summary = service.summary(brief);
        assertTrue(summary.contains("餐食类型：素食、生酮"), summary);
        assertTrue(summary.contains("暂不支持：foodType:生酮"), summary);

        MealPlanBrief restored = new MealPlanBrief(brief.weekStart(), brief.mealTimes(), brief.healthGoal(),
                brief.cuisines(), brief.foodTypes(), brief.tastePreferences(), brief.convenience(),
                brief.unsupportedPreferences());
        assertEquals(brief.foodTypes(), restored.foodTypes());
        assertEquals(brief.unsupportedPreferences(), restored.unsupportedPreferences());
    }
}
