package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanScope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 计划范围与资源类型的一致性 Guard，写入和激活共用同一规则。 */
@Service
public class PlanScopeGuard {

    public void requireCompatible(PlanScope scope, List<PlanItemDraft> items) {
        if (scope == null) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "必须明确选择计划范围");
        }
        List<PlanItemDraft> safeItems = items == null ? List.of() : items;
        if (safeItems.isEmpty()) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划至少需要一个项目");
        }
        Set<String> types = safeItems.stream().map(PlanItemDraft::resourceType).collect(java.util.stream.Collectors.toSet());
        if (types.contains(null) || types.stream().anyMatch(type -> !Set.of("EXERCISE", "MEAL").contains(type))) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "新计划不允许包含作息或未知资源类型");
        }
        Set<String> expected = switch (scope) {
            case EXERCISE -> Set.of("EXERCISE");
            case MEAL -> Set.of("MEAL");
            // 用户侧统一为综合周计划，餐食-only/训练-only 只是项目集合不同。
            case COMPOSITE -> Set.of("EXERCISE", "MEAL");
        };
        boolean incompatible = scope != PlanScope.COMPOSITE
                ? !expected.equals(types)
                : types.isEmpty() || !expected.containsAll(types);
        if (incompatible) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                    "计划范围与项目资源类型不一致：" + scope);
        }
    }

    public PlanScope parse(String value) {
        try {
            return PlanScope.valueOf(value);
        } catch (Exception error) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "计划范围无效或已损坏");
        }
    }
}
