package com.diet.mapper;

import com.diet.model.ExerciseItemRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** exercise_item 表（33 号票审核动作资源）。 */
@Mapper
public interface ExerciseMapper {

    /** 浏览页：全部审核通过动作，按 id 升序分页。 */
    List<ExerciseItemRow> browse(@Param("offset") int offset, @Param("size") int size);

    /** 动作总数（浏览分页用）。 */
    int count();
}
