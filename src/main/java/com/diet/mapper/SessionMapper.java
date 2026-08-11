package com.diet.mapper;

import com.diet.model.SessionMessageRow;
import com.diet.model.SessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SessionMapper {
    int insert(SessionRow row);

    SessionRow findById(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /** 按 id + 归属查询会话并锁定行（56 号票：并发默认会话竞态输方恢复时的最新已提交重读）。 */
    SessionRow findByIdForUpdate(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    int update(SessionRow row);

    int insertMessage(
            @Param("sessionId") String sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("intent") String intent,
            @Param("traceId") String traceId
    );

    List<SessionMessageRow> listRecentMessages(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("limit") int limit
    );
}




