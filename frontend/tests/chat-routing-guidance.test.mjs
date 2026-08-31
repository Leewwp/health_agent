import test from "node:test";
import assert from "node:assert/strict";

// 2026-08-31 严格路由规格票据 06：等待文案、修改/新建计划动作与任务选择的结构化渲染。
const { renderPlanActions, renderTaskChoices } = await import("../assets/js/ui/plan-actions.js");
const { waitingMessageHtml } = await import("../assets/js/ui/chat-waiting.js");

test("等待态文案为正在生成回答，不再出现正在等待推荐结果", () => {
    const html = waitingMessageHtml();
    assert.match(html, /正在生成回答/);
    assert.doesNotMatch(html, /推荐结果/);
});

test("修改当前计划与新建动作都渲染为可点击按钮", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "EXERCISE",
        actions: [
            { type: "MODIFY_CURRENT_PLAN", label: "修改当前计划", requestId: "APPEND:5:EXERCISE" },
            { type: "NEW_PLAN_BRIEF", label: "新建一份", requestId: "新建" }
        ]
    });
    assert.match(html, /data-plan-action="MODIFY_CURRENT_PLAN"/);
    assert.match(html, /data-plan-action="NEW_PLAN_BRIEF"/);
    assert.match(html, /data-request-id="APPEND:5:EXERCISE"/);
});

test("任务澄清选项渲染为携带消息文案的结构化按钮", () => {
    const html = renderTaskChoices({
        actions: [
            { type: "SELECT_TASK", label: "新建一周计划", requestId: "帮我制定一份饮食和训练的综合计划" },
            { type: "SELECT_TASK", label: "问作息建议", requestId: "给我一些作息建议" }
        ]
    });
    assert.match(html, /data-action="select-task"/);
    assert.match(html, /data-message="帮我制定一份饮食和训练的综合计划"/);
    assert.equal(renderTaskChoices({ actions: [] }), "");
    assert.equal(renderTaskChoices({}), "");
});

test("计划简报面板标题不出现内部术语简报", () => {
    for (const domain of ["MEAL", "COMPOSITE", "EXERCISE"]) {
        const html = renderPlanActions({
            task: "PLAN",
            domain,
            actions: [{ type: "GENERATE_PLAN", label: "开始生成", requestId: "r-1" }]
        });
        assert.doesNotMatch(html, /简报/, `${domain} 面板标题不得出现内部术语`);
    }
});
