package com.diet.health.plan;

import com.diet.health.enums.HealthRiskLevel;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合时 Guard（34 号，PlanValidationService）：
 * 未满 18 岁、65 岁以上训练、作息/训练时间冲突、训练部位连续两天、
 * 资源资格/引用、日能量区间。多规则取最高等级，BLOCK_PLAN=HARD_ERROR、ADVISORY=WARNING。
 */
class PlanValidationServiceTest {

    private final PlanValidationService validation = new PlanValidationService();

    private static final LocalDate MON = LocalDate.of(2026, 8, 17);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate SUN = MON.plusDays(6);

    private static final HealthResourceProvider CATALOG_PROVIDER = new SeedResourceProvider();

    /** 目录：从 fixture Provider 派生（种子动作 9001-9008 全部 plan_ready；作息事实 R1-R5）。 */
    private static final PlanValidationService.ResourceCatalog CATALOG = new PlanValidationService.ResourceCatalog(
            Set.copyOf(CATALOG_PROVIDER.planReadyExerciseIds()),
            CATALOG_PROVIDER.exercises().stream().map(com.diet.health.module.HealthResource::resourceId).collect(Collectors.toSet()),
            Set.copyOf(CATALOG_PROVIDER.allFactIds())
    );

    private static PlanItemDraft sleep(LocalDate date) {
        return new PlanItemDraft("ROUTINE", "R1", "睡眠", date, LocalTime.of(23, 0), LocalTime.of(7, 0), null, Map.of());
    }

    /** 自定义时间的作息项目（跨午夜/同日用例用）。 */
    private static PlanItemDraft routine(String factId, String name, LocalDate date, LocalTime start, LocalTime end) {
        return new PlanItemDraft("ROUTINE", factId, name, date, start, end, null, Map.of());
    }

    private static PlanItemDraft meal(LocalDate date, String name, int kcal) {
        return new PlanItemDraft("MEAL", "m-" + name, name, date, LocalTime.of(12, 0), LocalTime.of(13, 0), null,
                Map.of("caloriesKcal", kcal));
    }

    private static PlanItemDraft exercise(LocalDate date, String id, String bodyPart) {
        return exercise(date, id, bodyPart, LocalTime.of(19, 0), LocalTime.of(20, 30));
    }

    /** 自定义时间的训练项目。 */
    private static PlanItemDraft exercise(LocalDate date, String id, String bodyPart, LocalTime start, LocalTime end) {
        return new PlanItemDraft("EXERCISE", id, "动作" + id, date, start, end, null, Map.of("bodyPart", bodyPart));
    }

    private static PlanValidationService.ProfileContext profile(int age, int low, int high) {
        return new PlanValidationService.ProfileContext(age, low, high);
    }

    @Test
    void 正常计划OK无命中() {
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                meal(MON, "早餐", 400), meal(MON, "午餐", 600), meal(MON, "晚餐", 500),
                exercise(MON, "9001", "胸"), exercise(TUE.plusDays(1), "9002", "腿")
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
        assertTrue(result.hits().isEmpty());
        assertTrue(result.activatable());
    }

    @Test
    void 跨午夜睡眠与次日早晨训练冲突为HARD_ERROR() {
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                exercise(TUE, "9001", "胸", LocalTime.of(6, 30), LocalTime.of(7, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.blocked());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
        assertEquals(PlanValidationService.SCHEDULE_OVERLAP_COPY, result.copy());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=" + TUE)),
                "冲突日期必须记录真实次日瞬间，实际 " + result.hits());
    }

    @Test
    void 跨午夜睡眠与当日早晨训练不冲突() {
        // 旧实现把 [00:00,07:00) 段留在周一桶导致假阳性；结束段必须归属周二
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                exercise(MON, "9001", "胸", LocalTime.of(6, 30), LocalTime.of(7, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
        assertTrue(result.activatable());
        assertTrue(result.hits().isEmpty(), "当日早晨训练不得与前一晚跨午夜睡眠冲突，实际 " + result.hits());
    }

    @Test
    void 跨午夜睡眠与当日深夜训练冲突() {
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                exercise(MON, "9001", "胸", LocalTime.of(23, 30), LocalTime.of(23, 45))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=" + MON)),
                "深夜段冲突日期为开始日");
    }

    @Test
    void 跨午夜睡眠与次日边界相接训练不冲突() {
        // 半开区间 [00:00,07:00) 与 [07:00,08:00) 边界相接不算重叠
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                exercise(TUE, "9001", "胸", LocalTime.of(7, 0), LocalTime.of(8, 0))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
        assertTrue(result.hits().isEmpty(), "边界相接不得判为冲突，实际 " + result.hits());
    }

    @Test
    void 跨午夜睡眠与次日零点边界训练冲突() {
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                exercise(TUE, "9001", "胸", LocalTime.of(0, 0), LocalTime.of(0, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
    }

    @Test
    void 两个跨午夜作息在次日重叠为冲突() {
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                routine("R2", "夜间工作", MON, LocalTime.of(23, 30), LocalTime.of(6, 0))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=" + TUE)),
                "两个跨午夜区间在次日的结束段必须相互冲突");
    }

    @Test
    void 普通同日重叠保持冲突() {
        List<PlanItemDraft> items = List.of(
                routine("R2", "晨间作息", MON, LocalTime.of(6, 0), LocalTime.of(7, 0)),
                exercise(MON, "9001", "胸", LocalTime.of(6, 30), LocalTime.of(7, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
    }

    @Test
    void 普通同日不重叠不冲突() {
        List<PlanItemDraft> items = List.of(
                routine("R2", "晨间作息", MON, LocalTime.of(6, 0), LocalTime.of(7, 0)),
                exercise(MON, "9001", "胸", LocalTime.of(8, 0), LocalTime.of(9, 0))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
        assertTrue(result.hits().isEmpty(), "同日不重叠不得误判，实际 " + result.hits());
    }

    @Test
    void 周日跨周一与另一跨周项目冲突且detail显示真实日期() {
        List<PlanItemDraft> items = List.of(
                sleep(SUN),
                routine("R2", "夜间工作", SUN, LocalTime.of(23, 30), LocalTime.of(0, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=" + SUN)),
                "周日深夜段冲突记录周日日期");
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=" + SUN.plusDays(1))),
                "次日结束段冲突 detail 必须显示次周一日期，实际 " + result.hits());
    }

    @Test
    void 周日跨周一且无重叠项目合法() {
        List<PlanItemDraft> items = List.of(
                sleep(SUN),
                routine("R2", "晚间阅读", SUN, LocalTime.of(22, 0), LocalTime.of(22, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
        assertTrue(result.hits().isEmpty(), "周日跨周区间不与本周其他项目重叠应合法，实际 " + result.hits());
    }

    @Test
    void 零时长区间为HARD_ERROR() {
        List<PlanItemDraft> items = List.of(
                routine("R1", "睡眠", MON, LocalTime.of(23, 0), LocalTime.of(23, 0))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.blocked());
        assertTrue(result.hits().stream().anyMatch(hit -> "INVALID_TIME_RANGE".equals(hit.ruleCode())));
        assertEquals(PlanValidationService.INVALID_TIME_RANGE_COPY, result.copy());
    }

    @Test
    void 缺失单侧时间不构造半截区间() {
        // 只有开始时间或只有结束时间：按可选字段契约跳过，不得与跨午夜睡眠冲突
        List<PlanItemDraft> items = List.of(
                sleep(MON),
                new PlanItemDraft("EXERCISE", "9001", "早练", TUE, LocalTime.of(6, 30), null, null,
                        Map.of("bodyPart", "胸")),
                new PlanItemDraft("EXERCISE", "9002", "夜练", MON, null, LocalTime.of(23, 30), null,
                        Map.of("bodyPart", "腿"))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
        assertTrue(result.hits().isEmpty(), "缺失单侧时间不得参与冲突校验，实际 " + result.hits());
    }

    @Test
    void 月末跨月睡眠与次月早晨训练冲突() {
        LocalDate aug31 = LocalDate.of(2026, 8, 31);
        List<PlanItemDraft> items = List.of(
                sleep(aug31),
                exercise(aug31.plusDays(1), "9001", "胸", LocalTime.of(6, 30), LocalTime.of(7, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=2026-09-01")),
                "月末跨月结束段必须进位到 9 月 1 日");
    }

    @Test
    void 年末跨年睡眠与新年早晨训练冲突() {
        LocalDate dec31 = LocalDate.of(2026, 12, 31);
        List<PlanItemDraft> items = List.of(
                sleep(dec31),
                exercise(dec31.plusDays(1), "9001", "胸", LocalTime.of(6, 30), LocalTime.of(7, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=2027-01-01")),
                "年末跨年结束段必须进位到 2027 年 1 月 1 日");
    }

    @Test
    void 闰日跨月睡眠与次月早晨训练冲突() {
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        List<PlanItemDraft> items = List.of(
                sleep(leapDay),
                exercise(leapDay.plusDays(1), "9001", "胸", LocalTime.of(6, 30), LocalTime.of(7, 30))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SCHEDULE_OVERLAP".equals(hit.ruleCode())));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.detail().contains("date=2028-03-01")),
                "闰日结束段必须进位到 2028 年 3 月 1 日");
    }

    @Test
    void 同一训练部位连续两天为HARD_ERROR() {
        List<PlanItemDraft> items = List.of(
                sleep(MON), sleep(TUE),
                exercise(MON, "9001", "胸"), exercise(TUE, "9006", "胸")
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "BODY_PART_CONSECUTIVE".equals(hit.ruleCode())));
        assertEquals(PlanValidationService.BODY_PART_CONSECUTIVE_COPY, result.copy());
    }

    @Test
    void 同部位间隔两天不算连续() {
        List<PlanItemDraft> items = List.of(
                exercise(MON, "9001", "胸"), exercise(MON.plusDays(2), "9006", "胸")
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
    }

    @Test
    void 日能量超出区间为WARNING() {
        List<PlanItemDraft> items = List.of(
                meal(MON, "早餐", 1000), meal(MON, "午餐", 1000), meal(MON, "晚餐", 1000)
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.WARNING, result.level());
        assertFalse(result.activatable());
        assertTrue(result.hits().stream().anyMatch(hit -> "ENERGY_OUT_OF_RANGE".equals(hit.ruleCode())));
        assertEquals(PlanValidationService.ENERGY_OUT_OF_RANGE_COPY, result.copy());
    }

    @Test
    void 餐食时间超出餐次窗口为WARNING() {
        List<PlanItemDraft> items = List.of(
                new PlanItemDraft("MEAL", "m-1", "深夜早餐", MON, LocalTime.of(23, 30), LocalTime.of(23, 59), null,
                        Map.of("mealTime", "早餐", "caloriesKcal", 500))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 400, 800), items, CATALOG);
        assertEquals(PlanValidationLevel.WARNING, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "MEAL_TIME_OUT_OF_WINDOW".equals(hit.ruleCode())));
        assertEquals(PlanValidationService.MEAL_TIME_WINDOW_COPY, result.copy());
    }

    @Test
    void 餐食落在餐次窗口内不触发() {
        List<PlanItemDraft> items = List.of(
                new PlanItemDraft("MEAL", "m-1", "正常早餐", MON, LocalTime.of(8, 0), LocalTime.of(8, 30), null,
                        Map.of("mealTime", "早餐", "caloriesKcal", 500))
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 400, 800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
    }

    @Test
    void 非planReady动作进入计划为HARD_ERROR() {
        PlanValidationService.ResourceCatalog catalog = new PlanValidationService.ResourceCatalog(
                Set.of("9001"), Set.of("9001", "9009"), Set.of("R1"));
        List<PlanItemDraft> items = List.of(exercise(MON, "9009", "胸"));
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, catalog);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "RESOURCE_NOT_PLAN_READY".equals(hit.ruleCode())));
    }

    @Test
    void 引用不存在的动作为HARD_ERROR() {
        List<PlanItemDraft> items = List.of(exercise(MON, "9999", "胸"));
        PlanValidationService.ValidationResult result = validation.validate(profile(30, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "RESOURCE_NOT_FOUND".equals(hit.ruleCode())));
    }

    @Test
    void 未满18岁拒绝计划() {
        List<PlanItemDraft> items = List.of(sleep(MON), meal(MON, "午餐", 600));
        PlanValidationService.ValidationResult result = validation.validate(profile(15, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "UNDERAGE".equals(hit.ruleCode())));
        assertEquals(PlanValidationService.UNDERAGE_COPY, result.copy());
    }

    @Test
    void 高龄65以上含训练项目拒绝() {
        List<PlanItemDraft> items = List.of(exercise(MON, "9001", "胸"));
        PlanValidationService.ValidationResult result = validation.validate(profile(70, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        assertTrue(result.hits().stream().anyMatch(hit -> "SENIOR_TRAINING".equals(hit.ruleCode())));
    }

    @Test
    void 高龄65以上不含训练项目不拒绝() {
        List<PlanItemDraft> items = List.of(sleep(MON), meal(MON, "午餐", 600));
        PlanValidationService.ValidationResult result = validation.validate(profile(70, 400, 800), items, CATALOG);
        assertEquals(PlanValidationLevel.OK, result.level());
    }

    @Test
    void 多规则命中取最高等级并保留全部命中() {
        List<PlanItemDraft> items = List.of(
                exercise(MON, "9001", "胸"), exercise(TUE, "9006", "胸"),
                meal(MON, "早餐", 1000), meal(MON, "午餐", 1000), meal(MON, "晚餐", 1000)
        );
        PlanValidationService.ValidationResult result = validation.validate(profile(70, 1400, 1800), items, CATALOG);
        assertEquals(PlanValidationLevel.HARD_ERROR, result.level());
        List<String> codes = result.hits().stream().map(PlanValidationService.RuleHit::ruleCode).toList();
        assertTrue(codes.contains("SENIOR_TRAINING"));
        assertTrue(codes.contains("BODY_PART_CONSECUTIVE"));
        assertTrue(codes.contains("ENERGY_OUT_OF_RANGE"));
    }
}
