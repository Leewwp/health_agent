package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.util.JsonService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 餐食浏览服务（规格 6.2）。
 * 只暴露审核通过（review_status=APPROVED）的公共餐食；分页参数：page≥1、1≤size≤50。
 * 无媒体许可的餐食以稳定无图状态展示（mediaUrl 不下发）。
 */
@Service
public class MealBrowseService {

    /** size 上限（规格 6.2）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final MealMapper mealMapper;
    private final JsonService jsonService;

    public MealBrowseService(MealMapper mealMapper, JsonService jsonService) {
        this.mealMapper = mealMapper;
        this.jsonService = jsonService;
    }

    public PagedResponse<MealBrowseItem> browse(int page, int size) {
        if (page < 1) {
            throw new DietException("page 必须不小于 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new DietException("size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        // 43 号票：long 计算 offset 防 int 溢出，超出数据库安全范围统一 400
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new DietException("page 超出安全范围");
        }
        List<MealItemRow> rows = mealMapper.browsePublicMeals((int) offset, size);
        int total = mealMapper.countPublicMeals();
        List<MealBrowseItem> items = rows.stream().map(this::toItem).toList();
        return PagedResponse.of(items, page, size, total);
    }

    private MealBrowseItem toItem(MealItemRow row) {
        return new MealBrowseItem(
                row.getId(),
                row.getName(),
                row.getNameEn(),
                jsonService.fromJsonArray(row.getAliases()),
                toTags(row),
                row.getDescription(),
                jsonService.fromJsonArray(row.getIngredientsJson()),
                new MealBrowseItem.Serving(
                        row.getServingCount() == null ? 0 : row.getServingCount(),
                        row.getServingSize(),
                        row.getServingUnit()),
                new MealBrowseItem.Nutrition(
                        row.getCaloriesKcal(),
                        row.getProteinG(),
                        row.getFatG(),
                        row.getCarbohydrateG(),
                        row.getNutritionBasis(),
                        Boolean.TRUE.equals(row.getNutritionEstimated())),
                jsonService.fromJsonArray(row.getAllergenJson()),
                row.getAllergenStatus(),
                row.getReviewStatus(),
                row.getMediaStatus(),
                row.getMediaCredit(),
                row.getSourceName(),
                row.getSourceId(),
                row.getSourceVersion()
        );
    }

    /** 7 维槽位 JSON → 标签 Map。 */
    private Map<String, List<String>> toTags(MealItemRow row) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", jsonService.fromJsonArray(row.getMealTime()));
        tags.put("mood", jsonService.fromJsonArray(row.getMood()));
        tags.put("scene", jsonService.fromJsonArray(row.getScene()));
        tags.put("healthGoal", jsonService.fromJsonArray(row.getHealthGoal()));
        tags.put("cuisine", jsonService.fromJsonArray(row.getCuisine()));
        tags.put("taste", jsonService.fromJsonArray(row.getTaste()));
        tags.put("convenience", jsonService.fromJsonArray(row.getConvenience()));
        return tags;
    }
}
