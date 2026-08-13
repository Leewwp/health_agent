package com.diet.health.feedback;

import com.diet.exception.HealthApiException;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthFeedbackRequest;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.RequestTraceRow;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * 健康反馈服务（41 号票 + #65 + #74）：
 * action 白名单（LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT）；资源类型白名单 MEAL/EXERCISE，
 * 作息事实不参与偏好；资源存在性经 HealthResourceProvider 校验；planId/planItemId
 * 参照周计划 PATCH 的归属 + 当前版本校验，跨用户与资源不匹配一律明确报错且不写入。
 * traceId 非空时（#74）：先按当前用户查 Trace（不存在/sessionId 不匹配 → 404 无权访问），
 * 再要求 resourceType/resourceId 必须出现在该 trace 响应的 displayBlocks 中（否则 400 且不写入），
 * 保证新健康聊天反馈可一对一精确归因，同一会话其他轮次的资源不能冒名顶替。
 */
@Service
public class HealthFeedbackService {

    private static final Set<String> ACTIONS = Set.of(
            FeedbackConstants.ACTION_LIKE,
            FeedbackConstants.ACTION_DISLIKE,
            FeedbackConstants.ACTION_FAVORITE,
            FeedbackConstants.ACTION_UNFAVORITE,
            FeedbackConstants.ACTION_ADOPT
    );
    private static final Set<String> RESOURCE_TYPES = Set.of(
            FeedbackConstants.RESOURCE_TYPE_MEAL,
            FeedbackConstants.RESOURCE_TYPE_EXERCISE
    );

    private final FeedbackMapper feedbackMapper;
    private final WeeklyPlanMapper planMapper;
    private final HealthResourceProvider resourceProvider;
    private final AgentTraceService agentTraceService;
    private final ObjectMapper objectMapper;

    public HealthFeedbackService(FeedbackMapper feedbackMapper, WeeklyPlanMapper planMapper,
                                 HealthResourceProvider resourceProvider,
                                 AgentTraceService agentTraceService, ObjectMapper objectMapper) {
        this.feedbackMapper = feedbackMapper;
        this.planMapper = planMapper;
        this.resourceProvider = resourceProvider;
        this.agentTraceService = agentTraceService;
        this.objectMapper = objectMapper;
    }

    public void save(Long userId, HealthFeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "反馈 sessionId 不能为空");
        }
        String action = request.action();
        if (action == null || !ACTIONS.contains(action)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                    "反馈 action 必须为 LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT");
        }
        String traceId = normalize(request.traceId());
        String resourceType = normalize(request.resourceType());
        String resourceId = normalize(request.resourceId());
        if (FeedbackConstants.RESOURCE_TYPE_ROUTINE.equals(resourceType)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "作息事实不参与偏好反馈");
        }
        if (resourceType != null && !RESOURCE_TYPES.contains(resourceType)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "资源类型必须为 MEAL 或 EXERCISE");
        }
        if ((resourceType == null) != (resourceId == null)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "资源类型与资源 ID 需同时提供或同时省略");
        }
        if (traceId != null) {
            // #74：traceId 归因校验优先于资源库存在性校验，缺失/越权/资源不归属一律拒绝且不写入。
            validateTraceAttribution(userId, traceId, request.sessionId(), resourceType, resourceId);
        } else if (resourceType != null && !exists(resourceType, resourceId)) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "反馈资源不存在");
        }

        WeeklyPlanRow plan = null;
        if (request.planId() != null) {
            plan = planMapper.findPlanById(request.planId(), userId);
            if (plan == null) {
                throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划不存在或无权访问");
            }
        }
        WeeklyPlanItemRow item = null;
        if (request.planItemId() != null) {
            if (plan == null) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "planItemId 需要同时提供 planId");
            }
            item = planMapper.findItemById(request.planItemId());
            if (item == null || !plan.getId().equals(item.getPlanId())
                    || !plan.getCurrentVersion().equals(item.getVersionNo())) {
                throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划项目不存在");
            }
            if (resourceType != null && !item.getResourceType().equals(resourceType)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目与反馈资源类型不匹配");
            }
            if (resourceId != null && !item.getResourceId().equals(resourceId)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目与反馈资源标识不匹配");
            }
            resourceType = item.getResourceType();
            resourceId = item.getResourceId();
            if (FeedbackConstants.RESOURCE_TYPE_ROUTINE.equals(resourceType)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "作息事实不参与偏好反馈");
            }
        }

        String source = normalize(request.source());
        if (source == null) {
            source = FeedbackConstants.SOURCE_HEALTH_CHAT;
        }
        feedbackMapper.insertTyped(userId, request.sessionId(), traceId, null, resourceType, resourceId,
                plan == null ? null : plan.getId(), item == null ? null : item.getId(),
                action, request.rating(), request.reason(), source);
    }

    /**
     * #74 traceId 精确归因校验（校验顺序固定）：
     * 1. 按当前匿名用户查 Trace，不存在 → 404「Trace 不存在或无权访问」（insert 尚未执行）；
     * 2. Trace 的 sessionId 必须与请求 sessionId 一致，不一致同样 404；
     * 3. traceId 非空必须同时提供 resourceType/resourceId，缺失 → 400；
     * 4. 解析 Trace 行 response_json（可能为 null）为 HealthChatResponse 的 displayBlocks，
     *    该类型化资源必须存在其中，否则 400 且不写入。
     */
    private void validateTraceAttribution(Long userId, String traceId, String sessionId,
                                          String resourceType, String resourceId) {
        RequestTraceRow trace = agentTraceService.findByTraceId(userId, traceId);
        if (trace == null) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "Trace 不存在或无权访问");
        }
        if (!Objects.equals(trace.getSessionId(), sessionId)) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "Trace 不存在或无权访问");
        }
        if (resourceType == null || resourceId == null) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                    "traceId 反馈必须同时提供资源类型与资源 ID");
        }
        if (!resourceInTraceResponse(trace, resourceType, resourceId)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "反馈资源不在该 trace 的推荐结果中");
        }
    }

    /** 该类型化资源必须真实存在于该 trace 最终响应的 displayBlocks，防止跨轮次冒名归因。 */
    private boolean resourceInTraceResponse(RequestTraceRow trace, String resourceType, String resourceId) {
        String responseJson = trace.getResponseJson();
        if (responseJson == null || responseJson.isBlank()) {
            return false;
        }
        try {
            HealthChatResponse response = objectMapper.readValue(responseJson, HealthChatResponse.class);
            if (response.displayBlocks() == null) {
                return false;
            }
            return response.displayBlocks().stream().anyMatch(block ->
                    Objects.equals(resourceType, block.resourceType())
                            && Objects.equals(resourceId, block.resourceId()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean exists(String resourceType, String resourceId) {
        if (FeedbackConstants.RESOURCE_TYPE_MEAL.equals(resourceType)) {
            return resourceProvider.mealById(resourceId).isPresent();
        }
        return resourceProvider.exerciseById(resourceId).isPresent();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
