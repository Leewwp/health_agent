package com.diet.health.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 当前健康会话中的训练计划需求简报，与普通推荐 slots 独立保存。
 * 简报只表达"当前整理出的条件"，没有独立的用户确认状态或确认版本；
 * 开始生成时由服务端重新读取并校验完整性（ADR-0016）。
 */
public record PlanBrief(
        String trainingGoal,
        List<String> bodyParts,
        List<String> equipment,
        String difficulty,
        LocalDate weekStart,
        List<DayOfWeek> trainingDays,
        TrainingTimeWindow timeWindow,
        Map<String, List<String>> hardConstraints,
        String expectedField,
        int failedAttempts,
        LocalTime partialStartTime
) {

    public PlanBrief {
        bodyParts = immutableStrings(bodyParts);
        equipment = immutableStrings(equipment);
        trainingDays = trainingDays == null ? List.of() : List.copyOf(new LinkedHashSet<>(trainingDays));
        hardConstraints = immutableMap(hardConstraints);
        failedAttempts = Math.max(0, failedAttempts);
        expectedField = expectedField == null || expectedField.isBlank() ? null : expectedField;
    }

    public static PlanBrief empty() {
        return new PlanBrief(null, List.of(), List.of(), null, null, List.of(), null,
                Map.of(), null, 0, null);
    }

    @JsonIgnore
    public boolean isComplete() {
        return hasText(trainingGoal) && !bodyParts.isEmpty() && !equipment.isEmpty()
                && hasText(difficulty) && weekStart != null && !trainingDays.isEmpty()
                && timeWindow != null;
    }

    public PlanBrief withProgress(String nextExpectedField, int nextFailedAttempts, LocalTime nextPartialStartTime) {
        return new PlanBrief(trainingGoal, bodyParts, equipment, difficulty, weekStart, trainingDays, timeWindow,
                hardConstraints, nextExpectedField, nextFailedAttempts, nextPartialStartTime);
    }

    public PlanBrief recordFailure(String field) {
        return withProgress(field == null ? expectedField : field, failedAttempts + 1, partialStartTime);
    }

    @JsonIgnore
    public List<LocalDate> scheduledDates() {
        if (weekStart == null) {
            return List.of();
        }
        return trainingDays.stream().map(day -> weekStart.plusDays(day.getValue() - 1L)).toList();
    }

    private static List<String> immutableStrings(List<String> values) {
        return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values));
    }

    private static Map<String, List<String>> immutableMap(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null && !value.isEmpty()) {
                copy.put(key, immutableStrings(value));
            }
        });
        return Map.copyOf(copy);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
