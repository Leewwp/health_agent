package com.diet.health.session;

import com.diet.exception.DietException;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.PreferenceSignal;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.TrainingTimeWindow;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 健康会话状态：复用 diet_sessions 的序列化往返、新建与 43 号票默认会话/类型化资源引用。 */
class HealthSessionServiceTest {

    private final SessionMapper mapper = mock(SessionMapper.class);
    private final HealthSessionService service = new HealthSessionService(mapper, new ObjectMapper());

    @org.junit.jupiter.api.Test
    void 新旧会话JSON往返保留生命周期确认指纹与未支持偏好() {
        when(mapper.findById("sess-roundtrip", 1L)).thenReturn(null);
        when(mapper.update(any())).thenAnswer(invocation -> {
            // 模拟数据库：update 覆盖同 id 行
            SessionRow row = invocation.getArgument(0);
            stored = row;
            return 1;
        });
        service.save(HealthSessionState.fresh("sess-roundtrip", 1L));
        Map<String, String> lifecycle = Map.of("MEAL", "OPEN", "EXERCISE", "PAUSED");
        com.diet.health.plan.MealPlanBrief brief = new com.diet.health.plan.MealPlanBrief(
                java.time.LocalDate.of(2026, 8, 31), List.of("早餐"), "减脂", "中餐",
                List.of("清淡", "高蛋白"), "快速", List.of("cuisine:中餐"));
        HealthSessionState intended = new HealthSessionState("sess-roundtrip", 1L,
                com.diet.health.enums.HealthPhase.RESPOND, com.diet.health.enums.HealthDomain.MEAL,
                com.diet.health.enums.HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(),
                com.diet.health.plan.PlanBrief.empty(), brief, true, false, 1, lifecycle, "fingerprint-abc");
        service.save(intended);

        when(mapper.findById("sess-roundtrip", 1L)).thenReturn(stored);
        HealthSessionState reloaded = service.loadOrCreate("sess-roundtrip", 1L);
        assertEquals(lifecycle, reloaded.briefLifecycle());
        assertEquals("fingerprint-abc", reloaded.recommendationConfirmationKey());
        assertEquals("中餐", reloaded.mealPlanBrief().cuisine());
        assertEquals(List.of("清淡", "高蛋白"), reloaded.mealPlanBrief().tastePreferences());
        assertEquals("快速", reloaded.mealPlanBrief().convenience());
        assertEquals(List.of("cuisine:中餐"), reloaded.mealPlanBrief().unsupportedPreferences(),
                "未支持偏好在会话重启后保留");
    }

    @org.junit.jupiter.api.Test
    void 旧快照合并保存不能把GENERATED打回OPEN或PAUSED() {
        // 轮开始快照：MEAL OPEN、简报 V1
        when(mapper.findById("sess-gen", 1L)).thenReturn(null);
        when(mapper.update(any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return 1;
        });
        service.save(HealthSessionState.fresh("sess-gen", 1L));
        HealthSessionState original = new HealthSessionState("sess-gen", 1L,
                com.diet.health.enums.HealthPhase.RESPOND, com.diet.health.enums.HealthDomain.MEAL,
                com.diet.health.enums.HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(),
                com.diet.health.plan.PlanBrief.empty(), briefV1(), false, false, 0,
                Map.of("MEAL", "OPEN"), null);
        service.save(original);

        // 并发生成关闭：MEAL → GENERATED
        when(mapper.findByIdForUpdate("sess-gen", 1L)).thenAnswer(invocation -> stored);
        service.markBriefGenerated(1L, "sess-gen", List.of("MEAL"));

        // 旧聊天快照（original，生命周期 OPEN、简报 V1）此时才落库：本轮补充了新简报字段 V2
        HealthSessionState intended = original
                .withMealPlanBrief(briefV1().withOptional(null, List.of("清淡"), null, null));
        service.saveMerged(original, intended);

        when(mapper.findById("sess-gen", 1L)).thenReturn(stored);
        HealthSessionState reloaded = service.loadOrCreate("sess-gen", 1L);
        assertEquals("GENERATED", reloaded.briefLifecycle().get("MEAL"),
                "旧聊天快照不能把 GENERATED 覆盖回 OPEN/PAUSED");
        assertEquals(List.of("清淡"), reloaded.mealPlanBrief().tastePreferences(),
                "聊天补充与生成关闭并发时不得丢失简报字段");
    }

    @org.junit.jupiter.api.Test
    void 合并保存保留未触碰方面的数据库最新值() {
        when(mapper.findById("sess-merge", 1L)).thenReturn(null);
        when(mapper.update(any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return 1;
        });
        HealthSessionState original = new HealthSessionState("sess-merge", 1L,
                com.diet.health.enums.HealthPhase.RESPOND, com.diet.health.enums.HealthDomain.MEAL,
                com.diet.health.enums.HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(),
                com.diet.health.plan.PlanBrief.empty(), briefV1(), false, false, 0,
                Map.of("MEAL", "OPEN"), null);
        service.save(original);

        // 并发方写入：简报 V2 + 新确认指纹
        HealthSessionState concurrent = original.withMealPlanBrief(briefV1().withOptional("川菜", null, null, null))
                .withRecommendationConfirmationKey("concurrent-key");
        service.save(concurrent);

        // 旧快照轮：只更新槽位，未触碰简报与指纹
        HealthSessionState intended = original.withSlots(Map.of("mealTime", List.of("早餐")));
        when(mapper.findByIdForUpdate("sess-merge", 1L)).thenAnswer(invocation -> stored);
        service.saveMerged(original, intended);

        when(mapper.findById("sess-merge", 1L)).thenReturn(stored);
        HealthSessionState reloaded = service.loadOrCreate("sess-merge", 1L);
        assertEquals("川菜", reloaded.mealPlanBrief().cuisine(), "未触碰简报不得被旧快照覆盖");
        assertEquals("concurrent-key", reloaded.recommendationConfirmationKey(), "未触碰指纹保留最新值");
        assertEquals(List.of("早餐"), reloaded.slots().get("mealTime"), "本轮触碰的槽位照常写入");
    }

    private com.diet.health.plan.MealPlanBrief briefV1() {
        return new com.diet.health.plan.MealPlanBrief(
                java.time.LocalDate.of(2026, 8, 31), List.of("早餐", "午餐"), "减脂");
    }

    private SessionRow stored;

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
        row.setLastRecommendations("[{\"type\":\"MEAL\",\"id\":\"5\"},{\"type\":\"ROUTINE\",\"id\":\"R1\"}]");
        when(mapper.findById(any(), any())).thenReturn(row);

        HealthSessionState state = service.loadOrCreate("sess_roundtrip", 7L);
        assertNull(state.domain());
        assertEquals(List.of(new SessionResourceRef("MEAL", "5"), new SessionResourceRef("ROUTINE", "R1")),
                state.lastResources());
        assertEquals(HealthPhase.RESPOND, state.phase());

        when(mapper.update(any())).thenReturn(1);
        HealthSessionState saved = state
                .withIntent(HealthDomain.EXERCISE, HealthTask.RECOMMEND, List.of("ACUTE_SYMPTOMS"))
                .withSlots(Map.of("bodyParts", List.of("胸"), "wakeTime", List.of("07:00")))
                .withPreferenceSignals(List.of(new PreferenceSignal("EXERCISE", "9001", "LIKE")));
        PlanBrief brief = new PlanBrief("增肌", List.of("胸"), List.of("徒手"), "入门",
                LocalDate.of(2026, 8, 24), List.of(DayOfWeek.MONDAY),
                new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)), Map.of(), null, 0, null);
        saved = saved.withPlanBrief(brief);
        service.save(saved);
        verify(mapper).update(any());

        ArgumentCaptor<SessionRow> rowCaptor = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(rowCaptor.capture());
        assertTrue(rowCaptor.getValue().getSlots().contains("planBrief"));

        PlanBrief partial = brief.withProgress("timeWindowEnd", 2, LocalTime.of(17, 0));
        HealthSessionState partialState = saved.withPlanBrief(partial);
        service.save(partialState);
        ArgumentCaptor<SessionRow> partialCaptor = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper, times(2)).update(partialCaptor.capture());
        row.setSlots(partialCaptor.getAllValues().get(1).getSlots());
        when(mapper.findById("sess_roundtrip", 7L)).thenReturn(row);

        HealthSessionState reloaded = service.loadOrCreate("sess_roundtrip", 7L);
        assertEquals("timeWindowEnd", reloaded.planBrief().expectedField());
        assertEquals(2, reloaded.planBrief().failedAttempts());
        assertEquals(LocalTime.of(17, 0), reloaded.planBrief().partialStartTime());
    }

    @Test
    void 旧数字列表兼容读取为无类型遗留引用() {
        SessionRow row = new SessionRow();
        row.setId("sess_legacy");
        row.setUserId(7L);
        row.setPhase("START");
        row.setLastRecommendations("[9001,9002]");
        when(mapper.findById(any(), any())).thenReturn(row);

        HealthSessionState state = service.loadOrCreate("sess_legacy", 7L);
        assertEquals(List.of(SessionResourceRef.legacy("9001"), SessionResourceRef.legacy("9002")),
                state.lastResources());
    }

    @Test
    void 缺省会话按匿名身份稳定派生且互不可猜() {
        when(mapper.findById(any(), any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "sessionSecret", "test-secret");

        HealthSessionState first = service.loadOrCreate(null, 1L);
        HealthSessionState second = service.loadOrCreate(null, 1L);
        HealthSessionState other = service.loadOrCreate(null, 2L);

        assertEquals(first.sessionId(), second.sessionId(), "同一匿名身份缺省会话必须稳定");
        assertNotEquals(first.sessionId(), other.sessionId(), "不同匿名身份不得共享默认会话");
        assertTrue(first.sessionId().startsWith("sess_"));
        assertTrue(first.sessionId().length() <= 64, "默认会话 ID 必须在 64 字符限制内");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mapper, atLeast(2)).findById(captor.capture(), any());
        assertEquals(List.of(first.sessionId(), first.sessionId(), other.sessionId()), captor.getAllValues(),
                "默认会话查询必须使用同一稳定派生 ID");
    }

    @Test
    void 显式sessionId优先于默认会话() {
        when(mapper.findById("sess_explicit", 1L)).thenReturn(null);
        HealthSessionState explicit = service.loadOrCreate("sess_explicit", 1L);
        assertEquals("sess_explicit", explicit.sessionId());
    }

    @Test
    void 并发首次创建默认会话时输方重读恢复且双方sessionId一致() throws Exception {
        ReflectionTestUtils.setField(service, "sessionSecret", "test-secret");
        // 竞态模拟（56 号票）：初始查询一律未命中；两个线程在 insert 处会合，
        // 赢方写入成功，输方抛 DuplicateKeyException，随后用 findByIdForUpdate 重读恢复。
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allInInsert = new CountDownLatch(2);
        AtomicBoolean winner = new AtomicBoolean();
        SessionRow[] stored = new SessionRow[1];
        when(mapper.findById(any(), any())).thenAnswer(invocation -> {
            synchronized (stored) {
                return stored[0];
            }
        });
        when(mapper.findByIdForUpdate(any(), any())).thenAnswer(invocation -> {
            synchronized (stored) {
                return stored[0];
            }
        });
        when(mapper.insert(any())).thenAnswer(invocation -> {
            SessionRow row = invocation.getArgument(0);
            allInInsert.countDown();
            if (!allInInsert.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发栅栏超时");
            }
            synchronized (stored) {
                if (!winner.getAndSet(true)) {
                    stored[0] = row;
                    return 1;
                }
                throw new DuplicateKeyException("Duplicate entry '" + row.getId() + "' for key 'PRIMARY'");
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HealthSessionState> first = pool.submit(() -> {
                start.await();
                return service.loadOrCreate(null, 1L);
            });
            Future<HealthSessionState> second = pool.submit(() -> {
                start.await();
                return service.loadOrCreate(null, 1L);
            });
            start.countDown();
            HealthSessionState a = first.get(5, TimeUnit.SECONDS);
            HealthSessionState b = second.get(5, TimeUnit.SECONDS);
            assertEquals(a.sessionId(), b.sessionId(), "并发首次创建必须返回同一默认会话");
            verify(mapper, times(2)).insert(any());
            verify(mapper, times(1)).findByIdForUpdate(any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 显式sessionId被其他用户占有时仍拒绝() {
        when(mapper.findById(any(), any())).thenReturn(null);
        when(mapper.findByIdForUpdate(any(), any())).thenReturn(null);
        when(mapper.insert(any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'sess_owned' for key 'PRIMARY'"));
        DietException error = assertThrows(DietException.class,
                () -> service.loadOrCreate("sess_owned", 2L));
        assertEquals("会话不存在或无权访问", error.getMessage());
    }
}
