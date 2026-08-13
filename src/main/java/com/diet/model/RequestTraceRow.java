package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

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
}
