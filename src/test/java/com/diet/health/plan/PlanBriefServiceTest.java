package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 训练简报的确定性合并、日期/星期和确认失效规则。 */
class PlanBriefServiceTest {

    private final PlanBriefService service = new PlanBriefService(new HealthInputNormalizer());

    @Test
    void 多轮补充合并为独立简报并正确映射周和时间窗口() {
        PlanBriefService.UpdateResult first = service.update(PlanBrief.empty(),
                "我想减脂，重点练胸和核心，徒手，入门，目标周 2026-08-24，周一周三周五，19:00-20:00");
        assertTrue(first.brief().isComplete());
        assertEquals(LocalDate.of(2026, 8, 24), first.brief().weekStart());
        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), first.brief().trainingDays());
        assertEquals(new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)), first.brief().timeWindow());
        assertFalse(first.brief().confirmed());

        PlanBriefService.UpdateResult confirmed = service.update(first.brief(), "确认训练偏好");
        assertTrue(confirmed.confirmedNow());
        assertTrue(confirmed.brief().isConfirmedAndComplete());
        assertEquals(1, confirmed.brief().confirmationVersion());

        PlanBriefService.UpdateResult corrected = service.update(confirmed.brief(), "改成 20:00-21:00");
        assertFalse(corrected.brief().confirmed());
        assertEquals(LocalTime.of(20, 0), corrected.brief().timeWindow().start());
        assertEquals(LocalTime.of(21, 0), corrected.brief().timeWindow().end());
    }

    @Test
    void 缺失字段返回确定性追问且模糊日期不会交给模型() {
        PlanBriefService.UpdateResult result = service.update(PlanBrief.empty(), "我想增肌，练背，哑铃，进阶");
        assertFalse(result.brief().isComplete());
        assertTrue(result.missingFields().contains("weekStart"));
        assertTrue(result.missingFields().contains("trainingDays"));
        assertTrue(result.missingFields().contains("timeWindow"));
        assertTrue(service.question(result.missingFields()).contains("目标周"));
        assertTrue(service.update(result.brief(), "下周周一").brief().weekStart() != null);
    }

    @Test
    void 空简报可以安全生成摘要() {
        assertNotNull(service.summary(PlanBrief.empty()));
    }

    @Test
    void 明确排除的动作进入可执行硬约束() {
        PlanBriefService.UpdateResult result = service.update(PlanBrief.empty(), "不要做俯卧撑");

        assertEquals(List.of("俯卧撑"), result.brief().hardConstraints().get("excludeExercises"));
    }

    @Test
    void 多轮硬约束按类型合并而不覆盖() {
        PlanBrief first = service.update(PlanBrief.empty(), "不要用哑铃").brief();
        PlanBrief second = service.update(first, "不要做俯卧撑").brief();

        assertEquals(List.of("哑铃"), second.hardConstraints().get("excludeEquipment"));
        assertEquals(List.of("俯卧撑"), second.hardConstraints().get("excludeExercises"));
    }

    @Test
    void 无法确定性执行的自由文本硬约束明确拒绝() {
        com.diet.exception.HealthApiException error = assertThrows(com.diet.exception.HealthApiException.class,
                () -> service.update(PlanBrief.empty(), "训练时不要让我太累"));

        assertEquals(com.diet.exception.HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("暂不支持"));
    }
}
