package com.diet.mapper;

import com.diet.model.PlanWriteRequestRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 计划写请求幂等记录。 */
@Mapper
public interface PlanWriteRequestMapper {
    PlanWriteRequestRow find(@Param("userId") Long userId, @Param("requestId") String requestId);

    int insert(PlanWriteRequestRow row);
}
