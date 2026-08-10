package com.diet.mapper;

import com.diet.model.RoutineFactRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** routine_fact 表（40 号票：作息事实 Provider 读取入口）。 */
@Mapper
public interface RoutineFactMapper {

    /** 全部审核作息事实，按 id 升序。 */
    List<RoutineFactRow> selectAll();

    /** 按冻结业务 ref_id 查事实。 */
    RoutineFactRow selectByRefId(@Param("refId") String refId);

    /** 按主题关键词模糊匹配（topic LIKE），按 id 升序；为 43 号作息关键词命中打基础。 */
    List<RoutineFactRow> selectByTopicLike(@Param("topic") String topic);
}
