package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.exception.HealthApiException;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.reader.exercise.ReviewedExercise;
import com.diet.health.reader.exercise.ReviewedExerciseReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 动作浏览服务测试（33 号票；#64 迁移到审核动作读取模块 seam）。
 * 接缝：ExerciseBrowseService + mock ReviewedExerciseReader（方案 B，浏览用例层不接触 Mapper 行对象）。
 * 验证分页参数校验、偏移计算与读取模型（已归一中文槽位）字段透传。
 */
class ExerciseBrowseServiceTest {

    private ReviewedExerciseReader reviewedExerciseReader;
    private HealthResourceProvider resourceProvider;
    private ExerciseBrowseService service;

    @BeforeEach
    void setUp() {
        reviewedExerciseReader = mock(ReviewedExerciseReader.class);
        resourceProvider = mock(HealthResourceProvider.class);
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        service = new ExerciseBrowseService(reviewedExerciseReader, resourceProvider);
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
        verify(reviewedExerciseReader).browse(0, 50);
        service.browse(4, 20);
        verify(reviewedExerciseReader).browse(60, 20);
    }

    @Test
    void 极大page返回400而非负偏移或500() {
        assertThrows(DietException.class, () -> service.browse(Integer.MAX_VALUE, 20), "page 溢出 int 范围应拒绝");
        assertThrows(DietException.class, () -> service.browse(100_000_000, 50), "offset 超出数据库安全范围应拒绝");
    }

    @Test
    void 查询和结构化筛选交给Reader并保持服务端分页() {
        Map<String, String> filters = Map.of("bodyPart", "胸", "equipment", "徒手", "difficulty", "进阶");
        when(reviewedExerciseReader.browse(20, 20, 7L, true, "俯卧", filters))
                .thenReturn(List.of(exercise()));
        when(reviewedExerciseReader.count(7L, true, "俯卧", filters)).thenReturn(1);

        PagedResponse<ExerciseBrowseItem> response = service.browse(2, 20, 7L, true, "俯卧", filters);

        assertEquals(1, response.total());
        verify(reviewedExerciseReader).browse(20, 20, 7L, true, "俯卧", filters);
        verify(reviewedExerciseReader).count(7L, true, "俯卧", filters);
    }

    @Test
    void 空数据返回空列表() {
        when(reviewedExerciseReader.browse(0, 20)).thenReturn(List.of());
        when(reviewedExerciseReader.count()).thenReturn(0);
        PagedResponse<ExerciseBrowseItem> response = service.browse(1, 20);
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.total());
    }

    @Test
    void fixture模式返回能力不可用且不调用审核读取模块() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        ExerciseBrowseService fixtureService = new ExerciseBrowseService(reviewedExerciseReader, provider);

        HealthApiException error = assertThrows(HealthApiException.class,
                () -> fixtureService.browse(1, 20));

        assertEquals(HealthApiException.CODE_RESOURCE_MODE_UNAVAILABLE, error.code());
        assertTrue(error.getMessage().contains("动作浏览"));
        verifyNoInteractions(reviewedExerciseReader);
    }

    @Test
    void 动作元数据映射完整() {
        when(reviewedExerciseReader.browse(0, 20)).thenReturn(List.of(exercise()));
        when(reviewedExerciseReader.count()).thenReturn(1);
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
        assertEquals(null, item.thumbnailUrl());
        assertEquals(null, item.mediaUrl());
        assertEquals("NONE", item.mediaState());
        assertTrue(item.mediaCredit().contains("Gym visual"));
        assertEquals("gym-visual-exercises-dataset", item.sourceName());
        assertEquals("0662", item.sourceId());
    }

    @Test
    void 只有已授权媒体下发地址() {
        when(reviewedExerciseReader.browse(0, 20)).thenReturn(List.of(
                exercise("/assets/licensed.gif", "LICENSED"),
                exercise("/assets/unlicensed.gif", "NONE")));
        when(reviewedExerciseReader.count()).thenReturn(2);

        List<ExerciseBrowseItem> items = service.browse(1, 20).items();

        assertEquals("/assets/licensed.gif", items.get(0).mediaUrl());
        assertEquals("/assets/licensed.gif", items.get(0).thumbnailUrl());
        assertEquals(null, items.get(1).mediaUrl());
        assertEquals(null, items.get(1).thumbnailUrl());
    }

    /** 读取模型（#64）：槽位字段已是归一中文，浏览服务原样透传。 */
    private ReviewedExercise exercise() {
        return exercise(null, "NONE");
    }

    private ReviewedExercise exercise(String mediaUrl, String mediaState) {
        return new ReviewedExercise(
                9001L,
                "俯卧撑",
                "push-up",
                List.of("标准俯卧撑"),
                "胸",
                "胸",
                List.of("手臂"),
                List.of("手臂", "肩", "核心"),
                "徒手",
                "入门",
                "推",
                List.of("手腕压力"),
                "g_chest_press",
                "APPROVED",
                true,
                "平躺，膝盖弯曲，双脚平放在地上。收紧腹肌，慢慢将上半身抬离地面。",
                List.of("步骤一：准备姿势", "步骤二：完成动作"),
                mediaUrl,
                mediaUrl,
                mediaState,
                "© Gym visual — https://gymvisual.com/",
                "gym-visual-exercises-dataset",
                "0662",
                "main-2026-08-10"
        );
    }
}
