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

test("餐食确认动作提交餐食确认语句并携带范围", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "MEAL",
        mealPlanBriefSummary: "餐食目标周 2026-08-24",
        actions: [{ type: "CONFIRM_MEAL_PLAN_BRIEF", label: "确认餐食计划" }]
    });
    assert.match(html, /确认餐食计划/);
    assert.match(html, /data-plan-scope="MEAL"/);
});

test("综合计划生成动作携带COMPOSITE范围", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "COMPOSITE",
        actions: [{ type: "GENERATE_PLAN", label: "生成综合计划", requestId: "r-1" }]
    });
    assert.match(html, /data-plan-scope="COMPOSITE"/);
});
