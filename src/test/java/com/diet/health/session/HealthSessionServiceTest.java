package com.diet.health.session;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.PreferenceSignal;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 健康会话状态：复用 diet_sessions 的序列化往返与新建。 */
class HealthSessionServiceTest {

    private final SessionMapper mapper = mock(SessionMapper.class);
    private final HealthSessionService service = new HealthSessionService(mapper, new ObjectMapper());

    @Test
    void 新会话先insert再update() {
        when(mapper.findById(any(), any())).thenReturn(null);
        HealthSessionState created = service.loadOrCreate("sess_new", 1L);
        assertEquals("sess_new", created.sessionId());
        assertEquals(HealthPhase.START, created.phase());

        when(mapper.update(any())).thenReturn(1);
        service.save(created.withPhase(HealthPhase.RESPOND).withIntent(HealthDomain.EXERCISE, HealthTask.RECOMMEND, List.of()));
        verify(mapper).update(any());
    }

    @Test
    void 序列化往返保留槽位意图与偏好信号() {
        SessionRow row = new SessionRow();
        row.setId("sess_roundtrip");
        row.setUserId(7L);
        row.setPhase("RESPOND");
        row.setLastRecommendations("[9001,9002]");
        when(mapper.findById(any(), any())).thenReturn(row);

        HealthSessionState state = service.loadOrCreate("sess_roundtrip", 7L);
        assertNull(state.domain());
        assertEquals(List.of(9001L, 9002L), state.lastResourceIds());
        assertEquals(HealthPhase.RESPOND, state.phase());

        when(mapper.update(any())).thenReturn(1);
        HealthSessionState saved = state
                .withIntent(HealthDomain.EXERCISE, HealthTask.RECOMMEND, List.of("ACUTE_SYMPTOMS"))
                .withSlots(Map.of("bodyParts", List.of("胸"), "wakeTime", List.of("07:00")))
                .withPreferenceSignals(List.of(new PreferenceSignal("EXERCISE", "9001", "LIKE")));
        service.save(saved);
        verify(mapper).update(any());
    }
}
