package com.diet.health.resource;

import com.diet.exception.DietException;
import com.diet.health.module.HealthResource;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.module.RoutineFact;
import com.diet.health.reader.exercise.ExerciseVocabulary;
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
 * 动作槽位词汇统一经 {@link ExerciseVocabulary} 归一（与浏览读取模块共用同一套规则），
 * 未收录的原始值过滤掉、不透出英文，保证聊天槽位打分与周计划部位轮转可用。
 * 空表时各方法返回空集合，不抛异常。
 */
@Component
public class DbReviewedResourceProvider implements HealthResourceProvider {

    /** 审核子集批次版本（与 reviewed_resources.sql 生成批次同日）。 */
    static final String RESOURCE_VERSION = "reviewed-2026-08-10-v1";

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
    public List<HealthResource> singleRecommendationExercises() {
        return exerciseMapper.findAllCatalog().stream()
                .map(this::toResource)
                .toList();
    }

    @Override
    public List<HealthResource> singleRecommendationMeals() {
        return mealMapper.findApprovedPublicMeals().stream().map(this::toResource).toList();
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
    public List<PlanMealCandidate> planMealCandidates() {
        return mealMapper.findApprovedPublicMeals().stream()
                .map(this::toMealCandidate)
                .toList();
    }

    @Override
    public ResourceMode providerMode() {
        return ResourceMode.REVIEWED_DB;
    }

    @Override
    public String resourceVersion() {
        return RESOURCE_VERSION;
    }

    // ---------- 行 → 资源映射 ----------

    /** 动作行 → 类型化资源：标签经共享词汇模块归一为健身槽位中文词汇（未收录原始值过滤，不透出英文）。 */
    private HealthResource toResource(ExerciseItemRow row) {
        List<String> bodyParts = new ArrayList<>();
        addPartZh(bodyParts, row.getBodyPart());
        jsonService.fromJsonArray(row.getTargetMuscles()).forEach(muscle -> addPartZh(bodyParts, muscle));
        jsonService.fromJsonArray(row.getSecondaryMuscles()).forEach(muscle -> addPartZh(bodyParts, muscle));
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("bodyParts", bodyParts.stream().distinct().toList());
        tags.put("primaryBodyPart", singleZh(ExerciseVocabulary.partZh(row.getBodyPart())));
        tags.put("equipment", singleZh(ExerciseVocabulary.equipmentZh(row.getEquipment())));
        tags.put("difficulty", singleZh(ExerciseVocabulary.difficultyZh(row.getDifficulty())));
        tags.put("movementPattern", row.getMovementPattern() == null || row.getMovementPattern().isBlank()
                ? List.of() : List.of(row.getMovementPattern()));
        tags.put("trainingGoal", jsonService.fromJsonArray(row.getTrainingGoals()));
        return new HealthResource(
                "EXERCISE",
                String.valueOf(row.getId()),
                row.getName(),
                "DATASET",
                row.getSourceName(),
                licensedMediaUrl(row.getMediaUrl(), row.getMediaState()),
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
                licensedMediaUrl(row.getMediaUrl(), row.getMediaStatus()),
                false,
                tags,
                jsonService.fromJsonArray(row.getIngredientsJson()),
                new HealthResource.Nutrition(row.getCaloriesKcal(), row.getProteinG(), row.getFatG(),
                        row.getCarbohydrateG(), row.getNutritionBasis(), Boolean.TRUE.equals(row.getNutritionEstimated()))
        );
    }

    /** 餐食行 → 计划餐食候选：resourceId 为数据库主键，sortKey 为主键序；热量缺失保留 null 由挑选器降级。
     *  餐次标签解析失败按空标签（全时段）降级，不因单行脏数据中断整个计划生成。 */
    private PlanMealCandidate toMealCandidate(MealItemRow row) {
        List<String> mealTimeTags = List.of();
        try {
            mealTimeTags = jsonService.fromJsonArray(row.getMealTime());
        } catch (DietException ignored) {
            // 畸形餐次 JSON 按空标签（全时段可用）降级，与热量缺失降级精神一致
        }
        return new PlanMealCandidate(
                "MEAL",
                String.valueOf(row.getId()),
                row.getName(),
                mealTimeTags,
                row.getCaloriesKcal() == null ? null : row.getCaloriesKcal().intValue(),
                row.getId()
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

    /**
     * 数据集英文部位/肌群值 → 健身槽位中文值：统一经共享词汇模块归一
     * （先查部位字典，再查肌群字典），保证与浏览读取模型同口径；
     * 未收录的原始值返回空串并过滤，不透出英文，输出值属于健身槽位合法中文集合。
     */
    private static String toPartZh(String value) {
        return ExerciseVocabulary.partZh(value);
    }

    private static void addPartZh(List<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String zh = toPartZh(value);
        if (!zh.isEmpty()) {
            target.add(zh);
        }
    }

    /** 数据集器材英文值 → 健身槽位中文值（未收录过滤，不透出英文）。 */
    private static String toEquipmentZh(String value) {
        return ExerciseVocabulary.equipmentZh(value);
    }

    /** 数据集难度 → 健身槽位合法难度（未收录返回空串）。 */
    private static String toDifficultyZh(String value) {
        return ExerciseVocabulary.difficultyZh(value);
    }

    /** 单个翻译值包装为标签列表；未收录为空列表，不透出英文原始值。 */
    private static List<String> singleZh(String value) {
        return value.isEmpty() ? List.of() : List.of(value);
    }

    private static String licensedMediaUrl(String mediaUrl, String mediaStatus) {
        return "LICENSED".equals(mediaStatus) ? mediaUrl : null;
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
