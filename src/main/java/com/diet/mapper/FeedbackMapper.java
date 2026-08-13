package com.diet.mapper;

import com.diet.model.FeedbackRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedbackMapper {

    /** 类型化反馈写入（item_id 保留旧兼容窗口；新健康链路传 NULL；traceId 为空表示旧链路或会话级反馈）。 */
    int insertTyped(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("traceId") String traceId,
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

    /** 按 session 读取旧反馈（trace 为空的旧链路/会话级反馈）；带 trace_id 的新反馈不参与 session 回退归因。 */
    List<FeedbackRow> findBySessions(
            @Param("userId") Long userId,
            @Param("sessionIds") List<String> sessionIds,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    /** 按 traceId 精确读取反馈（#74 评估精确归因，traceId 为用户范围内全局唯一，无需时间窗）。 */
    List<FeedbackRow> findByTraceIds(
            @Param("userId") Long userId,
            @Param("traceIds") List<String> traceIds
    );

    /** 最近 N 条反馈（偏好聚合用，倒序截断）。 */
    List<FeedbackRow> findRecent(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );
}
