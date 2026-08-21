package com.diet.health.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 健康会话资源历史的有界累计契约。 */
class HealthSessionStateTest {

    @Test
    void 同类资源连续推荐最多保留最近50条且去重() {
        HealthSessionState state = HealthSessionState.fresh("sess", 1L);
        List<SessionResourceRef> refs = java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(id -> new SessionResourceRef("MEAL", String.valueOf(id)))
                .toList();

        HealthSessionState saved = state.appendLastResources(refs);

        assertEquals(HealthSessionState.MAX_RESOURCE_HISTORY, saved.lastResources().size());
        assertEquals("11", saved.lastResources().get(0).id());
        assertEquals("60", saved.lastResources().get(49).id());
    }

    @Test
    void 跨领域资源共享容量但排除按类型隔离() {
        HealthSessionState state = HealthSessionState.fresh("sess", 1L)
                .appendLastResources(List.of(
                        new SessionResourceRef("MEAL", "M1"),
                        new SessionResourceRef("EXERCISE", "E1")));

        assertEquals(List.of("M1"), state.excludeIdsFor("MEAL"));
        assertEquals(List.of("E1"), state.excludeIdsFor("EXERCISE"));
    }
}
