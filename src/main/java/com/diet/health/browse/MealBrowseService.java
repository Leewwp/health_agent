package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 餐食浏览服务（规格 6.2）。
 * 只暴露审核通过（review_status=APPROVED）的公共餐食；分页参数：page≥1、1≤size≤50。
 * 无媒体许可的餐食以稳定无图状态展示（mediaUrl 不下发）。
 * 数据读取经 {@link ReviewedMealReader}（方案 B），本层不接触 Mapper 行对象。
 */
@Service
public class MealBrowseService {

    /** size 上限（规格 6.2）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final ReviewedMealReader reviewedMealReader;

    public MealBrowseService(ReviewedMealReader reviewedMealReader) {
        this.reviewedMealReader = reviewedMealReader;
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
        List<ReviewedMeal> meals = reviewedMealReader.browse((int) offset, size);
        int total = reviewedMealReader.countPublic();
        List<MealBrowseItem> items = meals.stream().map(MealBrowseService::toItem).toList();
        return PagedResponse.of(items, page, size, total);
    }

    /** 读取模型 → 浏览条目（浏览用例层透传，字段口径与读取模块同一行映射一致）。 */
    private static MealBrowseItem toItem(ReviewedMeal meal) {
        return new MealBrowseItem(
                meal.id(),
                meal.name(),
                meal.nameEn(),
                meal.aliases(),
                meal.tags(),
                meal.description(),
                meal.ingredients(),
                new MealBrowseItem.Serving(
                        meal.serving().count(),
                        meal.serving().size(),
                        meal.serving().unit()),
                new MealBrowseItem.Nutrition(
                        meal.nutrition().caloriesKcal(),
                        meal.nutrition().proteinG(),
                        meal.nutrition().fatG(),
                        meal.nutrition().carbohydrateG(),
                        meal.nutrition().basis(),
                        meal.nutrition().estimated()),
                meal.allergens(),
                meal.allergenStatus(),
                meal.reviewStatus(),
                meal.mediaStatus(),
                meal.mediaCredit(),
                meal.sourceName(),
                meal.sourceId(),
                meal.sourceVersion()
        );
    }
}
