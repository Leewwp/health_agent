package com.diet.health.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 当前健康会话中的训练计划需求简报，与普通推荐 slots 独立保存。 */
public record PlanBrief(
        String trainingGoal,
        List<String> bodyParts,
        List<String> equipment,
        String difficulty,
        LocalDate weekStart,
        List<DayOfWeek> trainingDays,
        TrainingTimeWindow timeWindow,
        Map<String, List<String>> hardConstraints,
        boolean confirmed,
        long confirmationVersion,
        LocalDateTime confirmedAt,
        String expectedField,
        int failedAttempts,
        LocalTime partialStartTime
) {

    public PlanBrief {
        bodyParts = immutableStrings(bodyParts);
        equipment = immutableStrings(equipment);
        trainingDays = trainingDays == null ? List.of() : List.copyOf(new LinkedHashSet<>(trainingDays));
        hardConstraints = immutableMap(hardConstraints);
        confirmationVersion = Math.max(0, confirmationVersion);
        failedAttempts = Math.max(0, failedAttempts);
        expectedField = expectedField == null || expectedField.isBlank() ? null : expectedField;
    }

    /** 兼容已有调用方的简报构造形式。 */
    public PlanBrief(String trainingGoal, List<String> bodyParts, List<String> equipment, String difficulty,
                     LocalDate weekStart, List<DayOfWeek> trainingDays, TrainingTimeWindow timeWindow,
                     Map<String, List<String>> hardConstraints, boolean confirmed, long confirmationVersion,
                     LocalDateTime confirmedAt) {
        this(trainingGoal, bodyParts, equipment, difficulty, weekStart, trainingDays, timeWindow, hardConstraints,
                confirmed, confirmationVersion, confirmedAt, null, 0, null);
    }

    public static PlanBrief empty() {
        return new PlanBrief(null, List.of(), List.of(), null, null, List.of(), null,
                Map.of(), false, 0, null, null, 0, null);
    }

    @JsonIgnore
    public boolean isComplete() {
        return hasText(trainingGoal) && !bodyParts.isEmpty() && !equipment.isEmpty()
                && hasText(difficulty) && weekStart != null && !trainingDays.isEmpty()
                && timeWindow != null;
    }

    @JsonIgnore
    public boolean isConfirmedAndComplete() {
        return isComplete() && confirmed && confirmationVersion > 0;
    }

    public PlanBrief invalidate() {
        return new PlanBrief(trainingGoal, bodyParts, equipment, difficulty, weekStart, trainingDays,
                timeWindow, hardConstraints, false, confirmationVersion, null, expectedField, failedAttempts,
                partialStartTime);
    }

    public PlanBrief confirm() {
        return new PlanBrief(trainingGoal, bodyParts, equipment, difficulty, weekStart, trainingDays,
                timeWindow, hardConstraints, true, confirmationVersion + 1, LocalDateTime.now(), null, 0, null);
    }

    public PlanBrief withProgress(String nextExpectedField, int nextFailedAttempts, LocalTime nextPartialStartTime) {
        return new PlanBrief(trainingGoal, bodyParts, equipment, difficulty, weekStart, trainingDays, timeWindow,
                hardConstraints, false, confirmationVersion, null, nextExpectedField, nextFailedAttempts,
                nextPartialStartTime);
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
