package com.diet.agent.contract;

import com.diet.agent.invoker.AgentInvocationException;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.AgentTimeoutException;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent 契约模块：统一 Prompt/契约版本、输入输出 DTO、超时、JSON/Schema/枚举/候选 ID 校验、Trace 和失败分类。
 * <p>
 * 流程：配置检查 → 调用（分类超时/上游失败）→ JSON 解析 → Schema/枚举校验 → 候选 ID 白名单校验 → 记录 Trace。
 * 任何失败都返回带 {@code fallbackReason} 的降级结果，调用方必须使用确定性模板继续流程。
 */
@Component
public class AgentContractModule {

    /** JSON 解析器：把根节点转成类型化结果，校验失败抛 {@link AgentFailureException}。 */
    @FunctionalInterface
    public interface TypedParser<T> {
        T parse(JsonNode root) throws AgentFailureException;
    }

    /** 一次受契约约束的 Agent 调用。 */
    public record AgentContractRequest<T>(
            String agentRole,
            String modelName,
            String promptVersion,
            String contractVersion,
            String promptText,
            Duration timeout,
            TypedParser<T> parser,
            List<String> allowedCandidateIds,
            Function<T, List<String>> candidateIdExtractor
    ) {
    }

    /** 契约调用结果：解析成功返回 typed 值，失败返回降级信息。 */
    public record ContractResult<T>(T value, boolean parsed, AgentFailureType failureType, String fallbackReason, long latencyMs) {

        public static <T> ContractResult<T> ok(T value, long latencyMs) {
            return new ContractResult<>(value, true, null, null, latencyMs);
        }

        public static <T> ContractResult<T> degraded(AgentFailureType type, long latencyMs, String detail) {
            String reason = detail == null || detail.isBlank() ? type.name() : type.name() + ": " + detail;
            return new ContractResult<>(null, false, type, reason, latencyMs);
        }
    }

    private final AgentInvoker agentInvoker;
    private final LlmJsonService llmJsonService;
    private final AgentTraceService agentTraceService;

    public AgentContractModule(
            @Qualifier("healthAgentInvoker") AgentInvoker agentInvoker,
            LlmJsonService llmJsonService,
            AgentTraceService agentTraceService
    ) {
        this.agentInvoker = agentInvoker;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
    }

    /**
     * 执行一次受契约约束的 Agent 调用，始终返回确定性结果。
     */
    public <T> ContractResult<T> call(AgentContractRequest<T> request) {
        long startedAt = System.nanoTime();
        if (!agentInvoker.configured()) {
            return degraded(request, AgentFailureType.MISSING_CONFIG, null, "API key 未配置");
        }

        AgentInvoker.AgentInvocationResult raw;
        try {
            raw = agentInvoker.invoke(new AgentInvoker.AgentInvocation(
                    request.agentRole(), request.modelName(), request.promptText(), request.timeout()));
        } catch (AgentTimeoutException error) {
            return degraded(request, AgentFailureType.TIMEOUT, elapsedMs(startedAt), "调用超时");
        } catch (AgentInvocationException error) {
            return degraded(request, AgentFailureType.UPSTREAM_UNAVAILABLE, elapsedMs(startedAt), error.getMessage());
        } catch (RuntimeException error) {
            return degraded(request, AgentFailureType.UPSTREAM_UNAVAILABLE, elapsedMs(startedAt), error.getMessage());
        }

        JsonNode root;
        try {
            root = llmJsonService.parseObject(raw.text());
        } catch (RuntimeException error) {
            return degraded(request, AgentFailureType.INVALID_JSON, raw.latencyMs(), "输出不是合法 JSON");
        }

        T value;
        try {
            value = request.parser().parse(root);
        } catch (AgentFailureException error) {
            return degraded(request, error.type(), raw.latencyMs(), error.getMessage());
        } catch (RuntimeException error) {
            return degraded(request, AgentFailureType.SCHEMA_VIOLATION, raw.latencyMs(), error.getMessage());
        }

        if (request.allowedCandidateIds() != null && request.candidateIdExtractor() != null) {
            List<String> referenced = request.candidateIdExtractor().apply(value);
            if (referenced != null && !request.allowedCandidateIds().containsAll(referenced)) {
                return degraded(request, AgentFailureType.CANDIDATE_VIOLATION, raw.latencyMs(), "引用输入候选之外的资源 ID");
            }
        }

        recordTrace(request, raw, "PARSED", null, value);
        return ContractResult.ok(value, raw.latencyMs());
    }

    private <T> ContractResult<T> degraded(AgentContractRequest<T> request, AgentFailureType type, Long latencyMs, String detail) {
        recordTrace(request, null, "DEGRADED", type.name() + (detail == null ? "" : ": " + detail), null);
        return ContractResult.degraded(type, latencyMs == null ? 0 : latencyMs, detail);
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** 记录 AGENT_CALL Trace 事件，payload 携带 Prompt/契约版本、解析状态和降级原因。 */
    private void recordTrace(AgentContractRequest<?> request, AgentInvoker.AgentInvocationResult raw, String parseStatus, String fallbackReason, Object value) {
        Map<String, Object> input = Map.of(
                "promptVersion", request.promptVersion(),
                "contractVersion", request.contractVersion(),
                "promptText", request.promptText()
        );
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("parseStatus", parseStatus);
        output.put("fallbackReason", fallbackReason);
        if (value != null) {
            output.put("result", value);
        }
        agentTraceService.recordAgentEvent(
                "AGENT_CALL", "AGENT",
                request.agentRole(), request.modelName(),
                input, output,
                raw == null ? null : raw.latencyMs()
        );
    }
}
