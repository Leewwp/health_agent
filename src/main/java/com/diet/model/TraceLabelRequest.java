package com.diet.model;
import com.diet.enums.ClarifyAction;
import com.diet.enums.Intent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class TraceLabelRequest {
    private Intent expectedIntent;
    private SlotBundle expectedSlots;
    private ClarifyAction expectedClarifyAction;
    private String labelNote;
    /** #73 V9：health-eval-v2 标注契约版本（写 evaluation_schema_version 列）。 */
    private String evaluationSchemaVersion;
    /** #73 V9：health-eval-v2 结构化 gold 标注 JSON 原文（写 expected_health_json 列）。 */
    private String expectedHealthJson;
}
