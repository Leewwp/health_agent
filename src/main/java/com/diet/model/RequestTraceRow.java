package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RequestTraceRow {
    private Long id;
    private String traceId;
    private String requestId;
    private String sessionId;
    private Long userId;
    private String status;
    private Integer eventCount;
    private Long durationMs;
    private String errorMessage;
    private String traceJson;
    private String responseJson;
    private String expectedIntent;
    private String expectedSlots;
    private String expectedClarifyAction;
    /** #73 V9：health-eval-v2 标注契约版本（旧 expected_* 原义保留，不迁移）。 */
    private String evaluationSchemaVersion;
    /** #73 V9：health-eval-v2 结构化 gold 标注 JSON（expectedHealth）。 */
    private String expectedHealthJson;
    private Long labeledBy;
    private LocalDateTime labeledAt;
    private String labelNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** #87 诊断摘要字段：不新增数据库事实列，由 trace_json 事件确定性聚合。 */
    private String diagnosticStatus;
    private Long agentDurationMs;
    private Integer agentCallCount;
    private Integer degradedCount;
    private String tokenStatus;
    private List<String> modelNames;
    private List<String> parseStatuses;
    private List<String> fallbackReasons;
    private List<String> guardResults;
    private List<TraceTimelineEvent> timeline;

    /** Trace 工作台按 stepOrder 展示的安全时间线摘要。 */
    public record TraceTimelineEvent(
            int stepOrder,
            String eventType,
            String phase,
            String agentName,
            String modelName,
            Long latencyMs,
            String result
    ) {
    }
}
