package com.diet.health.module;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 作息事实模块：关键词命中类别与结构化事实标识。 */
class RoutineModuleTest {

    private final RoutineModule module = new RoutineModule();

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
        assertEquals("FACT", resource.resourceType());
        assertEquals("R1", resource.resourceId());
        assertEquals("来源", resource.sourceName());
    }
}
