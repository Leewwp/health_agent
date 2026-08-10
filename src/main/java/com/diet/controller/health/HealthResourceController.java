package com.diet.controller.health;

import com.diet.health.browse.ExerciseBrowseService;
import com.diet.health.browse.MealBrowseService;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return mealBrowseService.browse(page, size);
    }

    @GetMapping("/exercises")
    public PagedResponse<ExerciseBrowseItem> exercises(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return exerciseBrowseService.browse(page, size);
    }
}
