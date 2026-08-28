package com.diet.controller.health;

import com.diet.exception.HealthApiExceptionHandler;
import com.diet.health.browse.ExerciseBrowseService;
import com.diet.health.browse.MealBrowseService;
import com.diet.health.reader.exercise.ReviewedExerciseReader;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** fixture 浏览模式 HTTP 契约：统一返回 503，且不得访问正式审核库读取 adapter。 */
class HealthResourceControllerTest {

    private final ReviewedMealReader mealReader = mock(ReviewedMealReader.class);
    private final ReviewedExerciseReader exerciseReader = mock(ReviewedExerciseReader.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthResourceController(
                        new MealBrowseService(mealReader, provider),
                        new ExerciseBrowseService(exerciseReader, provider)))
                .setControllerAdvice(new HealthApiExceptionHandler())
                .build();
    }

    @Test
    void fixture餐食浏览返回503且审核读取零调用() throws Exception {
        mockMvc.perform(get("/api/v1/health/meals"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESOURCE_MODE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("当前资源模式不提供正式审核库餐食浏览"));

        verifyNoInteractions(mealReader);
    }

    @Test
    void fixture动作浏览返回503且审核读取零调用() throws Exception {
        mockMvc.perform(get("/api/v1/health/exercises"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESOURCE_MODE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("当前资源模式不提供正式审核库动作浏览"));

        verifyNoInteractions(exerciseReader);
    }

    @Test
    void 正式库名称搜索把q透传给对应Reader且丢弃未知筛选键() throws Exception {
        // #105：回车搜索的 q 必须原样到达对应审核 Reader；结构化筛选白名单以外的参数被丢弃。
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MockMvc dbMockMvc = MockMvcBuilders.standaloneSetup(new HealthResourceController(
                        new MealBrowseService(mealReader, provider),
                        new ExerciseBrowseService(exerciseReader, provider)))
                .setControllerAdvice(new HealthApiExceptionHandler())
                .build();
        when(mealReader.browse(0, 20, null, false, "鸡", Map.of())).thenReturn(List.of());
        when(mealReader.countPublic(null, false, "鸡", Map.of())).thenReturn(0);
        when(exerciseReader.browse(0, 20, null, false, "push up", Map.of())).thenReturn(List.of());
        when(exerciseReader.count(null, false, "push up", Map.of())).thenReturn(0);

        dbMockMvc.perform(get("/api/v1/health/meals").queryParam("q", "鸡").queryParam("hack", "1"))
                .andExpect(status().isOk());
        dbMockMvc.perform(get("/api/v1/health/exercises").queryParam("q", "push up"))
                .andExpect(status().isOk());

        verify(mealReader).browse(0, 20, null, false, "鸡", Map.of());
        verify(mealReader).countPublic(null, false, "鸡", Map.of());
        verify(exerciseReader).browse(0, 20, null, false, "push up", Map.of());
        verify(exerciseReader).count(null, false, "push up", Map.of());
    }
}
