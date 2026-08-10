package com.diet.health.module;

import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.module.RoutineFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 作息事实模块：关键词命中类别、数据库 topic 命中与结构化事实标识。 */
class RoutineModuleTest {

    private final RoutineModule module = new RoutineModule(new SeedResourceProvider());

    @Test
    void 睡眠关键词命中睡眠事实() {
        var facts = module.lookup("睡多久合适", Map.of());
        assertFalse(facts.isEmpty());
        assertTrue(facts.stream().allMatch(fact -> "睡眠".equals(fact.category())));
        assertTrue(facts.stream().anyMatch(fact -> fact.fact().contains("7-9")));
    }

    @Test
    void 咖啡关键词命中咖啡因事实() {
        var facts = module.lookup("晚上喝咖啡会影响睡眠吗", Map.of());
        assertTrue(facts.stream().anyMatch(fact -> "咖啡因".equals(fact.category())));
    }

    @Test
    void 事实带来源引用() {
        var facts = module.lookup("睡多久合适", Map.of());
        facts.forEach(fact -> {
            assertNotNull(fact.sourceName());
            assertNotNull(fact.factId());
        });
        assertEquals("R1", facts.get(0).factId());
    }

    @Test
    void 事实可转为类型化资源() {
        HealthResource resource = module.toResource(new RoutineFact("R1", "睡眠", "成人每晚 7-9 小时", "来源", "07-09h"));
        assertEquals("ROUTINE", resource.resourceType());
        assertEquals("R1", resource.resourceId());
        assertEquals("来源", resource.sourceName());
    }

    @Test
    void 无关键词命中时返回通用睡眠事实() {
        var facts = module.lookup("随便聊聊", Map.of());
        assertFalse(facts.isEmpty(), "无命中应兜底返回睡眠类通用事实");
        assertTrue(facts.stream().allMatch(fact -> "睡眠".equals(fact.category())));
    }

    @Test
    void 数据库topic事实按关键词命中类别() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.routineFacts()).thenReturn(List.of(
                new RoutineFact("aasm-sleep-minimum", "睡眠时长下限", "健康成人应保证每晚至少 7 小时睡眠", "AASM 共识", "成人 18+"),
                new RoutineFact("blue-light-2-3h", "屏幕蓝光", "睡前 2~3 小时少看亮屏", "Harvard Health", "成人"),
                new RoutineFact("caffeine-6h-cutoff", "咖啡因", "睡前 6 小时不喝咖啡", "Drake 等 2013", "成人"),
                new RoutineFact("wake-regularity", "作息规律", "固定起床时间稳定生物钟", "睡眠卫生指南", "成人")
        ));
        RoutineModule dbModule = new RoutineModule(provider);

        var sleepHits = dbModule.lookup("睡多久合适", Map.of());
        assertFalse(sleepHits.isEmpty());
        assertTrue(sleepHits.stream().anyMatch(fact -> "睡眠时长".equals(fact.category()) || "睡眠时长下限".equals(fact.category())));
        assertEquals("aasm-sleep-minimum", sleepHits.get(0).factId(), "数据库事实应按 id 升序返回");

        var caffeineHits = dbModule.lookup("晚上喝咖啡", Map.of());
        assertTrue(caffeineHits.stream().anyMatch(fact -> "咖啡因".equals(fact.category())));
    }
}
