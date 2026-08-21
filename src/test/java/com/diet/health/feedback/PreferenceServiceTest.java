package com.diet.health.feedback;

import com.diet.constants.DietConstants;
import com.diet.health.module.HealthResource;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 偏好消费服务（17 号票；#65 两维状态机）：
 * DISLIKE 硬过滤、LIKE/FAVORITE/ADOPT 稳定前移、最新事件覆盖旧事件（正序折叠）、
 * UNFAVORITE 条件撤销 FAVORITE 贡献、收藏状态与推荐倾向分别可观察、
 * 会话级与非法类型不参与、最近 100 条截断、mapper 异常确定性降级。
 * <p>
 * 输入为 mapper 倒序（created_at DESC/id DESC，最新在前），用例按真实 mapper 顺序书写。
 */
class PreferenceServiceTest {

    private final FeedbackMapper mapper = mock(FeedbackMapper.class);
    private final PreferenceService service = new PreferenceService(mapper);

    private FeedbackRow feedback(String action, String resourceType, String resourceId) {
        FeedbackRow row = new FeedbackRow();
        row.setAction(action);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        return row;
    }

    private HealthResource resource(String type, String id) {
        return new HealthResource(type, id, "name-" + id, "PUBLIC", "公共餐食库", null, false, Map.of());
    }

    private List<String> keys(List<HealthResource> resources) {
        return resources.stream().map(item -> item.resourceType() + ":" + item.resourceId()).toList();
    }

    // ---- 冻结状态机矩阵（#65；列表按 mapper 倒序：最新在前） ----

    @Test
    void DISLIKE单独事件为排除() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(
                List.of(feedback("DISLIKE", "MEAL", "3")));
        assertTrue(folded.excludedKeys().contains("MEAL:3"));
        assertTrue(folded.boostedKeys().isEmpty());
        assertTrue(folded.favoriteKeys().isEmpty(), "DISLIKE 不改变收藏状态");
    }

    @Test
    void 正向旧事件提升但FAVORITE只记录收藏不改变排序() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("LIKE", "MEAL", "1"),
                feedback("FAVORITE", "EXERCISE", "9001"),
                feedback("ADOPT", "MEAL", "2")));
        assertTrue(folded.boostedKeys().containsAll(List.of("MEAL:1", "MEAL:2")));
        assertFalse(folded.boostedKeys().contains("EXERCISE:9001"), "收藏不应提升排序");
        assertTrue(folded.excludedKeys().isEmpty());
        assertEquals(Set("EXERCISE:9001"), folded.favoriteKeys(), "只有 FAVORITE 记录收藏状态");
    }

    @Test
    void 最新LIKE覆盖更早DISLIKE为提升非排除() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(
                List.of(feedback("LIKE", "MEAL", "5"), feedback("DISLIKE", "MEAL", "5")));
        assertTrue(folded.boostedKeys().contains("MEAL:5"), "更新的 LIKE 必须清除更早 NEGATIVE");
        assertFalse(folded.excludedKeys().contains("MEAL:5"));
    }

    @Test
    void 最新DISLIKE覆盖更早正向事件为排除非提升() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(
                List.of(feedback("DISLIKE", "MEAL", "5"), feedback("LIKE", "MEAL", "5")));
        assertTrue(folded.excludedKeys().contains("MEAL:5"));
        assertFalse(folded.boostedKeys().contains("MEAL:5"));
    }

    @Test
    void FAVORITE后UNFAVORITE为未收藏且中性() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(
                List.of(feedback("UNFAVORITE", "MEAL", "5"), feedback("FAVORITE", "MEAL", "5")));
        assertFalse(folded.favoriteKeys().contains("MEAL:5"));
        assertFalse(folded.excludedKeys().contains("MEAL:5"));
        assertFalse(folded.boostedKeys().contains("MEAL:5"), "撤销 FAVORITE 后为 NEUTRAL");
    }

    @Test
    void DISLIKE后FAVORITE再UNFAVORITE仍保持减少推荐倾向() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("UNFAVORITE", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5"),
                feedback("DISLIKE", "MEAL", "5")));
        assertFalse(folded.favoriteKeys().contains("MEAL:5"));
        assertTrue(folded.excludedKeys().contains("MEAL:5"), "收藏不改变已有的减少推荐倾向");
        assertFalse(folded.boostedKeys().contains("MEAL:5"));
    }

    @Test
    void DISLIKE后FAVORITE再LIKE再UNFAVORITE仍为提升() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("UNFAVORITE", "MEAL", "5"),
                feedback("LIKE", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5"),
                feedback("DISLIKE", "MEAL", "5")));
        assertFalse(folded.favoriteKeys().contains("MEAL:5"));
        assertTrue(folded.boostedKeys().contains("MEAL:5"), "UNFAVORITE 不能取消独立的 LIKE");
        assertFalse(folded.excludedKeys().contains("MEAL:5"));
    }

    @Test
    void LIKE后FAVORITE再UNFAVORITE仍为提升() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("UNFAVORITE", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5"),
                feedback("LIKE", "MEAL", "5")));
        assertFalse(folded.favoriteKeys().contains("MEAL:5"));
        assertTrue(folded.boostedKeys().contains("MEAL:5"), "撤销 FAVORITE 后独立 LIKE 仍生效");
    }

    @Test
    void FAVORITE后DISLIKE再UNFAVORITE仍为排除() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("UNFAVORITE", "MEAL", "5"),
                feedback("DISLIKE", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5")));
        assertFalse(folded.favoriteKeys().contains("MEAL:5"));
        assertTrue(folded.excludedKeys().contains("MEAL:5"), "UNFAVORITE 不能取消比 FAVORITE 更新的 DISLIKE");
    }

    @Test
    void FAVORITE后UNFAVORITE再DISLIKE为排除() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("DISLIKE", "MEAL", "5"),
                feedback("UNFAVORITE", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5")));
        assertFalse(folded.favoriteKeys().contains("MEAL:5"));
        assertTrue(folded.excludedKeys().contains("MEAL:5"), "UNFAVORITE 之后的新 DISLIKE 正常生效");
    }

    @Test
    void 不同资源与类型互不影响() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("DISLIKE", "MEAL", "5"),
                feedback("FAVORITE", "EXERCISE", "9001")));
        assertTrue(folded.excludedKeys().contains("MEAL:5"));
        assertFalse(folded.boostedKeys().contains("EXERCISE:9001"), "收藏不应提升排序");
        assertTrue(folded.favoriteKeys().contains("EXERCISE:9001"));
        assertFalse(folded.excludedKeys().contains("EXERCISE:9001"));
    }

    // ---- 既有行为保持 ----

    @Test
    void DISLIKE硬过滤且LIKE稳定前移收藏保持原位置() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of(
                feedback("DISLIKE", "MEAL", "3"),
                feedback("LIKE", "MEAL", "5"),
                feedback("FAVORITE", "EXERCISE", "9001")));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);

        assertTrue(view.excludedKeys().contains("MEAL:3"));
        assertTrue(view.boostedKeys().contains("MEAL:5"));
        assertFalse(view.boostedKeys().contains("EXERCISE:9001"));

        List<HealthResource> result = service.reorder(List.of(
                resource("MEAL", "3"), resource("MEAL", "7"), resource("MEAL", "5"),
                resource("EXERCISE", "9002"), resource("EXERCISE", "9001")), view);
        assertEquals(List.of("MEAL:5", "MEAL:7", "EXERCISE:9002", "EXERCISE:9001"), keys(result),
                "DISLIKE 被过滤，LIKE 前移，收藏不改变其余候选顺序");
    }

    @Test
    void 减少推荐可被撤销且不影响收藏状态() {
        PreferenceService.FoldedPreference folded = PreferenceService.fold(List.of(
                feedback("UNDO_REDUCE_RECOMMENDATION", "MEAL", "5"),
                feedback("REDUCE_RECOMMENDATION", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5")));
        assertFalse(folded.excludedKeys().contains("MEAL:5"));
        assertTrue(folded.favoriteKeys().contains("MEAL:5"));
    }

    @Test
    void 会话级与非法类型反馈不参与聚合() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of(
                feedback("LIKE", "MEAL", null),
                feedback("FAVORITE", "ROUTINE", "R1"),
                feedback("DISLIKE", "MEAL", "2")));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);

        assertTrue(view.excludedKeys().contains("MEAL:2"));
        assertEquals(1, view.excludedKeys().size(), "会话级与 ROUTINE 反馈不得进入偏好集合");
        assertTrue(view.boostedKeys().isEmpty());
    }

    @Test
    void mapper异常确定性降级为空集合() {
        when(mapper.findRecent(any(), anyInt())).thenThrow(new RuntimeException("数据库不可用"));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);
        assertTrue(view.isEmpty(), "读取失败必须降级为空集合而不是抛错");
        assertTrue(service.favoriteKeysFor(1L).isEmpty());
    }

    @Test
    void 聚合读取最近100条反馈() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of());
        service.preferencesFor(1L);
        verify(mapper).findRecent(eq(1L), eq(100));
    }

    @Test
    void favoriteKeysFor与推荐倾向同一折叠器() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of(
                feedback("UNFAVORITE", "MEAL", "5"),
                feedback("FAVORITE", "MEAL", "5"),
                feedback("FAVORITE", "EXERCISE", "9001")));
        assertEquals(Set("EXERCISE:9001"), service.favoriteKeysFor(1L),
                "收藏初始化必须使用同一折叠器，不增加第二套状态规则");
    }

    @Test
    void 无Web请求上下文时偏好消费原样返回() {
        List<HealthResource> candidates = List.of(resource("MEAL", "5"));
        assertEquals(candidates, service.applyPreference(candidates), "无请求上下文时确定性透传");
    }

    @Test
    void 有Web请求上下文时应用偏好() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DietConstants.USER_ID_ATTRIBUTE, 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(mapper.findRecent(1L, 100)).thenReturn(List.of(feedback("DISLIKE", "MEAL", "3")));
            List<HealthResource> result = service.applyPreference(
                    List.of(resource("MEAL", "3"), resource("MEAL", "5")));
            assertEquals(List.of("MEAL:5"), keys(result));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static java.util.Set<String> Set(String value) {
        return java.util.Set.of(value);
    }
}
