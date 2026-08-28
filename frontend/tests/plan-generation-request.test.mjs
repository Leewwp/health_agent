import assert from "node:assert/strict";
import test from "node:test";

import {
    PLAN_GENERATION_FRONTEND_TIMEOUT_MS,
    PLAN_GENERATION_TIMEOUT_MESSAGE,
    planGenerationRequestKey,
    runPlanGenerationRequest
} from "../assets/js/pages/plan-generation-request.js";
import { createChatRequestController, submitPresetChatMessage } from "../assets/js/pages/chat-request.js";
import { renderRawTraceJson } from "../assets/js/admin/trace-detail-view.js";
import { planGenerationSourceLabel } from "../assets/js/ui/plan-generation-source.js";

test("计划生成前端截止时间晚于代理且不超过 20 秒", () => {
    assert.equal(PLAN_GENERATION_FRONTEND_TIMEOUT_MS, 20_000);
    assert.ok(PLAN_GENERATION_FRONTEND_TIMEOUT_MS > 18_000);
});

test("计划生成超时会中止请求并返回可重试提示", async () => {
    let aborted = false;
    const request = (_payload, signal) => new Promise((_resolve, reject) => {
        signal.addEventListener("abort", () => {
            aborted = true;
            reject(new DOMException("Aborted", "AbortError"));
        });
    });

    await assert.rejects(runPlanGenerationRequest(request, {}, 5),
        (error) => error.message === PLAN_GENERATION_TIMEOUT_MESSAGE);
    assert.equal(aborted, true);
});

test("计划生成按 requestId 去重且修正偏好后的新请求不会复用旧失败", () => {
    assert.equal(planGenerationRequestKey("#/plans"), null);
    assert.equal(planGenerationRequestKey("#/plans?generate=1&requestId=req-a"), "req-a");
    assert.equal(planGenerationRequestKey("#/plans?generate=1&requestId=req-b"), "req-b");
    assert.equal(planGenerationRequestKey("#/plans?generate=1"), "__generated_request__");
});

test("计划生成来源在标题与提示中使用同一用户文案", () => {
    assert.equal(planGenerationSourceLabel("AGENT"), "Agent 生成");
    assert.equal(planGenerationSourceLabel("FALLBACK"), "规则降级");
    assert.equal(planGenerationSourceLabel("RULE_MEAL_COMPOSER"), "餐食规则组合");
    assert.equal(planGenerationSourceLabel("COMPOSITE_RULE_MERGE"), "综合规则合并");
});

test("确认训练偏好动作直接提交而不是只填写输入框", async () => {
    const submitted = [];
    const messages = [];
    const controller = createChatRequestController({
        request: async (payload) => submitted.push(payload.message),
        timeoutMs: 1000
    });

    assert.equal(await submitPresetChatMessage(controller, "确认训练偏好", (message) => messages.push(message)), true);
    assert.deepEqual(messages, ["确认训练偏好"]);
    assert.deepEqual(submitted, ["确认训练偏好"]);
});

test("Trace 原始 JSON 默认折叠", () => {
    const html = renderRawTraceJson("{&quot;ok&quot;:true}");
    assert.match(html, /<details>/);
    assert.doesNotMatch(html, /<details\s+open>/);
});
