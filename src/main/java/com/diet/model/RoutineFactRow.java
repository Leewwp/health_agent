package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** routine_fact 表行（33 号票审核作息事实资源，业务键为冻结 ref_id）。 */
@Data
public class RoutineFactRow {
    private Long id;
    private String topic;
    private String factZh;
    private String scope;
    private String source;
    private String sourceVersion;
    private String refId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
