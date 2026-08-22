package com.diet.health.plan;

import com.diet.health.enums.PlanScope;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/** 从唯一 ENABLED 综合计划读取当天软上下文，不维护独立的当前安排关系。 */
@Service
public class EnabledPlanContextService {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final WeeklyPlanMapper planMapper;
    private final PlanScopeGuard scopeGuard;

    public EnabledPlanContextService(WeeklyPlanMapper planMapper, PlanScopeGuard scopeGuard) {
        this.planMapper = planMapper;
        this.scopeGuard = scopeGuard;
    }

    /** 读取计划时区下今天的项目，供聊天作为低优先级软上下文。 */
    public String contextForToday(Long userId, PlanScope requestedScope) {
        List<WeeklyPlanItemRow> items = todayItems(userId, requestedScope);
        if (items.isEmpty()) {
            return null;
        }
        String label = requestedScope == PlanScope.MEAL ? "餐食安排" : "训练安排";
        return "今天的" + label + "包括：" + items.stream().map(WeeklyPlanItemRow::getName)
                .collect(Collectors.joining("、"))
                + "。这是软上下文，本轮明确需求优先。";
    }

    /** 仅在用户明确指向当前计划时返回当天资源，作为请求级排除。 */
    public List<String> resourceIdsForToday(Long userId, PlanScope requestedScope) {
        return todayItems(userId, requestedScope).stream()
                .map(WeeklyPlanItemRow::getResourceId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    /** 返回当前启用计划 ID，供聊天发起“追加到当前计划”编辑流程。 */
    public Long enabledPlanId(Long userId, PlanScope requestedScope) {
        if (userId == null || requestedScope == null) return null;
        WeeklyPlanRow plan = planMapper.findActiveByUser(userId);
        if (plan == null) return null;
        PlanScope actual = scopeGuard.parse(plan.getPlanScope());
        return actual == PlanScope.COMPOSITE || actual == requestedScope ? plan.getId() : null;
    }

    private List<WeeklyPlanItemRow> todayItems(Long userId, PlanScope requestedScope) {
        if (userId == null || requestedScope == null) {
            return List.of();
        }
        WeeklyPlanRow plan = planMapper.findActiveByUser(userId);
        if (plan == null) {
            return List.of();
        }
        PlanScope actualScope = scopeGuard.parse(plan.getPlanScope());
        if (actualScope != PlanScope.COMPOSITE) {
            return List.of();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(plan.getTimezone());
        } catch (Exception ignored) {
            zone = ZoneId.of(DEFAULT_TIMEZONE);
        }
        LocalDate today = LocalDate.now(zone);
        String resourceType = requestedScope.name();
        return planMapper.findItems(plan.getId(), plan.getCurrentVersion()).stream()
                .filter(item -> today.equals(item.getLocalDate()))
                .filter(item -> resourceType.equals(item.getResourceType()))
                .toList();
    }
}
