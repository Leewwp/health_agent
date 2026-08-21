package com.diet.mapper;

import com.diet.model.ExerciseItemRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** exercise_item 表（33 号票审核动作资源）。 */
@Mapper
public interface ExerciseMapper {

    /** 动作资料库浏览：审核集与未审核资料均可展示，审核状态随行返回。 */
    List<ExerciseItemRow> browse(@Param("offset") int offset, @Param("size") int size);

    List<ExerciseItemRow> browseFavorite(@Param("userId") Long userId,
                                         @Param("offset") int offset, @Param("size") int size);

    /** 动作资料库总数（浏览分页用）。 */
    int count();

    int countFavorite(@Param("userId") Long userId);

    List<ExerciseItemRow> browseFiltered(@Param("userId") Long userId,
                                         @Param("favoriteOnly") boolean favoriteOnly,
                                         @Param("query") String query,
                                         @Param("bodyPart") String bodyPart,
                                         @Param("equipment") String equipment,
                                         @Param("difficulty") String difficulty,
                                         @Param("movementPattern") String movementPattern,
                                         @Param("offset") int offset, @Param("size") int size);

    int countFiltered(@Param("userId") Long userId,
                      @Param("favoriteOnly") boolean favoriteOnly,
                      @Param("query") String query,
                      @Param("bodyPart") String bodyPart,
                      @Param("equipment") String equipment,
                      @Param("difficulty") String difficulty,
                      @Param("movementPattern") String movementPattern);

    /** 全部审核通过动作（Provider 用，与浏览同审核条件），按 id 升序。 */
    List<ExerciseItemRow> findAllApproved();

    /** 全部动作目录条目（单次推荐用，审核状态随行返回），按目录顺序返回。 */
    List<ExerciseItemRow> findAllCatalog();

    /** 按主键查审核通过动作（反馈校验与组合器用）。 */
    ExerciseItemRow findById(@Param("id") Long id);

    /** 按主键查动作目录条目（目录详情可包含尚未审核通过的动作）。 */
    ExerciseItemRow browseById(@Param("id") Long id);
}
