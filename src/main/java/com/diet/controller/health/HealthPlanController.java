package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.plan.DraftPlanRequest;
import com.diet.health.plan.GenerateTrainingPlanRequest;
import com.diet.health.plan.PatchItemRequest;
import com.diet.health.plan.PlanSummaryView;
import com.diet.health.plan.PlanView;
import com.diet.health.plan.WeeklyPlanService;
import com.diet.health.plan.TrainingPlanGenerationResponse;
import com.diet.health.plan.TrainingPlanGenerationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 周计划 HTTP 入口（规格 6.3）：
 * 生成 DRAFT、查看/列表、激活、ACTIVE 编辑复制为新 DRAFT、PATCH 项目（日期/时间/备注）。
 * 未经确定性校验的计划不会持久化或激活；风险拒绝返回统一错误结构。
 */
@RestController
@RequestMapping("/api/v1/health/plans")
public class HealthPlanController {

    private final WeeklyPlanService planService;
    private final TrainingPlanGenerationService trainingPlanGenerationService;

    public HealthPlanController(WeeklyPlanService planService) {
        this(planService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HealthPlanController(WeeklyPlanService planService, TrainingPlanGenerationService trainingPlanGenerationService) {
        this.planService = planService;
        this.trainingPlanGenerationService = trainingPlanGenerationService;
    }

    @GetMapping
    public List<PlanSummaryView> list(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId) {
        return planService.listPlans(userId);
    }

    @PostMapping("/drafts")
    public PlanView createDraft(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                @RequestBody(required = false) DraftPlanRequest request) {
        return planService.createDraft(userId, request);
    }

    @PostMapping("/generate")
    public TrainingPlanGenerationResponse generate(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                                   @RequestBody GenerateTrainingPlanRequest request) {
        return trainingPlanGenerationService.generate(userId, request);
    }

    @GetMapping("/{planId}")
    public PlanView get(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                        @PathVariable Long planId) {
        return planService.getPlan(userId, planId);
    }

    @PostMapping("/{planId}/activate")
    public PlanView activate(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                             @PathVariable Long planId) {
        return planService.activate(userId, planId);
    }

    @PostMapping("/{planId}/edit")
    public PlanView edit(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                         @PathVariable Long planId) {
        return planService.edit(userId, planId);
    }

    @PatchMapping("/{planId}/items/{itemId}")
    public PlanView patchItem(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                              @PathVariable Long planId,
                              @PathVariable Long itemId,
                              @RequestBody PatchItemRequest request) {
        return planService.patchItem(userId, planId, itemId, request);
    }
}
