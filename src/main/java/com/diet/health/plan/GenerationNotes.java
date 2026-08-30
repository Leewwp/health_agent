package com.diet.health.plan;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生成说明（简报补充回路规格 v3.2 固定合同 + 演示召回规格 P1 扩展 + ADR-0018 餐训适配扩展）：
 * {unsupportedPreferences: string[], fallbacks: [{date, mealTimes, unmetPreferences}],
 *  candidateScarcity: string[], mealAdaptations: [{date, mealTime, originalStart, originalEnd,
 *  finalStart, finalEnd, direction}]}。
 * 所有数组非 null；日期为计划时区 ISO 日期（YYYY-MM-DD）。
 * 同时存在于生成 metadata、resource_snapshot_json.generation.generationNotes、
 * PlanView.generationNotes、计划详情 API、版本详情 API 与计划页头部说明；
 * 旧计划缺 metadata 时返回非 null 空对象。
 */
public record GenerationNotes(List<String> unsupportedPreferences, List<FallbackDay> fallbacks,
                              List<String> candidateScarcity, List<MealAdaptationView> mealAdaptations) {

    /** 按日回退记录：日期 + 所选餐次 + 未满足偏好的“字段:值”键。 */
    public record FallbackDay(String date, List<String> mealTimes, List<String> unmetPreferences) {
        public FallbackDay {
            mealTimes = mealTimes == null ? List.of() : List.copyOf(mealTimes);
            unmetPreferences = unmetPreferences == null ? List.of() : List.copyOf(unmetPreferences);
        }
    }

    /** 餐训时间适配记录（ADR-0018）：被移动餐次、原始默认窗口、最终窗口与方向（AFTER_TRAINING/BEFORE_TRAINING）。 */
    public record MealAdaptationView(String date, String mealTime,
                                     String originalStart, String originalEnd,
                                     String finalStart, String finalEnd,
                                     String direction) {
        public MealAdaptationView {
            originalStart = originalStart == null ? "" : originalStart;
            originalEnd = originalEnd == null ? "" : originalEnd;
            finalStart = finalStart == null ? "" : finalStart;
            finalEnd = finalEnd == null ? "" : finalEnd;
            direction = direction == null ? "" : direction;
        }
    }

    public GenerationNotes {
        unsupportedPreferences = unsupportedPreferences == null ? List.of() : List.copyOf(unsupportedPreferences);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        candidateScarcity = candidateScarcity == null ? List.of() : List.copyOf(candidateScarcity);
        mealAdaptations = mealAdaptations == null ? List.of() : List.copyOf(mealAdaptations);
    }

    /** 兼容既有两参构造：候选稀缺说明与餐训适配记录缺省为空。 */
    public GenerationNotes(List<String> unsupportedPreferences, List<FallbackDay> fallbacks) {
        this(unsupportedPreferences, fallbacks, List.of(), List.of());
    }

    /** 兼容既有三参构造：餐训适配记录缺省为空。 */
    public GenerationNotes(List<String> unsupportedPreferences, List<FallbackDay> fallbacks,
                           List<String> candidateScarcity) {
        this(unsupportedPreferences, fallbacks, candidateScarcity, List.of());
    }

    public static GenerationNotes empty() {
        return new GenerationNotes(List.of(), List.of(), List.of(), List.of());
    }

    /** metadata 键名（resource_snapshot_json.generation.generationNotes 同名）。 */
    public static final String METADATA_KEY = "generationNotes";

    /** 序列化为 metadata Map（写入生成 metadata 与版本快照 generation 节点）。 */
    public Map<String, Object> toMetadata() {
        Map<String, Object> notes = new java.util.LinkedHashMap<>();
        notes.put("unsupportedPreferences", unsupportedPreferences);
        List<Map<String, Object>> days = new ArrayList<>();
        for (FallbackDay fallback : fallbacks) {
            Map<String, Object> day = new java.util.LinkedHashMap<>();
            day.put("date", fallback.date());
            day.put("mealTimes", fallback.mealTimes());
            day.put("unmetPreferences", fallback.unmetPreferences());
            days.add(day);
        }
        notes.put("fallbacks", days);
        notes.put("candidateScarcity", candidateScarcity);
        List<Map<String, Object>> adaptations = new ArrayList<>();
        for (MealAdaptationView view : mealAdaptations) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("date", view.date());
            entry.put("mealTime", view.mealTime());
            entry.put("originalStart", view.originalStart());
            entry.put("originalEnd", view.originalEnd());
            entry.put("finalStart", view.finalStart());
            entry.put("finalEnd", view.finalEnd());
            entry.put("direction", view.direction());
            adaptations.add(entry);
        }
        notes.put("mealAdaptations", adaptations);
        return notes;
    }

    /** 综合生成入口：在既有说明基础上合并餐训适配记录（ADR-0018，写入 metadata 与版本快照）。 */
    public Map<String, Object> toMetadataWithAdditionalMealAdaptations(
            List<MealTrainingScheduleAdapter.AdaptationNote> notes) {
        Map<String, Object> metadata = toMetadata();
        List<Map<String, Object>> adaptations = new ArrayList<>();
        if (notes != null) {
            for (MealTrainingScheduleAdapter.AdaptationNote note : notes) {
                adaptations.add(note.toMetadata());
            }
        }
        metadata.put("mealAdaptations", adaptations);
        return metadata;
    }

    /** 从生成 metadata 解析；缺失或损坏时返回非 null 空对象（旧计划兼容）。 */
    @SuppressWarnings("unchecked")
    public static GenerationNotes fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get(METADATA_KEY) instanceof Map<?, ?> raw)) {
            return empty();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> converted = mapper.convertValue(raw,
                    new TypeReference<Map<String, Object>>() { });
            Object unsupported = converted.get("unsupportedPreferences");
            List<String> unsupportedList = unsupported instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            Object scarcity = converted.get("candidateScarcity");
            List<String> scarcityList = scarcity instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            List<FallbackDay> fallbacks = new ArrayList<>();
            if (converted.get("fallbacks") instanceof List<?> days) {
                for (Object item : days) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> day = mapper.convertValue(item,
                            new TypeReference<Map<String, Object>>() { });
                    Object date = day.get("date");
                    if (date == null) {
                        continue;
                    }
                    fallbacks.add(new FallbackDay(String.valueOf(date),
                            stringList(day.get("mealTimes")), stringList(day.get("unmetPreferences"))));
                }
            }
            List<MealAdaptationView> adaptations = new ArrayList<>();
            if (converted.get("mealAdaptations") instanceof List<?> entries) {
                for (Object item : entries) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> entry = mapper.convertValue(item,
                            new TypeReference<Map<String, Object>>() { });
                    Object date = entry.get("date");
                    if (date == null) {
                        continue;
                    }
                    adaptations.add(new MealAdaptationView(String.valueOf(date),
                            String.valueOf(entry.getOrDefault("mealTime", "")),
                            String.valueOf(entry.getOrDefault("originalStart", "")),
                            String.valueOf(entry.getOrDefault("originalEnd", "")),
                            String.valueOf(entry.getOrDefault("finalStart", "")),
                            String.valueOf(entry.getOrDefault("finalEnd", "")),
                            String.valueOf(entry.getOrDefault("direction", ""))));
                }
            }
            return new GenerationNotes(unsupportedList, fallbacks, scarcityList, adaptations);
        } catch (Exception ignored) {
            return empty();
        }
    }

    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}