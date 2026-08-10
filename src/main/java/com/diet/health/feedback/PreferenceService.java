package com.diet.health.feedback;

import com.diet.constants.DietConstants;
import com.diet.health.module.HealthResource;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRow;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 偏好消费服务（17 号票）：读最近 100 条用户反馈聚合 DISLIKE 硬过滤集合与
 * LIKE/FAVORITE/ADOPT 确定性重排集合，由餐食/动作模块在推荐内部消费。
 * 读取失败或不在 Web 请求上下文时确定性降级为空集合，不抛错。
 */
@Service
public class PreferenceService {

    /** 偏好聚合读取的最近反馈条数上限。 */
    private static final int RECENT_LIMIT = 100;

    private final FeedbackMapper feedbackMapper;

    public PreferenceService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /** 对候选应用偏好：DISLIKE 硬过滤，LIKE/FAVORITE/ADOPT 稳定前移（无上下文或读失败时原样返回）。 */
    public List<HealthResource> applyPreference(List<HealthResource> candidates) {
        Long userId = currentUserId();
        if (userId == null || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        return reorder(candidates, preferencesFor(userId));
    }

    /** 聚合最近 100 条反馈（读取异常确定性降级为空）。 */
    PreferenceView preferencesFor(Long userId) {
        try {
            List<FeedbackRow> rows = feedbackMapper.findRecent(userId, RECENT_LIMIT);
            Set<String> excluded = new HashSet<>();
            Set<String> boosted = new HashSet<>();
            for (FeedbackRow row : rows) {
                String type = row.getResourceType();
                String resourceId = row.getResourceId();
                if (type == null || resourceId == null || resourceId.isBlank()) {
                    continue;
                }
                if (!FeedbackConstants.RESOURCE_TYPE_MEAL.equals(type)
                        && !FeedbackConstants.RESOURCE_TYPE_EXERCISE.equals(type)) {
                    continue;
                }
                String key = type + ":" + resourceId;
                if (FeedbackConstants.ACTION_DISLIKE.equals(row.getAction())) {
                    excluded.add(key);
                    boosted.remove(key);
                } else if (FeedbackConstants.ACTION_LIKE.equals(row.getAction())
                        || FeedbackConstants.ACTION_FAVORITE.equals(row.getAction())
                        || FeedbackConstants.ACTION_ADOPT.equals(row.getAction())) {
                    if (!excluded.contains(key)) {
                        boosted.add(key);
                    }
                }
            }
            return new PreferenceView(Set.copyOf(excluded), Set.copyOf(boosted));
        } catch (Exception error) {
            return PreferenceView.empty();
        }
    }

    /** 重排规则：先硬过滤 DISLIKE，再把 LIKE/FAVORITE/ADOPT 移到结果前部，保持稳定顺序。 */
    List<HealthResource> reorder(List<HealthResource> candidates, PreferenceView view) {
        if (view.isEmpty()) {
            return candidates;
        }
        List<HealthResource> kept = new ArrayList<>();
        for (HealthResource candidate : candidates) {
            if (!view.excludedKeys().contains(keyOf(candidate))) {
                kept.add(candidate);
            }
        }
        List<HealthResource> result = new ArrayList<>(kept.size());
        for (HealthResource candidate : kept) {
            if (view.boostedKeys().contains(keyOf(candidate))) {
                result.add(candidate);
            }
        }
        for (HealthResource candidate : kept) {
            if (!view.boostedKeys().contains(keyOf(candidate))) {
                result.add(candidate);
            }
        }
        return result;
    }

    private String keyOf(HealthResource candidate) {
        return candidate.resourceType() + ":" + candidate.resourceId();
    }

    private Long currentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            Object value = servlet.getRequest().getAttribute(DietConstants.USER_ID_ATTRIBUTE);
            if (value instanceof Long userId) {
                return userId;
            }
        }
        return null;
    }

    /** 偏好聚合视图：excludedKeys=DISLIKE 硬过滤键，boostedKeys=LIKE/FAVORITE/ADOPT 重排键（TYPE:ID）。 */
    public record PreferenceView(Set<String> excludedKeys, Set<String> boostedKeys) {
        public static PreferenceView empty() {
            return new PreferenceView(Set.of(), Set.of());
        }

        public boolean isEmpty() {
            return excludedKeys.isEmpty() && boostedKeys.isEmpty();
        }
    }
}
