package com.diet.skills;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skills Registry 校验测试（M5 #50）。
 * 验证：合法 manifest 加载并按 name 排序、稳定 URI 查询、非法 Schema 拒绝、
 * 未知工具拒绝、重复 name/version 拒绝、非对象 manifest 拒绝。
 */
class SkillsRegistryTest {

    private static final String VALID =
            "name: meal-recommendation\n" +
            "version: 1.0.0\n" +
            "description: 餐食推荐\n" +
            "input_schema:\n" +
            "  type: object\n" +
            "output_schema:\n" +
            "  type: object\n" +
            "allowed_tools:\n" +
            "  - search_meals\n" +
            "risk_level: low\n";

    @Test
    void 合法manifest加载并按name排序() {
        SkillsRegistry registry = new SkillsRegistry(List.of(
                manifest("health-target-calculation", "2.0.0", "calculate_targets"),
                manifest("meal-recommendation", "1.0.0", "search_meals")));

        assertEquals(List.of("health-target-calculation", "meal-recommendation"),
                registry.skills().stream().map(SkillManifest::name).toList(), "必须按 name 升序");
        Optional<SkillManifest> meal = registry.byUri("skill://meal-recommendation");
        assertTrue(meal.isPresent());
        assertEquals("1.0.0", meal.get().version());
        assertEquals(List.of("search_meals"), meal.get().allowedTools());
        assertTrue(registry.rawContent("skill://meal-recommendation").isPresent());
        assertTrue(registry.rawContent("skill://unknown").isEmpty());
    }

    @Test
    void 非法Schema缺少type被拒绝() {
        String bad = VALID.replace("  type: object\n", "  description: 缺 type\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SkillsRegistry(List.of(bad)));
        assertTrue(e.getMessage().contains("type"), "错误信息应指出缺失字段，实际 " + e.getMessage());
    }

    @Test
    void 未知工具被拒绝() {
        String bad = VALID.replace("  - search_meals\n", "  - delete_database\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SkillsRegistry(List.of(bad)));
        assertTrue(e.getMessage().contains("越界"), "错误信息应指出工具越界，实际 " + e.getMessage());
    }

    @Test
    void 重复name被拒绝() {
        String second = VALID.replace("meal-recommendation", "meal-recommendation");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SkillsRegistry(List.of(VALID, second)));
        assertTrue(e.getMessage().contains("重复"), "稳定 URI 必须唯一，实际 " + e.getMessage());
    }

    @Test
    void 同名不同版本同样被拒绝() {
        String otherVersion = VALID.replace("version: 1.0.0", "version: 2.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> new SkillsRegistry(List.of(VALID, otherVersion)),
                "稳定 URI 以 name 为键，同名不同版本也必须拒绝");
    }

    @Test
    void 空清单被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SkillsRegistry(List.of()));
    }

    @Test
    void 非YAML对象被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SkillsRegistry(List.of("- just\n- a\n- list\n")));
    }

    @Test
    void 非法riskLevel被拒绝() {
        String bad = VALID.replace("risk_level: low", "risk_level: extreme");
        assertThrows(IllegalArgumentException.class, () -> new SkillsRegistry(List.of(bad)));
    }

    private String manifest(String name, String version, String tool) {
        return "name: " + name + "\n" +
                "version: " + version + "\n" +
                "description: " + name + " 描述\n" +
                "input_schema:\n" +
                "  type: object\n" +
                "output_schema:\n" +
                "  type: object\n" +
                "allowed_tools:\n" +
                "  - " + tool + "\n" +
                "risk_level: low\n";
    }
}
