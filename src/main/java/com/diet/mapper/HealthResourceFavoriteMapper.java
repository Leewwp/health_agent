package com.diet.mapper;

import com.diet.model.HealthResourceFavoriteRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 独立个人收藏集合持久化边界；不写入 recommend_feedback。 */
@Mapper
public interface HealthResourceFavoriteMapper {

    int insertIgnore(HealthResourceFavoriteRow row);

    int delete(@Param("userId") Long userId, @Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId);

    List<HealthResourceFavoriteRow> page(@Param("userId") Long userId,
                                         @Param("resourceType") String resourceType,
                                         @Param("offset") int offset, @Param("size") int size);

    int count(@Param("userId") Long userId, @Param("resourceType") String resourceType);

    List<String> ids(@Param("userId") Long userId, @Param("resourceType") String resourceType);

    int exists(@Param("userId") Long userId, @Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId);
}
