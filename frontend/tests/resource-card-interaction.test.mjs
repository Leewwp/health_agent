import assert from "node:assert/strict";
import test from "node:test";

const storage = new Map();
globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key)
};
globalThis.window = {
    addEventListener() {},
    dispatchEvent() {}
};
globalThis.document = {
    getElementById: () => ({ classList: { add() {}, remove() {} }, setAttribute() {}, textContent: "" }),
    addEventListener() {},
    querySelectorAll: () => []
};

const { renderResourceCard } = await import("../assets/js/ui/resource-card.js");

test("餐食卡主体可通过鼠标和键盘聚焦打开详情", () => {
    const html = renderResourceCard({
        resourceType: "MEAL",
        resourceId: "12",
        name: "清蒸鲈鱼",
        nutrition: { caloriesKcal: 260 },
        sourceName: "审核餐食库"
    });

    assert.match(html, /class="resource-card-body"[^>]*data-action="open-resource"/);
    assert.match(html, /class="resource-card-body"[^>]*tabindex="0"/);
    assert.match(html, /class="resource-card-body"[^>]*role="button"/);
    assert.match(html, /aria-label="查看清蒸鲈鱼详情"/);
});

test("动作卡反馈与收藏控件位于详情打开主体之外", () => {
    const html = renderResourceCard({
        resourceType: "EXERCISE",
        resourceId: "30",
        name: "徒手深蹲",
        bodyPart: "腿部",
        equipment: "徒手"
    });
    const bodyEnd = html.indexOf("</div>", html.indexOf('class="resource-card-body"'));
    const feedbackStart = html.indexOf('data-feedback-group="1"');

    assert.ok(bodyEnd > 0);
    assert.ok(feedbackStart > bodyEnd, "反馈控件不得嵌套在详情打开主体内");
});
