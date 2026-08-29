package com.diet.mapper;

import com.diet.model.MealItemRow;
import com.diet.enums.SourceMode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MealMapper {
    int insert(MealItemRow row);

    int updatePersonal(MealItemRow row);

    int deletePersonal(@Param("id") Long id, @Param("userId") Long userId);

    MealItemRow findPersonalById(@Param("id") Long id, @Param("userId") Long userId);

    List<MealItemRow> findPersonalMeals(Long userId);

    List<MealItemRow> findPublicMeals();

    int countPersonalMeals(Long userId);

    /** 槽位检索（唯一语句）：foodTypeJson 恒定声明，空数组即不过滤（加固规格合并语句族）。 */
    List<MealItemRow> search(
            @Param("sourceMode") SourceMode sourceMode,
            @Param("userId") Long userId,
            @Param("mealTimeJson") String mealTimeJson,
            @Param("moodJson") String moodJson,
            @Param("sceneJson") String sceneJson,
            @Param("healthGoalJson") String healthGoalJson,
            @Param("cuisineJson") String cuisineJson,
            @Param("foodTypeJson") String foodTypeJson,
            @Param("tasteJson") String tasteJson,
            @Param("convenienceJson") String convenienceJson,
            @Param("limit") int limit
    );

    /**
     * 浏览页（唯一语句）：审核通过的公共餐食，按 id 升序分页；favoriteOnly=false 且
     * 各过滤参数为空时即全量浏览。与 {@link #countPublicMealsFiltered} 逐谓词一致。
     */
    List<MealItemRow> browsePublicMealsFiltered(@Param("userId") Long userId,
                                                @Param("favoriteOnly") boolean favoriteOnly,
                                                @Param("query") String query,
                                                @Param("mealTime") String mealTime,
                                                @Param("cuisine") String cuisine,
                                                @Param("foodType") String foodType,
                                                @Param("taste") String taste,
                                                @Param("healthGoal") String healthGoal,
                                                @Param("offset") int offset, @Param("size") int size);

    /** 浏览分页计数（唯一语句）：与 browsePublicMealsFiltered 同谓词同顺序，页总数与行数一致。 */
    int countPublicMealsFiltered(@Param("userId") Long userId,
                                 @Param("favoriteOnly") boolean favoriteOnly,
                                 @Param("query") String query,
                                 @Param("mealTime") String mealTime,
                                 @Param("cuisine") String cuisine,
                                 @Param("foodType") String foodType,
                                 @Param("taste") String taste,
                                 @Param("healthGoal") String healthGoal);

    /** 全部审核通过的公共餐食（离线 Embedding 生成用）。 */
    List<MealItemRow> findApprovedPublicMeals();

    /** 按主键查审核通过公共餐食（反馈校验与组合器用）。 */
    MealItemRow findApprovedPublicById(@Param("id") Long id);

    /** 按主键批量查审核通过公共餐食（hybrid 向量命中的 MySQL 二次校验用），按 id 升序。 */
    List<MealItemRow> findApprovedPublicByIds(@Param("ids") List<Long> ids);
}
