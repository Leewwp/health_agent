package com.diet.health.reader.exercise;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.diet.health.module.HealthResource;
import com.diet.health.resource.DbReviewedResourceProvider;
import com.diet.mapper.ExerciseMapper;
import com.diet.mapper.MealMapper;
import com.diet.mapper.RoutineFactMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核动作读取模块契约测试（#64，方案 B）。
 * <p>
 * mock Mapper 验证分页选择 browse/count SQL；raw cardio/body weight/core 与
 * chest/dumbbell/biceps 归一为允许中文集合；未收录部位/器材/肌群不原样透出；
 * 同一 raw row 经浏览读取模型与 DbReviewedResourceProvider 产生一致的部位、器材、难度语义。
 */
class DbReviewedExerciseReaderTest {

    private ExerciseMapper exerciseMapper;
    private JsonService jsonService;
    private DbReviewedExerciseReader reader;

    @BeforeEach
    void setUp() {
        exerciseMapper = mock(ExerciseMapper.class);
        jsonService = new JsonService(new ObjectMapper());
        reader = new DbReviewedExerciseReader(exerciseMapper, jsonService);
    }

    @Test
    void 分页与总数选择对应SQL() {
        when(exerciseMapper.browse(20, 10)).thenReturn(List.of(row(1L)));
        when(exerciseMapper.count()).thenReturn(30);
        assertEquals(1, reader.browse(20, 10).size());
        assertEquals(30, reader.count());
        verify(exerciseMapper).browse(20, 10);
        verify(exerciseMapper).count();
    }

    @Test
    void raw英文值映射到允许中文集合() {
        ExerciseItemRow row = row(1L);
        row.setBodyPart("cardio");
        row.setTargetMuscles("[\"core\", \"shoulders\"]");
        row.setSecondaryMuscles("[\"triceps\", \"feet\"]");
        row.setEquipment("body weight");
        row.setDifficulty("中级");
        row.setCategory("cardio");
        when(exerciseMapper.browse(0, 10)).thenReturn(List.of(row));

        ReviewedExercise exercise = reader.browse(0, 10).get(0);

        assertEquals("全身", exercise.bodyPart(), "cardio 稳定为「全身」");
        assertEquals("全身", exercise.category(), "category 与 bodyPart 同口径归一");
        assertEquals(List.of("核心", "肩"), exercise.targetMuscles());
        assertEquals(List.of("手臂", "腿"), exercise.secondaryMuscles());
        assertEquals("徒手", exercise.equipment());
        assertEquals("进阶", exercise.difficulty(), "中级归一到进阶");
    }

    @Test
    void 未收录原始值不透出() {
        ExerciseItemRow row = row(1L);
        row.setEquipment("kettlebell");
        row.setTargetMuscles("[\"unknown-muscle\"]");
        row.setDifficulty("expert");
        when(exerciseMapper.browse(0, 10)).thenReturn(List.of(row));

        ReviewedExercise exercise = reader.browse(0, 10).get(0);

        assertEquals("", exercise.equipment(), "未收录器材为空串，不透出英文");
        assertTrue(exercise.targetMuscles().isEmpty(), "未收录肌群从用户标签集合过滤");
        assertEquals("", exercise.difficulty(), "未收录难度为空串");
    }

    @Test
    void 只对归一失败的原始值记录告警() {
        Logger logger = (Logger) LoggerFactory.getLogger(DbReviewedExerciseReader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(exerciseMapper.browse(0, 10)).thenReturn(List.of(row(1L)));
            reader.browse(0, 10);
            assertTrue(appender.list.isEmpty(), "合法可归一值不得误报未收录告警");

            appender.list.clear();
            ExerciseItemRow unknown = row(2L);
            unknown.setCategory("unknown-category");
            unknown.setBodyPart("unknown-part");
            unknown.setEquipment("unknown-equipment");
            unknown.setDifficulty("unknown-difficulty");
            when(exerciseMapper.browse(0, 10)).thenReturn(List.of(unknown));
            reader.browse(0, 10);
            assertEquals(4, appender.list.size(), "四个归一失败的单值字段应分别记录告警");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 与Provider同一raw行归一语义一致() {
        ExerciseItemRow row = row(1L);
        when(exerciseMapper.browse(0, 10)).thenReturn(List.of(row));
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(row));
        DbReviewedResourceProvider provider = new DbReviewedResourceProvider(
                exerciseMapper, mock(MealMapper.class), mock(RoutineFactMapper.class), jsonService);

        ReviewedExercise viewed = reader.browse(0, 10).get(0);
        HealthResource resource = provider.exercises().get(0);

        assertEquals(resource.tags().get("primaryBodyPart").get(0), viewed.bodyPart(),
                "Provider 主部位与浏览读取模型必须一致");
        assertEquals(resource.tags().get("equipment").get(0), viewed.equipment(),
                "Provider 器材与浏览读取模型必须一致");
        assertEquals(resource.tags().get("difficulty").get(0), viewed.difficulty(),
                "Provider 难度与浏览读取模型必须一致");
        assertEquals(resource.tags().get("bodyParts"), List.of("胸", "手臂", "肩", "核心"),
                "Provider bodyParts 与浏览靶肌/次肌归一集合一致");
    }

    @Test
    void 空库返回空集合() {
        when(exerciseMapper.browse(0, 10)).thenReturn(List.of());
        when(exerciseMapper.count()).thenReturn(0);
        assertTrue(reader.browse(0, 10).isEmpty());
        assertEquals(0, reader.count());
    }

    @Test
    void 读取模型内嵌集合深不可变() {
        when(exerciseMapper.browse(0, 10)).thenReturn(List.of(row(1L)));
        ReviewedExercise exercise = reader.browse(0, 10).get(0);

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> exercise.aliases().add("别名")),
                () -> assertThrows(UnsupportedOperationException.class, () -> exercise.targetMuscles().add("腿")),
                () -> assertThrows(UnsupportedOperationException.class, () -> exercise.secondaryMuscles().add("腿")),
                () -> assertThrows(UnsupportedOperationException.class, () -> exercise.riskTags().add("风险")),
                () -> assertThrows(UnsupportedOperationException.class, () -> exercise.steps().add("步骤"))
        );
    }

    @Test
    void 只依赖Approved过滤由MapperSQL承担且不串读其他表() {
        when(exerciseMapper.browse(anyInt(), anyInt())).thenReturn(List.of());
        reader.browse(0, 10);
        verify(exerciseMapper).browse(0, 10);
        verify(exerciseMapper, never()).findAllApproved();
    }

    private ExerciseItemRow row(Long id) {
        ExerciseItemRow row = new ExerciseItemRow();
        row.setId(id);
        row.setSourceName("gym-visual-exercises-dataset");
        row.setSourceId(String.valueOf(id));
        row.setSourceVersion("main-2026-08-10");
        row.setName("俯卧撑");
        row.setNameEn("push-up");
        row.setAliases("[\"标准俯卧撑\"]");
        row.setCategory("chest");
        row.setBodyPart("chest");
        row.setTargetMuscles("[\"triceps\"]");
        row.setSecondaryMuscles("[\"triceps\", \"deltoids\", \"core\"]");
        row.setEquipment("body weight");
        row.setDifficulty("入门");
        row.setMovementPattern("推");
        row.setRiskTags("[\"手腕压力\"]");
        row.setAlternativeGroup("g_chest_press");
        row.setReviewStatus("APPROVED");
        row.setPlanReady(true);
        row.setInstructionsZh("平躺，膝盖弯曲，双脚平放在地上。收紧腹肌，慢慢将上半身抬离地面。");
        row.setStepsJson("[\"步骤一：准备姿势\",\"步骤二：完成动作\"]");
        row.setMediaState("NONE");
        row.setMediaCredit("© Gym visual — https://gymvisual.com/");
        return row;
    }
}
