package com.diet.health.reader.exercise;

import com.diet.health.intent.HealthSlotDictionary;
import org.junit.jupiter.api.Test;

import java.util.List;

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
