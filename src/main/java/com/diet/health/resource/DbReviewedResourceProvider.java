package com.diet.health.resource;

import com.diet.health.module.HealthResource;
import com.diet.health.module.RoutineFact;
import com.diet.mapper.ExerciseMapper;
import com.diet.mapper.MealMapper;
import com.diet.mapper.RoutineFactMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.model.MealItemRow;
import com.diet.model.RoutineFactRow;
import com.diet.util.JsonService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据库审核子集 Provider（正式模式）：读 exercise_item / meal_item / routine_fact 三张审核表。
 * <p>
 * 资源身份：餐食与动作使用数据库自增主键字符串；作息事实使用冻结业务 ref_id。
 * 审核动作数据集的器材/部位/肌群为英文原始值，Provider 统一翻译为健身槽位中文词汇
 * （与 {@code HealthSlotDictionary} 对齐），保证聊天槽位打分与周计划部位轮转可用。
 * 空表时各方法返回空集合，不抛异常。
 */
@Component
public class DbReviewedResourceProvider implements HealthResourceProvider {

    /** 审核子集批次版本（与 reviewed_resources.sql 生成批次同日）。 */
    static final String RESOURCE_VERSION = "reviewed-2026-08-10-v1";

    /** 数据集器材英文值 → 健身槽位中文值。 */
    private static final Map<String, String> EQUIPMENT_ZH = Map.of(
            "body weight", "徒手",
            "dumbbell", "哑铃",
            "band", "弹力带"
    );

    /** 数据集部位英文值 → 健身槽位中文值。 */
    private static final Map<String, String> BODY_PART_ZH = Map.of(
            "chest", "胸",
            "waist", "核心",
            "back", "背",
            "upper legs", "腿",
            "lower legs", "腿",
            "upper arms", "手臂",
            "shoulders", "肩",
            "cardio", "全身"
    );

    /** 数据集肌群英文值 → 健身槽位中文值。 */
    private static final Map<String, String> MUSCLE_ZH = Map.ofEntries(
            Map.entry("chest", "胸"),
            Map.entry("triceps", "手臂"),
            Map.entry("biceps", "手臂"),
            Map.entry("forearms", "手臂"),
            Map.entry("shoulders", "肩"),
            Map.entry("deltoids", "肩"),
            Map.entry("traps", "背"),
            Map.entry("upper back", "背"),
            Map.entry("quadriceps", "腿"),
            Map.entry("hamstrings", "腿"),
            Map.entry("calves", "腿"),
            Map.entry("ankles", "腿"),
            Map.entry("feet", "腿"),
            Map.entry("core", "核心"),
            Map.entry("obliques", "核心"),
            Map.entry("hip flexors", "核心"),
            Map.entry("lower back", "核心"),
            Map.entry("glutes", "臀")
    );

    private final ExerciseMapper exerciseMapper;
    private final MealMapper mealMapper;
    private final RoutineFactMapper routineFactMapper;
    private final JsonService jsonService;

    public DbReviewedResourceProvider(ExerciseMapper exerciseMapper, MealMapper mealMapper,
                                      RoutineFactMapper routineFactMapper, JsonService jsonService) {
        this.exerciseMapper = exerciseMapper;
        this.mealMapper = mealMapper;
        this.routineFactMapper = routineFactMapper;
        this.jsonService = jsonService;
    }

    @Override
    public List<HealthResource> exercises() {
        return exerciseMapper.findAllApproved().stream()
                .map(this::toResource)
                .toList();
    }

    @Override
    public List<RoutineFact> routineFacts() {
        return routineFactMapper.selectAll().stream()
                .map(this::toFact)
                .toList();
    }

    @Override
    public Optional<HealthResource> exerciseById(String resourceId) {
        Long id = parseId(resourceId);
        if (id == null) {
            return Optional.empty();
        }
        ExerciseItemRow row = exerciseMapper.findById(id);
        return row == null ? Optional.empty() : Optional.of(toResource(row));
    }

    @Override
    public Optional<HealthResource> mealById(String resourceId) {
        Long id = parseId(resourceId);
        if (id == null) {
            return Optional.empty();
        }
        MealItemRow row = mealMapper.findApprovedPublicById(id);
        return row == null ? Optional.empty() : Optional.of(toResource(row));
    }

    @Override
    public Optional<RoutineFact> routineFactById(String factId) {
        RoutineFactRow row = routineFactMapper.selectByRefId(factId);
        return row == null ? Optional.empty() : Optional.of(toFact(row));
    }

    @Override
    public Optional<RoutineFact> routineFactByTopic(String topicKeyword) {
        if (topicKeyword == null || topicKeyword.isBlank()) {
            return Optional.empty();
        }
        return routineFactMapper.selectByTopicLike(topicKeyword).stream()
                .map(this::toFact)
                .filter(fact -> fact.category().contains(topicKeyword) || topicKeyword.contains(fact.category()))
                .findFirst();
    }

    @Override
    public List<String> allFactIds() {
        return routineFactMapper.selectAll().stream()
                .map(RoutineFactRow::getRefId)
                .toList();
    }

    @Override
    public List<HealthResource> planReadyExercises() {
        return exerciseMapper.findAllApproved().stream()
                .filter(row -> Boolean.TRUE.equals(row.getPlanReady()))
                .map(this::toResource)
                .toList();
    }

    @Override
    public List<String> planReadyExerciseIds() {
        return exerciseMapper.findAllApproved().stream()
                .filter(row -> Boolean.TRUE.equals(row.getPlanReady()))
                .map(row -> String.valueOf(row.getId()))
                .toList();
    }

    @Override
    public String providerMode() {
        return "REVIEWED_DB";
    }

    @Override
    public String resourceVersion() {
        return RESOURCE_VERSION;
    }

    // ---------- 行 → 资源映射 ----------

    /** 动作行 → 类型化资源：标签翻译为健身槽位中文词汇。 */
    private HealthResource toResource(ExerciseItemRow row) {
        List<String> bodyParts = new ArrayList<>();
        addTranslated(bodyParts, row.getBodyPart());
        jsonService.fromJsonArray(row.getTargetMuscles()).forEach(muscle -> addTranslated(bodyParts, muscle));
        jsonService.fromJsonArray(row.getSecondaryMuscles()).forEach(muscle -> addTranslated(bodyParts, muscle));
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("bodyParts", bodyParts.stream().distinct().toList());
        tags.put("primaryBodyPart", List.of(translate(BODY_PART_ZH, row.getBodyPart())));
        tags.put("equipment", List.of(translate(EQUIPMENT_ZH, row.getEquipment())));
        tags.put("difficulty", List.of(row.getDifficulty()));
        tags.put("movementPattern", List.of(row.getMovementPattern()));
        tags.put("trainingGoal", List.of());
        return new HealthResource(
                "EXERCISE",
                String.valueOf(row.getId()),
                row.getName(),
                "DATASET",
                row.getSourceName(),
                null,
                Boolean.TRUE.equals(row.getPlanReady()),
                tags
        );
    }

    /** 餐食行 → 类型化资源：resourceId 为数据库主键。 */
    private HealthResource toResource(MealItemRow row) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", jsonService.fromJsonArray(row.getMealTime()));
        tags.put("mood", jsonService.fromJsonArray(row.getMood()));
        tags.put("scene", jsonService.fromJsonArray(row.getScene()));
        tags.put("healthGoal", jsonService.fromJsonArray(row.getHealthGoal()));
        tags.put("cuisine", jsonService.fromJsonArray(row.getCuisine()));
        tags.put("taste", jsonService.fromJsonArray(row.getTaste()));
        tags.put("convenience", jsonService.fromJsonArray(row.getConvenience()));
        return new HealthResource(
                "MEAL",
                String.valueOf(row.getId()),
                row.getName(),
                row.getSourceType() == null ? "PUBLIC" : row.getSourceType(),
                "公共餐食库",
                row.getMediaUrl(),
                false,
                tags
        );
    }

    /** 事实行 → 结构化事实：factId 为冻结 ref_id，category 为 topic，sourceDetail 为适用范围。 */
    private RoutineFact toFact(RoutineFactRow row) {
        return new RoutineFact(
                row.getRefId(),
                row.getTopic(),
                row.getFactZh(),
                row.getSource(),
                row.getScope()
        );
    }

    /** 英文数据集值 → 中文槽位值（未收录时原样透出，保证不丢数据）。 */
    private String translate(Map<String, String> dictionary, String value) {
        if (value == null) {
            return "";
        }
        return dictionary.getOrDefault(value, value);
    }

    private void addTranslated(List<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.add(translate(MUSCLE_ZH, value));
    }

    /** 资源 ID 转主键，非法返回 null（空库/脏数据不抛异常）。 */
    private Long parseId(String resourceId) {
        try {
            return Long.parseLong(resourceId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
