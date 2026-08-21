package com.diet.health.reader.meal;

import com.diet.enums.SourceMode;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.model.SlotBundle;
import com.diet.util.JsonService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审核餐食读取模块 DB adapter（#68，方案 B）。
 * <p>
 * 本类与 {@code DbReviewedResourceProvider} 是仅有的两个允许依赖 MealMapper 的类。
 * 集中 MealItemRow → {@link ReviewedMeal} 的映射与 JSON 解析：浏览、领域召回、
 * 索引/评估快照全部来自同一行映射口径。SQL 复用现有 Mapper 契约（不为抽象重写 SQL）。
 */
@Component
public class DbReviewedMealReader implements ReviewedMealReader {

    private final MealMapper mealMapper;
    private final JsonService jsonService;

    public DbReviewedMealReader(MealMapper mealMapper, JsonService jsonService) {
        this.mealMapper = mealMapper;
        this.jsonService = jsonService;
    }

    @Override
    public List<ReviewedMeal> recallStructured(Map<String, List<String>> slots, int limit) {
        SlotBundle bundle = SlotBundle.fromHealthSlots(slots);
        List<MealItemRow> rows = mealMapper.search(
                SourceMode.PUBLIC, null,
                jsonService.toJsonArray(bundle.mealTime()),
                jsonService.toJsonArray(bundle.mood()),
                jsonService.toJsonArray(bundle.scene()),
                jsonService.toJsonArray(bundle.healthGoal()),
                jsonService.toJsonArray(bundle.cuisine()),
                jsonService.toJsonArray(bundle.taste()),
                jsonService.toJsonArray(bundle.convenience()),
                limit
        );
        return rows.stream()
                .filter(row -> "APPROVED".equals(row.getReviewStatus()))
                .map(this::toReviewedMeal)
                .toList();
    }

    @Override
    public List<ReviewedMeal> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mealMapper.findApprovedPublicByIds(ids.stream().distinct().toList()).stream()
                .map(this::toReviewedMeal)
                .toList();
    }

    @Override
    public Optional<ReviewedMeal> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        MealItemRow row = mealMapper.findApprovedPublicById(id);
        return row == null ? Optional.empty() : Optional.of(toReviewedMeal(row));
    }

    @Override
    public List<ReviewedMeal> browse(int offset, int size) {
        return mealMapper.browsePublicMeals(offset, size).stream()
                .map(this::toReviewedMeal)
                .toList();
    }

    @Override
    public int countPublic() {
        return mealMapper.countPublicMeals();
    }

    @Override
    public List<ReviewedMeal> snapshotAll() {
        return mealMapper.findApprovedPublicMeals().stream()
                .map(this::toReviewedMeal)
                .toList();
    }

    /** 行 → 审核餐食视图（单一映射实现；JSON 空值/空数组/多值解析口径与浏览/索引一致）。 */
    public ReviewedMeal toReviewedMeal(MealItemRow row) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", jsonService.fromJsonArray(row.getMealTime()));
        tags.put("mood", jsonService.fromJsonArray(row.getMood()));
        tags.put("scene", jsonService.fromJsonArray(row.getScene()));
        tags.put("healthGoal", jsonService.fromJsonArray(row.getHealthGoal()));
        tags.put("cuisine", jsonService.fromJsonArray(row.getCuisine()));
        tags.put("taste", jsonService.fromJsonArray(row.getTaste()));
        tags.put("convenience", jsonService.fromJsonArray(row.getConvenience()));
        return new ReviewedMeal(
                row.getId(),
                row.getName(),
                row.getNameEn(),
                jsonService.fromJsonArray(row.getAliases()),
                tags,
                row.getDescription(),
                jsonService.fromJsonArray(row.getIngredientsJson()),
                new ReviewedMeal.Serving(
                        row.getServingCount() == null ? 0 : row.getServingCount(),
                        row.getServingSize(),
                        row.getServingUnit()),
                new ReviewedMeal.Nutrition(
                        row.getCaloriesKcal(),
                        row.getProteinG(),
                        row.getFatG(),
                        row.getCarbohydrateG(),
                        row.getNutritionBasis(),
                        Boolean.TRUE.equals(row.getNutritionEstimated())),
                jsonService.fromJsonArray(row.getAllergenJson()),
                row.getAllergenStatus(),
                row.getReviewStatus(),
                row.getMediaUrl(),
                row.getMediaStatus(),
                row.getMediaCredit(),
                row.getSourceName(),
                row.getSourceId(),
                row.getSourceVersion(),
                row.getSourceType() == null ? "PUBLIC" : row.getSourceType()
        );
    }
}
