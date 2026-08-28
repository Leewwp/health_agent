package com.diet.health.plan;

import com.diet.health.enums.PlanScope;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 当前启用计划只作为聊天软上下文，但三种计划范围都必须可读取。 */
class EnabledPlanContextServiceTest {

    private final WeeklyPlanMapper mapper = mock(WeeklyPlanMapper.class);
    private final EnabledPlanContextService service = new EnabledPlanContextService(mapper, new PlanScopeGuard());

    @Test
    void 独立餐食计划可提供当天餐食软上下文() {
        WeeklyPlanRow plan = plan("MEAL");
        WeeklyPlanItemRow item = item("MEAL", "高蛋白早餐");
        when(mapper.findActiveByUser(1L)).thenReturn(plan);
        when(mapper.findItems(plan.getId(), plan.getCurrentVersion())).thenReturn(List.of(item));

        String context = service.contextForToday(1L, PlanScope.MEAL);

        assertEquals("今天的餐食安排包括：高蛋白早餐。这是软上下文，本轮明确需求优先。", context);
    }

    @Test
    void 请求范围与独立启用计划不一致时不串用上下文() {
        when(mapper.findActiveByUser(1L)).thenReturn(plan("EXERCISE"));

        assertNull(service.contextForToday(1L, PlanScope.MEAL));
    }

    private WeeklyPlanRow plan(String scope) {
        WeeklyPlanRow row = new WeeklyPlanRow();
        row.setId(10L);
        row.setPlanScope(scope);
        row.setTimezone("Asia/Shanghai");
        row.setCurrentVersion(2L);
        return row;
    }

    private WeeklyPlanItemRow item(String resourceType, String name) {
        WeeklyPlanItemRow row = new WeeklyPlanItemRow();
        row.setLocalDate(LocalDate.now());
        row.setResourceType(resourceType);
        row.setName(name);
        return row;
    }
}
