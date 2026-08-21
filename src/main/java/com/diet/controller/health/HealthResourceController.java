package com.diet.controller.health;

import com.diet.health.browse.ExerciseBrowseService;
import com.diet.health.browse.MealBrowseService;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.constants.DietConstants;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestAttribute;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 审核资源浏览 HTTP 入口（规格 6.2）。
 * GET /api/v1/health/meals 与 /exercises 使用 page/size 分页，size 最大 50；
 * 越界由服务层抛参数错误（统一错误结构）。
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthResourceController {

    private final MealBrowseService mealBrowseService;
    private final ExerciseBrowseService exerciseBrowseService;

    public HealthResourceController(MealBrowseService mealBrowseService,
                                    ExerciseBrowseService exerciseBrowseService) {
        this.mealBrowseService = mealBrowseService;
        this.exerciseBrowseService = exerciseBrowseService;
    }

    @GetMapping("/meals")
    public PagedResponse<MealBrowseItem> meals(
            @RequestAttribute(value = DietConstants.USER_ID_ATTRIBUTE, required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean favoriteOnly,
            @RequestParam(required = false) String q,
            @RequestParam Map<String, String> filters
    ) {
        return mealBrowseService.browse(page, size, userId, favoriteOnly, q, accepted(filters, "q"));
    }

    @GetMapping("/exercises")
    public PagedResponse<ExerciseBrowseItem> exercises(
            @RequestAttribute(value = DietConstants.USER_ID_ATTRIBUTE, required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean favoriteOnly,
            @RequestParam(required = false) String q,
            @RequestParam Map<String, String> filters
    ) {
        return exerciseBrowseService.browse(page, size, userId, favoriteOnly, q, accepted(filters, "q"));
    }

    private static Map<String, String> accepted(Map<String, String> params, String ignored) {
        Map<String, String> accepted = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if ("mealTime".equals(key) || "cuisine".equals(key) || "taste".equals(key)
                    || "healthGoal".equals(key) || "bodyPart".equals(key) || "equipment".equals(key)
                    || "difficulty".equals(key) || "movementPattern".equals(key)) {
                accepted.put(key, value);
            }
        });
        return accepted;
    }

    @GetMapping("/meals/{id}")
    public MealBrowseItem mealDetail(
            @RequestAttribute(value = DietConstants.USER_ID_ATTRIBUTE, required = false) Long userId,
            @PathVariable long id) {
        return mealBrowseService.detail(id, userId);
    }

    @GetMapping("/exercises/{id}")
    public ExerciseBrowseItem exerciseDetail(
            @RequestAttribute(value = DietConstants.USER_ID_ATTRIBUTE, required = false) Long userId,
            @PathVariable long id) {
        return exerciseBrowseService.detail(id, userId);
    }
}
