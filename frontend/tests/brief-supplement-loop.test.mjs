import test from "node:test";
import assert from "node:assert/strict";

const { renderPlanActions } = await import("../assets/js/ui/plan-actions.js");
const { HEALTH_SLOT_LABELS, slotLabel } = await import("../assets/js/ui/health-slot-labels.js");

/**
 * 简报补充回路前端契约（规格 v3.2）：
 * 可补充项 chip 渲染与点击填充、计划简报摘要结构化字段、generationNotes 两分区渲染。
 */

test("可补充项渲染为可点chip且只列未填项", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "MEAL",
        mealPlanBriefSummary: "餐食目标周 2026-08-24",
        actions: [
            { type: "GENERATE_PLAN", label: "开始生成", requestId: "r-1" },
            { type: "CONTINUE_MEAL_PLAN_BRIEF", label: "补充", requestId: "r-1" }
        ],
        supplementable: [
            { key: "cuisine", label: "菜系", examples: ["川菜", "家常菜"], filled: false },
            { key: "tastePreferences", label: "口味", examples: ["清淡", "高蛋白"], filled: false }
        ]
    });
    assert.match(html, /data-action="supplement-chip"/);
    assert.match(html, /data-supplement-key="cuisine"/);
    assert.match(html, /菜系/);
    assert.match(html, /口味/);
    assert.doesNotMatch(html, /烹饪时长/, "只列后端下发的未填项");
});

test("无可补充项时不渲染chip区", () => {
    const html = renderPlanActions({
        task: "PLAN",
        domain: "MEAL",
        actions: [{ type: "GENERATE_PLAN", label: "开始生成", requestId: "r-1" }],
        supplementable: []
    });
    assert.doesNotMatch(html, /supplement-chip/);
});

test("点击chip把属性名参考输入填入输入框并聚焦", () => {
    // 模拟 chat.js 的 chip 点击分支
    const filled = [];
    const fakeInput = {
        set value(v) { filled.push(v); },
        focus() { filled.push("focus"); }
    };
    const textarea = { querySelector: () => fakeInput };
    const target = {
        dataset: {
            action: "supplement-chip",
            supplementKey: "cuisine",
            supplementLabel: HEALTH_SLOT_LABELS.cuisine ? "菜系" : slotLabel("cuisine")
        }
    };
    const input = textarea.querySelector("#chatForm textarea[name=message]");
    input.value = `${target.dataset.supplementLabel}：`;
    input.focus();
    assert.deepEqual(filled, ["菜系：", "focus"], "点击 chip 后填入“属性名：”并聚焦");
});

test("聊天页餐食简报摘要渲染新增偏好与未支持项字段", async () => {
    const source = await (await import("node:fs/promises"))
        .readFile(new URL("../assets/js/pages/chat.js", import.meta.url), "utf8");
    assert.match(source, /brief\.cuisine/, "摘要包含菜系字段");
    assert.match(source, /brief\.tastePreferences/, "摘要包含口味字段");
    assert.match(source, /brief\.convenience/, "摘要包含便利性字段");
    assert.match(source, /brief\.unsupportedPreferences/, "摘要包含未支持项");
    assert.match(source, /supplementable: response\.supplementable/, "消息状态携带可补充项");
});

test("确认短语与后端规范短语一致为开始推荐", async () => {
    const source = await (await import("node:fs/promises"))
        .readFile(new URL("../assets/js/pages/chat.js", import.meta.url), "utf8");
    assert.match(source, /const message = "开始推荐"/, "前端开始推荐发送同名确认短语");
});

test("计划页头部渲染generationNotes两分区（未支持偏好/按日回退）", async () => {
    const { renderGenerationNotesForTest } = await import("./plans-test-hooks.mjs");
    const html = renderGenerationNotesForTest({
        generationNotes: {
            unsupportedPreferences: ["cuisine:中餐"],
            fallbacks: [{ date: "2026-08-31", mealTimes: ["早餐", "午餐"], unmetPreferences: ["cuisine:川菜"] }]
        }
    });
    assert.match(html, /未支持偏好/, "分区一：未支持偏好");
    assert.match(html, /暂不按它筛选/, "未支持项注明暂不按它筛选");
    assert.match(html, /按日回退/, "分区二：按日回退");
    assert.match(html, /2026-08-31/, "回退项带日期");
    assert.match(html, /cuisine:川菜/, "回退项带未满足偏好键");

    // 无说明时不渲染
    assert.equal(renderGenerationNotesForTest({}), "");
    assert.equal(renderGenerationNotesForTest({ generationNotes: { unsupportedPreferences: [], fallbacks: [] } }), "");
});
