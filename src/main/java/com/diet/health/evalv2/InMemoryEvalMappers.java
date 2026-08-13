package com.diet.health.evalv2;

import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.SessionMapper;
import com.diet.model.RequestTraceRow;
import com.diet.model.SessionMessageRow;
import com.diet.model.SessionRow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DETERMINISTIC_FIXTURE 专用内存 Mapper（#73）：无 MySQL/Qdrant/API key 也可运行。
 * <p>
 * 会话与 Trace 全部驻留内存：会话支持多轮续接，Trace 按 traceId/requestId/sessionId 查询，
 * 供评估器读取本轮结构化事实。仅用于评估 runner 组装隔离编排器，不进入生产上下文。
 */
public final class InMemoryEvalMappers {

    private InMemoryEvalMappers() {
    }

    /** 内存会话 Mapper：按 (sessionId, userId) 存储，支撑多轮样本续接。 */
    public static final class InMemorySessionMapper implements SessionMapper {
        private final Map<String, SessionRow> rows = new LinkedHashMap<>();
        private final List<SessionMessageRow> messages = new ArrayList<>();

        @Override
        public int insert(SessionRow row) {
            rows.put(key(row.getId(), row.getUserId()), row);
            return 1;
        }

        @Override
        public SessionRow findById(String sessionId, Long userId) {
            return rows.get(key(sessionId, userId));
        }

        @Override
        public SessionRow findByIdForUpdate(String sessionId, Long userId) {
            return rows.get(key(sessionId, userId));
        }

        @Override
        public int update(SessionRow row) {
            rows.put(key(row.getId(), row.getUserId()), row);
            return 1;
        }

        @Override
        public int insertMessage(String sessionId, String role, String content, String intent, String traceId) {
            return 1;
        }

        @Override
        public List<SessionMessageRow> listRecentMessages(String sessionId, Long userId, int limit) {
            return List.of();
        }

        private String key(String sessionId, Long userId) {
            return sessionId + "#" + userId;
        }
    }

    /** 内存 Trace Mapper：完整实现查询语义，供评估 runner 读取 fixture 产生的 Trace。 */
    public static final class InMemoryAgentTraceMapper implements AgentTraceMapper {
        private final List<RequestTraceRow> rows = new ArrayList<>();

        @Override
        public int insert(RequestTraceRow row) {
            rows.add(row);
            return 1;
        }

        @Override
        public RequestTraceRow findByRequestId(Long userId, String sessionId, String requestId) {
            return rows.stream()
                    .filter(row -> row.getUserId().equals(userId))
                    .filter(row -> row.getRequestId() != null && row.getRequestId().equals(requestId))
                    .filter(row -> sessionId == null || sessionId.isBlank()
                            || (row.getSessionId() != null && row.getSessionId().equals(sessionId)))
                    .reduce((first, second) -> second)
                    .orElse(null);
        }

        @Override
        public RequestTraceRow findByTraceId(Long userId, String traceId) {
            return rows.stream()
                    .filter(row -> row.getUserId().equals(userId))
                    .filter(row -> row.getTraceId() != null && row.getTraceId().equals(traceId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<RequestTraceRow> findBySessionId(Long userId, String sessionId, int limit) {
            return rows.stream()
                    .filter(row -> row.getUserId().equals(userId))
                    .filter(row -> row.getSessionId() != null && row.getSessionId().equals(sessionId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RequestTraceRow> findByTimeRange(Long userId, LocalDateTime startAt, LocalDateTime endAt,
                                                     boolean onlyUnlabeled, int limit) {
            return rows.stream()
                    .filter(row -> row.getUserId().equals(userId))
                    .filter(row -> row.getCreatedAt() != null
                            && !row.getCreatedAt().isBefore(startAt) && row.getCreatedAt().isBefore(endAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int updateLabel(Long userId, String traceId, String expectedIntent, String expectedSlots,
                               String expectedClarifyAction, Long labeledBy, String labelNote,
                               String evaluationSchemaVersion, String expectedHealthJson) {
            return 1;
        }
    }
}
