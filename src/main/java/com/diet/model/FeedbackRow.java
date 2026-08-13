package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FeedbackRow {
    private Long id;
    private Long userId;
    private String sessionId;
    private String traceId;
    private Long itemId;
    private String resourceType;
    private String resourceId;
    private Long planId;
    private Long planItemId;
    private String action;
    private Integer rating;
    private String reason;
    private String source;
    private LocalDateTime createdAt;
}