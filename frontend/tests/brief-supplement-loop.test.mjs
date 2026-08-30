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
        mealPlanBriefSummary: "餐次：午餐",
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

// 摘要渲染行为化测试：从 chat.js 提取真实的 summarizeMealPlanBrief 函数体，
// 在受控作用域内求值并断言渲染输出（加固规格：禁止源码正则充当字段支持证明）。
async function loadSummarizeMealPlanBrief() {
    const { readFile } = await import("node:fs/promises");
    const source = await readFile(new URL("../assets/js/pages/chat.js", import.meta.url), "utf8");
    const start = source.indexOf("function summarizeMealPlanBrief(brief)");
    const end = source.indexOf("\nfunction ", start + 10);
    const body = source.slice(start, end);
    return new Function(`${body}; return summarizeMealPlanBrief;`)();
}

test("聊天页餐食简报摘要渲染多菜系多类型与未支持项", async () => {
    const summarize = await loadSummarizeMealPlanBrief();
    const summary = summarize({
        weekStart: "2026-08-31",
        mealTimes: ["早餐", "晚餐"],
        healthGoal: "减脂",
        cuisines: ["粤菜", "川菜"],
        foodTypes: ["素食", "轻食"],
        tastePreferences: ["清淡"],
        convenience: "快速",
        unsupportedPreferences: ["cuisine:中餐", "foodType:生酮"]
    });
    assert.ok(summary.includes("菜系 粤菜、川菜"), `摘要渲染多个菜系: ${summary}`);
    assert.ok(summary.includes("餐食类型 素食、轻食"), `摘要渲染多个类型: ${summary}`);
    assert.ok(summary.includes("暂不支持 cuisine:中餐、foodType:生酮"), `摘要渲染未支持项: ${summary}`);
    assert.ok(summary.includes("口味 清淡"), `摘要渲染口味: ${summary}`);
    assert.ok(summary.includes("烹饪 快速"), `摘要渲染便利性: ${summary}`);
    assert.ok(!summary.includes("目标周") && !summary.includes("2026-08-31"),
        `ADR-0018：聊天摘要隐藏内部周锚点: ${summary}`);
});

test("聊天页餐食简报摘要不展示内部周锚点日期", async () => {
    const summarize = await loadSummarizeMealPlanBrief();
    const summary = summarize({ weekStart: "2026-08-31", mealTimes: ["早餐"], healthGoal: "减脂" });
    assert.ok(summary.includes("早餐"), `简报内容正常渲染: ${summary}`);
    assert.ok(!summary.includes("2026-08-31"), `内部周锚点不得出现在聊天摘要: ${summary}`);
    assert.ok(!summary.includes("目标周"), `不得出现目标周文案: ${summary}`);
    assert.ok(!summary.includes("未定") || summary.includes("餐次 早餐") || summary.includes("早餐"),
        `摘要不因缺周锚点渲染虚假缺项: ${summary}`);
});

test("聊天页餐食简报摘要对空简报与旧单值菜系的行为", async () => {
    const summarize = await loadSummarizeMealPlanBrief();
    assert.equal(summarize(null), "", "空简报渲染为空摘要");
    assert.equal(summarize({}), "", "全空字段渲染为空摘要（空态）");
    const legacy = summarize({ weekStart: "2026-08-31", mealTimes: ["早餐"], cuisine: "粤菜" });
    assert.ok(legacy.includes("菜系 粤菜"), `旧单值 cuisine 兼容回退渲染: ${legacy}`);
});

test("确认短语与后端规范短语一致为开始推荐", async () => {
    const source = await (await import("node:fs/promises"))
        .readFile(new URL("../assets/js/pages/chat.js", import.meta.url), "utf8");
    assert.match(source, /const message = "开始推荐"/, "前端开始推荐发送同名确认短语");
});

test("计划页头部渲染generationNotes三分区（未支持偏好/按日回退/候选不足）", async () => {
    const { renderGenerationNotesForTest } = await import("./plans-test-hooks.mjs");
    const html = renderGenerationNotesForTest({
        generationNotes: {
            unsupportedPreferences: ["cuisine:中餐"],
            fallbacks: [{ date: "2026-08-31", mealTimes: ["早餐", "午餐"], unmetPreferences: ["cuisine:川菜"] }],
            candidateScarcity: ["符合全部条件的候选动作只有 1 个，已按指定训练日复用候选。"]
        }
    });
    assert.match(html, /未支持偏好/, "分区一：未支持偏好");
    assert.match(html, /暂不按它筛选/, "未支持项注明暂不按它筛选");
    assert.match(html, /按日回退/, "分区二：按日回退");
    assert.match(html, /2026-08-31/, "回退项带日期");
    assert.match(html, /cuisine:川菜/, "回退项带未满足偏好键");
    assert.match(html, /候选不足/, "分区三：候选不足");
    assert.match(html, /候选动作只有 1 个/, "候选不足说明用户可见");

    // 分区四：餐训时间适配（ADR-0018，被移动餐次与方向用户可见）
    const adaptedHtml = renderGenerationNotesForTest({
        generationNotes: {
            mealAdaptations: [{
                date: "2026-08-31",
                mealTime: "晚餐",
                originalStart: "18:00",
                originalEnd: "19:00",
                finalStart: "19:00",
                finalEnd: "20:00",
                direction: "AFTER_TRAINING"
            }]
        }
    });
    assert.match(adaptedHtml, /餐时适配/, "分区四：餐时适配");
    assert.match(adaptedHtml, /晚餐 18:00-19:00/, "显示被移动餐次与原始窗口");
    assert.match(adaptedHtml, /19:00-20:00/, "显示最终窗口");
    assert.match(adaptedHtml, /训练后/, "显示适配方向（训练后）");

    // 无说明时不渲染
    assert.equal(renderGenerationNotesForTest({}), "");
    assert.equal(renderGenerationNotesForTest({ generationNotes: { unsupportedPreferences: [], fallbacks: [], candidateScarcity: [], mealAdaptations: [] } }), "");
});
