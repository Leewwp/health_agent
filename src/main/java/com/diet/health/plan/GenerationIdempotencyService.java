package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.session.HealthSessionService;
import com.diet.mapper.PlanWriteRequestMapper;
import com.diet.model.PlanWriteRequestRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 生成写入的耐久幂等与失败恢复（简报补充回路规格 v3.2）。
 * <p>
 * 三个生成入口以既有 {@code health_plan_write_request} 表记录 {@code operation=GENERATE_<scope>}
 * 幂等（requestId 对同一用户全局唯一，记录与 weekly plan/版本快照同一事务提交）。
 * 生成入口先查该记录：命中时校验 scope/session 一致（否则幂等冲突），恢复既有响应，
 * 再只执行一次“补偿式”生命周期回写（已 GENERATED 幂等保持），不重新生成计划；
 * 同一 requestId 被不同 session/scope 使用时返回幂等冲突，不创建新计划。
 * Trace 仅作诊断，不能作为生成幂等唯一来源。
 */
@Service
public class GenerationIdempotencyService {

    private final PlanWriteRequestMapper writeRequestMapper;
    private final HealthSessionService sessionService;
    private final ObjectMapper objectMapper;

    public GenerationIdempotencyService(PlanWriteRequestMapper writeRequestMapper,
                                        HealthSessionService sessionService,
                                        ObjectMapper objectMapper) {
        this.writeRequestMapper = writeRequestMapper;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    /** 命中生成写记录时的恢复结果：原响应所需字段（含原 traceId，保证重放保真）。 */
    public record ReplayedGeneration(Long planId, String traceId, String sessionId, String planScope, PlanView plan) {
    }

    /**
     * 查询并回放生成写记录：未命中返回 null；命中时先补偿生命周期回写（失败抛出 → 请求 5xx，
     * 计划已提交、重试再次补偿），再返回原响应内容。
     */
    public ReplayedGeneration replay(Long userId, String requestId, String operation, String requestSessionId) {
        if (writeRequestMapper == null) {
            return null;
        }
        PlanWriteRequestRow record = writeRequestMapper.find(userId, requestId);
        if (record == null) {
            return null;
        }
        if (!operation.equals(record.getOperation())) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT,
                    "同一 requestId 已用于其他范围的计划生成");
        }
        Map<String, Object> envelope = parseEnvelope(record.getResponseJson());
        String sessionId = text(envelope.get("sessionId"));
        if (requestSessionId != null && !requestSessionId.equals(sessionId)) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT,
                    "同一 requestId 已用于其他会话的计划生成");
        }
        PlanView plan = objectMapper.convertValue(envelope.get("plan"), PlanView.class);
        // 补偿式生命周期回写：计划已提交，只有会话回写待完成；失败 → 5xx，重试经本记录再次补偿
        sessionService.markBriefGenerated(userId, sessionId, scopesFor(operation));
        return new ReplayedGeneration(plan.id(), text(envelope.get("traceId")), sessionId,
                text(envelope.get("planScope")), plan);
    }

    /** GENERATE_<scope> 操作对应的简报生命周期关闭范围；COMPOSITE 同时关闭两侧。 */
    public static List<String> scopesFor(String operation) {
        return switch (operation == null ? "" : operation) {
            case "GENERATE_MEAL" -> List.of("MEAL");
            case "GENERATE_EXERCISE" -> List.of("EXERCISE");
            case "GENERATE_COMPOSITE" -> List.of("MEAL", "EXERCISE");
            default -> List.of();
        };
    }

    private Map<String, Object> parseEnvelope(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new HealthApiException(HealthApiException.CODE_SERVICE_ERROR, "生成幂等响应快照损坏");
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
