package com.diet.health.plan;

import java.time.LocalTime;

/** 训练计划每次可执行的本地时间窗口。 */
public record TrainingTimeWindow(LocalTime start, LocalTime end) {

    public TrainingTimeWindow {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("训练时间窗口必须是有效的开始和结束时间");
        }
    }

    public boolean contains(LocalTime value) {
        return value != null && !value.isBefore(start) && !value.isAfter(end);
    }
}
