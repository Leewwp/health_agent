package com.diet.mapper;

import com.diet.model.FeedbackRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedbackMapper {

    /** 类型化反馈写入（item_id 保留旧兼容窗口；新健康链路传 NULL）。 */
    int insertTyped(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("itemId") Long itemId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("planId") Long planId,
            @Param("planItemId") Long planItemId,
            @Param("action") String action,
            @Param("rating") Integer rating,
            @Param("reason") String reason,
            @Param("source") String source
    );

    List<FeedbackRow> findBySessions(
            @Param("userId") Long userId,
            @Param("sessionIds") List<String> sessionIds,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    /** 最近 N 条反馈（偏好聚合用，倒序截断）。 */
    List<FeedbackRow> findRecent(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );
}
