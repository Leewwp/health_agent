package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.plan.DraftPlanRequest;
import com.diet.health.plan.GenerateTrainingPlanRequest;
import com.diet.health.plan.PatchItemRequest;
import com.diet.health.plan.PlanItemsWriteRequest;
import com.diet.health.plan.PlanSummaryView;
import com.diet.health.plan.PlanView;
import com.diet.health.plan.PlanWriteRequest;
import com.diet.health.plan.WeeklyPlanService;
import com.diet.health.plan.TrainingPlanGenerationResponse;
import com.diet.health.plan.TrainingPlanGenerationService;
import com.diet.health.plan.MealPlanGenerationService;
import com.diet.health.plan.CompositePlanGenerationService;
import com.diet.health.enums.PlanScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 周计划 HTTP 入口（规格 6.3）：
 * 生成 DRAFT、查看/列表、四态生命周期和批量项目编辑。
 * 未经确定性校验的计划不会持久化或激活；风险拒绝返回统一错误结构。
 */
@RestController
@RequestMapping("/api/v1/health/plans")
public class HealthPlanController {

    private final WeeklyPlanService planService;
    private final TrainingPlanGenerationService trainingPlanGenerationService;
    private final MealPlanGenerationService mealPlanGenerationService;
    private final CompositePlanGenerationService compositePlanGenerationService;
    public HealthPlanController(WeeklyPlanService planService) {
        this(planService, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HealthPlanController(WeeklyPlanService planService, TrainingPlanGenerationService trainingPlanGenerationService,
                                MealPlanGenerationService mealPlanGenerationService,
                                CompositePlanGenerationService compositePlanGenerationService) {
        this.planService = planService;
        this.trainingPlanGenerationService = trainingPlanGenerationService;
        this.mealPlanGenerationService = mealPlanGenerationService;
        this.compositePlanGenerationService = compositePlanGenerationService;
    }

    @GetMapping
    public List<PlanSummaryView> list(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId) {
        return planService.listPlans(userId);
    }

    @PostMapping("/drafts")
    public PlanView createDraft(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                @RequestBody(required = false) DraftPlanRequest request) {
        throw new com.diet.exception.HealthApiException(com.diet.exception.HealthApiException.CODE_CONFLICT,
                "旧的通用草稿入口已移除，请从聊天简报进入范围生成");
    }

    @PostMapping("/generate")
    public TrainingPlanGenerationResponse generate(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                                   @RequestBody GenerateTrainingPlanRequest request) {
        PlanScope scope = request == null ? null : request.planScope();
        if (scope == PlanScope.MEAL) {
            return mealPlanGenerationService.generate(userId, request);
        }
        if (scope == PlanScope.COMPOSITE) {
            return compositePlanGenerationService.generate(userId, request);
        }
        return trainingPlanGenerationService.generate(userId, request);
    }

    @GetMapping("/{planId}")
    public PlanView get(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                        @PathVariable Long planId) {
        return planService.getPlan(userId, planId);
    }

    @PostMapping("/{planId}/confirm")
    public PlanView confirm(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                            @PathVariable Long planId, @RequestBody PlanWriteRequest request) {
        return planService.confirm(userId, planId, request);
    }

    @PostMapping("/{planId}/enable")
    public PlanView enable(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                           @PathVariable Long planId, @RequestBody PlanWriteRequest request) {
        return planService.enable(userId, planId, request);
    }

    @PostMapping("/{planId}/disable")
    public PlanView disable(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                            @PathVariable Long planId, @RequestBody PlanWriteRequest request) {
        return planService.disable(userId, planId, request);
    }

    @PostMapping("/{planId}/archive")
    public PlanView archive(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                            @PathVariable Long planId, @RequestBody PlanWriteRequest request) {
        return planService.archive(userId, planId, request);
    }

    @PostMapping("/{planId}/copy")
    public PlanView copy(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                         @PathVariable Long planId, @RequestBody PlanWriteRequest request) {
        return planService.copy(userId, planId, request);
    }

    @DeleteMapping("/{planId}")
    public void deleteDraft(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                            @PathVariable Long planId) {
        planService.deleteDraft(userId, planId);
    }

    @PostMapping("/{planId}/edit")
    public PlanView edit(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                         @PathVariable Long planId) {
        return planService.edit(userId, planId);
    }

    @PutMapping("/{planId}/items")
    public PlanView updateItems(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                @PathVariable Long planId, @RequestBody PlanItemsWriteRequest request) {
        return planService.updateItems(userId, planId, request);
    }

    @PatchMapping("/{planId}/items/{itemId}")
    public PlanView patchItem(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                              @PathVariable Long planId,
                              @PathVariable Long itemId,
                              @RequestBody PatchItemRequest request) {
        return planService.patchItem(userId, planId, itemId, request);
    }

}
