package com.diet.health.reader.exercise;

import com.diet.health.intent.HealthSlotDictionary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 动作词汇归一共享模块契约（#64）：原始英文值 → 健身槽位中文集合，未收录值过滤。 */
class ExerciseVocabularyTest {

    @Test
    void 部位字典映射固定() {
        assertEquals("胸", ExerciseVocabulary.partZh("chest"));
        assertEquals("核心", ExerciseVocabulary.partZh("waist"));
        assertEquals("核心", ExerciseVocabulary.partZh("core"));
        assertEquals("背", ExerciseVocabulary.partZh("back"));
        assertEquals("腿", ExerciseVocabulary.partZh("upper legs"));
        assertEquals("腿", ExerciseVocabulary.partZh("lower legs"));
        assertEquals("腿", ExerciseVocabulary.partZh("quadriceps"));
        assertEquals("手臂", ExerciseVocabulary.partZh("upper arms"));
        assertEquals("手臂", ExerciseVocabulary.partZh("biceps"));
        assertEquals("肩", ExerciseVocabulary.partZh("shoulders"));
        assertEquals("臀", ExerciseVocabulary.partZh("glutes"));
    }

    @Test
    void cardio稳定为全身() {
        assertEquals("全身", ExerciseVocabulary.partZh("cardio"));
    }

    @Test
    void 器材字典映射固定() {
        assertEquals("徒手", ExerciseVocabulary.equipmentZh("body weight"));
        assertEquals("哑铃", ExerciseVocabulary.equipmentZh("dumbbell"));
        assertEquals("弹力带", ExerciseVocabulary.equipmentZh("band"));
        assertEquals("壶铃", ExerciseVocabulary.equipmentZh("kettlebell"));
    }

    @Test
    void 完整目录器材都归一到可选择槽位() {
        Map<String, String> equipment = Map.ofEntries(
                Map.entry("assisted", "器械"),
                Map.entry("band", "弹力带"),
                Map.entry("barbell", "杠铃"),
                Map.entry("body weight", "徒手"),
                Map.entry("bosu ball", "器械"),
                Map.entry("cable", "器械"),
                Map.entry("dumbbell", "哑铃"),
                Map.entry("elliptical machine", "器械"),
                Map.entry("ez barbell", "杠铃"),
                Map.entry("hammer", "器械"),
                Map.entry("kettlebell", "壶铃"),
                Map.entry("leverage machine", "器械"),
                Map.entry("medicine ball", "器械"),
                Map.entry("olympic barbell", "杠铃"),
                Map.entry("resistance band", "弹力带"),
                Map.entry("roller", "器械"),
                Map.entry("rope", "器械"),
                Map.entry("skierg machine", "器械"),
                Map.entry("sled machine", "器械"),
                Map.entry("smith machine", "器械"),
                Map.entry("stability ball", "器械"),
                Map.entry("stationary bike", "器械"),
                Map.entry("stepmill machine", "器械"),
                Map.entry("tire", "器械"),
                Map.entry("trap bar", "杠铃"),
                Map.entry("upper body ergometer", "器械"),
                Map.entry("weighted", "器械"),
                Map.entry("wheel roller", "器械")
        );
        equipment.forEach((raw, expected) -> assertEquals(expected, ExerciseVocabulary.equipmentZh(raw), raw));
        assertTrue(HealthSlotDictionary.FITNESS_OPTIONS.get("equipment").containsAll(equipment.values()));
    }

    @Test
    void 完整目录肌群都归一到可用部位() {
        Map<String, String> muscles = Map.ofEntries(
                Map.entry("abdominals", "核心"),
                Map.entry("ankle stabilizers", "腿"),
                Map.entry("ankles", "腿"),
                Map.entry("brachialis", "手臂"),
                Map.entry("calves", "腿"),
                Map.entry("grip muscles", "手臂"),
                Map.entry("hands", "手臂"),
                Map.entry("inner thighs", "腿"),
                Map.entry("lats", "背"),
                Map.entry("latissimus dorsi", "背"),
                Map.entry("lower abs", "核心"),
                Map.entry("rear deltoids", "肩"),
                Map.entry("rhomboids", "背"),
                Map.entry("rotator cuff", "肩"),
                Map.entry("shins", "腿"),
                Map.entry("soleus", "腿"),
                Map.entry("sternocleidomastoid", "颈部"),
                Map.entry("upper chest", "胸"),
                Map.entry("trapezius", "背"),
                Map.entry("wrist extensors", "手臂"),
                Map.entry("wrist flexors", "手臂"),
                Map.entry("wrists", "手臂")
        );
        muscles.forEach((raw, expected) -> assertEquals(expected, ExerciseVocabulary.partZh(raw), raw));
        assertTrue(HealthSlotDictionary.FITNESS_OPTIONS.get("bodyParts").containsAll(muscles.values()));
        assertEquals("颈部", ExerciseVocabulary.partZh("neck"));
    }

    @Test
    void 难度中级归一到进阶() {
        assertEquals("入门", ExerciseVocabulary.difficultyZh("入门"));
        assertEquals("进阶", ExerciseVocabulary.difficultyZh("中级"));
        assertEquals("进阶", ExerciseVocabulary.difficultyZh("进阶"));
        assertEquals("挑战", ExerciseVocabulary.difficultyZh("挑战"));
    }

    @Test
    void 未收录原始值返回空串不透出() {
        assertEquals("", ExerciseVocabulary.partZh("kettlebell fly"));
        assertEquals("", ExerciseVocabulary.equipmentZh("cable machine"));
        assertEquals("", ExerciseVocabulary.difficultyZh("expert"));
    }

    @Test
    void 归一化列表去重过滤且保持顺序() {
        assertEquals(List.of("胸", "手臂", "核心"),
                ExerciseVocabulary.normalizeParts(List.of("chest", "triceps", "core", "chest", "unknown-muscle")));
    }

    @Test
    void 未收录值可诊断列表() {
        assertEquals(List.of("unknown-muscle"),
                ExerciseVocabulary.unrepresentedParts(List.of("chest", "unknown-muscle")));
        assertTrue(ExerciseVocabulary.unrepresentedParts(List.of("chest", "triceps")).isEmpty());
    }

    @Test
    void 合法值集合与槽位字典对齐() {
        assertTrue(HealthSlotDictionary.FITNESS_OPTIONS.get("bodyParts").containsAll(
                List.of("胸", "背", "腿", "肩", "手臂", "核心", "臀", "全身")));
        assertTrue(HealthSlotDictionary.FITNESS_OPTIONS.get("equipment").contains("徒手"));
        assertFalse(HealthSlotDictionary.FITNESS_OPTIONS.get("equipment").contains("body weight"));
    }
}
