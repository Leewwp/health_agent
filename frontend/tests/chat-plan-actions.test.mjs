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
