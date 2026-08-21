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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 偏好消费服务（17 号票；#65 两维折叠）：读最近 100 条用户反馈，对每个 TYPE:ID 独立折叠
 * 收藏状态（最新 FAVORITE/UNFAVORITE）与推荐倾向（DISLIKE→NEGATIVE、LIKE/FAVORITE/ADOPT→
 * POSITIVE，UNFAVORITE 只撤销仍有效的 FAVORITE 贡献），由餐食/动作模块在推荐内部消费：
 * DISLIKE/REDUCE_RECOMMENDATION 资源级过滤；LIKE/ADOPT 仅保留旧兼容语义。
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
            FoldedPreference folded = fold(feedbackMapper.findRecent(userId, RECENT_LIMIT));
            return new PreferenceView(folded.excludedKeys(), folded.boostedKeys());
        } catch (Exception error) {
            return PreferenceView.empty();
        }
    }

    /** 收藏状态可观察（#65）：同一折叠器导出当前收藏键集合（TYPE:ID），读取异常降级为空。 */
    public Set<String> favoriteKeysFor(Long userId) {
        try {
            return fold(feedbackMapper.findRecent(userId, RECENT_LIMIT)).favoriteKeys();
        } catch (Exception error) {
            return Set.of();
        }
    }

    /**
     * 两维状态机折叠（#65 冻结矩阵）：输入为 mapper 倒序（created_at DESC/id DESC，最新在前），
     * 折叠前按 key 反转为时间正序。同一 TYPE:ID 独立维护两个维度：
     * <ul>
     *   <li>收藏 favorite：最新 FAVORITE/UNFAVORITE 决定 true/false，无该类事件为 unset；</li>
     *   <li>推荐倾向 affinity：最新的 DISLIKE/LIKE/FAVORITE/ADOPT 事件决定 NEGATIVE/POSITIVE；
     *       UNFAVORITE 只撤销仍有效的 FAVORITE 贡献，被撤销 FAVORITE 曾覆盖的更早 DISLIKE 不恢复（NEUTRAL）。</li>
     * </ul>
     * 最近 100 条是全局读取窗口：窗口内缺少基线时不得另行回查恢复历史状态。
     */
    static FoldedPreference fold(List<FeedbackRow> rows) {
        Map<String, List<FeedbackRow>> byKey = new LinkedHashMap<>();
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
            byKey.computeIfAbsent(type + ":" + resourceId, key -> new ArrayList<>()).add(row);
        }
        Set<String> excluded = new HashSet<>();
        Set<String> boosted = new HashSet<>();
        Set<String> favorites = new HashSet<>();
        for (Map.Entry<String, List<FeedbackRow>> entry : byKey.entrySet()) {
            List<FeedbackRow> chronological = new ArrayList<>(entry.getValue());
            Collections.reverse(chronological);
            State state = foldKey(chronological);
            if (state.affinity == Affinity.NEGATIVE) {
                excluded.add(entry.getKey());
            }
            if (state.affinity == Affinity.POSITIVE) {
                boosted.add(entry.getKey());
            }
            if (Boolean.TRUE.equals(state.favorite)) {
                favorites.add(entry.getKey());
            }
        }
        return new FoldedPreference(Set.copyOf(excluded), Set.copyOf(boosted), Set.copyOf(favorites));
    }

    /** 单个 TYPE:ID 的时间正序折叠（#65 冻结示例矩阵）。 */
    private static State foldKey(List<FeedbackRow> chronological) {
        Affinity affinity = null;
        boolean contributionActive = false;
        Affinity contributionFallback = null;
        Boolean favorite = null;
        for (FeedbackRow row : chronological) {
            switch (row.getAction() == null ? "" : row.getAction()) {
                case FeedbackConstants.ACTION_DISLIKE -> {
                    affinity = Affinity.NEGATIVE;
                    contributionActive = false;
                    contributionFallback = null;
                }
                case FeedbackConstants.ACTION_LIKE, FeedbackConstants.ACTION_ADOPT -> {
                    affinity = Affinity.POSITIVE;
                    contributionActive = false;
                    contributionFallback = null;
                }
                case FeedbackConstants.ACTION_FAVORITE -> {
                    favorite = true;
                }
                case FeedbackConstants.ACTION_UNFAVORITE -> {
                    favorite = false;
                }
                case FeedbackConstants.ACTION_REDUCE_RECOMMENDATION -> {
                    affinity = Affinity.NEGATIVE;
                    contributionActive = false;
                    contributionFallback = null;
                }
                case FeedbackConstants.ACTION_UNDO_REDUCE_RECOMMENDATION -> {
                    affinity = null;
                    contributionActive = false;
                    contributionFallback = null;
                }
                default -> {
                    // 未知 action 不参与折叠
                }
            }
        }
        return new State(affinity, favorite);
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

    /** 推荐倾向两态（NEUTRAL 不进入任何集合）。 */
    private enum Affinity {
        POSITIVE, NEGATIVE
    }

    private record State(Affinity affinity, Boolean favorite) {
    }

    /** 折叠结果：excluded=倾向 NEGATIVE 键，boosted=倾向 POSITIVE 键，favoriteKeys=收藏状态 true 键（TYPE:ID）。 */
    record FoldedPreference(Set<String> excludedKeys, Set<String> boostedKeys, Set<String> favoriteKeys) {
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
