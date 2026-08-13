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

import static org.mockito.Mockito.mock;
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
}
