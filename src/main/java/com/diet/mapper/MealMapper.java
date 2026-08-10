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

    List<MealItemRow> search(
            @Param("sourceMode") SourceMode sourceMode,
            @Param("userId") Long userId,
            @Param("mealTimeJson") String mealTimeJson,
            @Param("moodJson") String moodJson,
            @Param("sceneJson") String sceneJson,
            @Param("healthGoalJson") String healthGoalJson,
            @Param("cuisineJson") String cuisineJson,
            @Param("tasteJson") String tasteJson,
            @Param("convenienceJson") String convenienceJson,
            @Param("limit") int limit
    );

    /** 浏览页：审核通过的公共餐食，按 id 升序分页。 */
    List<MealItemRow> browsePublicMeals(@Param("offset") int offset, @Param("size") int size);

    /** 审核通过的公共餐食总数（浏览分页用）。 */
    int countPublicMeals();

    /** 全部审核通过的公共餐食（离线 Embedding 生成用）。 */
    List<MealItemRow> findApprovedPublicMeals();
}




