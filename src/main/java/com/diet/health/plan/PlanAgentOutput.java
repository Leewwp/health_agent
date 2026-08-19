package com.diet.health.plan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Plan Agent 的最小结构化输出，只允许引用候选动作并决定排期。 */
public record PlanAgentOutput(List<ScheduledExercise> schedule) {
    public PlanAgentOutput {
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
    }

    public record ScheduledExercise(String exerciseId, LocalDate localDate, LocalTime startTime, Integer durationMinutes) {
    }
}
