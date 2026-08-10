package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.mapper.ExerciseMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动作浏览服务测试（33 号票）。
 * 接缝：ExerciseBrowseService + mock ExerciseMapper。验证分页参数校验、
 * 偏移计算、plan_ready/风险标签/替代组/媒体署名等元数据映射。
 */
class ExerciseBrowseServiceTest {

    private ExerciseMapper exerciseMapper;
    private ExerciseBrowseService service;

    @BeforeEach
    void setUp() {
        exerciseMapper = mock(ExerciseMapper.class);
        service = new ExerciseBrowseService(exerciseMapper, new JsonService(new ObjectMapper()));
    }

    @Test
    void 分页参数校验_page小于1抛参数错误() {
        assertThrows(DietException.class, () -> service.browse(0, 20));
        assertThrows(DietException.class, () -> service.browse(-3, 20));
    }

    @Test
    void 分页参数校验_size超过50或小于1抛参数错误() {
        assertThrows(DietException.class, () -> service.browse(1, 51));
        assertThrows(DietException.class, () -> service.browse(1, 0));
    }

    @Test
    void 分页偏移按page计算() {
        service.browse(1, 50);
        verify(exerciseMapper).browse(0, 50);
        service.browse(4, 20);
        verify(exerciseMapper).browse(60, 20);
    }

    @Test
    void 空数据返回空列表() {
        when(exerciseMapper.browse(0, 20)).thenReturn(List.of());
        when(exerciseMapper.count()).thenReturn(0);
        PagedResponse<ExerciseBrowseItem> response = service.browse(1, 20);
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.total());
    }

    @Test
    void 动作元数据映射完整() {
        when(exerciseMapper.browse(0, 20)).thenReturn(List.of(row()));
        when(exerciseMapper.count()).thenReturn(1);
        ExerciseBrowseItem item = service.browse(1, 20).items().get(0);

        assertEquals(9001L, item.id());
        assertEquals("俯卧撑", item.name());
        assertEquals("push-up", item.nameEn());
        assertEquals(List.of("标准俯卧撑"), item.aliases());
        assertEquals("胸", item.bodyPart());
        assertEquals("徒手", item.equipment());
        assertEquals("入门", item.difficulty());
        assertEquals("推", item.movementPattern());
        assertEquals(List.of("手腕压力"), item.riskTags());
        assertEquals("g_chest_press", item.alternativeGroup());
        assertTrue(item.planReady());
        assertEquals("APPROVED", item.reviewStatus());
        assertTrue(item.steps().size() >= 2);
        assertTrue(item.instructionsZh().length() > 10);
        assertEquals("NONE", item.mediaState());
        assertTrue(item.mediaCredit().contains("Gym visual"));
        assertEquals("gym-visual-exercises-dataset", item.sourceName());
        assertEquals("0662", item.sourceId());
    }

    private ExerciseItemRow row() {
        ExerciseItemRow row = new ExerciseItemRow();
        row.setId(9001L);
        row.setSourceName("gym-visual-exercises-dataset");
        row.setSourceId("0662");
        row.setSourceVersion("main-2026-08-10");
        row.setName("俯卧撑");
        row.setNameEn("push-up");
        row.setAliases("[\"标准俯卧撑\"]");
        row.setCategory("chest");
        row.setBodyPart("胸");
        row.setTargetMuscles("[\"胸\"]");
        row.setSecondaryMuscles("[]");
        row.setEquipment("徒手");
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
