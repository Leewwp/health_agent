package com.diet.mapper;

import com.diet.model.MealEmbeddingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** meal_item_embedding 表（33 号票餐食向量）。 */
@Mapper
public interface MealEmbeddingMapper {

    /** 按餐食 ID + 模型 + 模型版本批量读取向量。 */
    List<MealEmbeddingRow> findByMealIds(@Param("mealIds") List<Long> mealIds,
                                         @Param("model") String model,
                                         @Param("modelVersion") String modelVersion);

    /** 幂等写入（同 meal_id+model+version 时更新向量）。 */
    int upsert(MealEmbeddingRow row);
}
