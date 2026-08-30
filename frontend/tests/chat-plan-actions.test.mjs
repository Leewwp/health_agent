import test from "node:test";
import assert from "node:assert/strict";

const { renderPlanActions } = await import("../assets/js/ui/plan-actions.js");

test("PLAN 无后端动作时不臆造完善健康档案入口", () => {
    assert.equal(renderPlanActions({ task: "PLAN", actions: [] }), "");
});

test("仅后端 COMPLETE_PROFILE 动作显示完善档案入口", () => {
    const html = renderPlanActions({
        task: "PLAN",
        actions: [{ type: "COMPLETE_PROFILE", label: "完善健康档案" }]
    });
    assert.match(html, /完善健康档案/);
    assert.match(html, /#\/profile/);
});

test("餐食简报完整时只提供开始生成和补充，不出现确认动作", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "MEAL",
        mealPlanBriefSummary: "餐次：早餐、午餐",
        actions: [
            { type: "GENERATE_PLAN", label: "开始生成", requestId: "r-1" },
            { type: "CONTINUE_MEAL_PLAN_BRIEF", label: "补充", requestId: "r-1" }
        ]
    });
    assert.match(html, /开始生成/);
    assert.match(html, /补充/);
    assert.doesNotMatch(html, /确认/);
    assert.match(html, /data-plan-scope="MEAL"/);
});

test("综合计划生成动作携带COMPOSITE范围", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "COMPOSITE",
        actions: [{ type: "GENERATE_PLAN", label: "开始生成", requestId: "r-1" }]
    });
    assert.match(html, /data-plan-scope="COMPOSITE"/);
    assert.match(html, /开始生成/);
});
