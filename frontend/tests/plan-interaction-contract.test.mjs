import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const drawerSource = await readFile(new URL("../assets/js/ui/detail-drawer.js", import.meta.url), "utf8");
const feedbackSource = await readFile(new URL("../assets/js/ui/feedback-control.js", import.meta.url), "utf8");
const plansSource = await readFile(new URL("../assets/js/pages/plans.js", import.meta.url), "utf8");

test("异步详情只渲染一个完整详情容器并保护迟到响应", () => {
    assert.match(drawerSource, /const body = typeof detailLoader === "function" \? ""/);
    assert.match(drawerSource, /<div class="drawer-resource-detail" data-drawer-detail="1">/);
    assert.match(drawerSource, /if \(requestVersion !== detailRequestVersion\) return;/);
    assert.doesNotMatch(drawerSource, /\$\{body\}\$\{detailArea\}/);
});

test("动作步骤存在时隐藏重复的整段讲解", () => {
    assert.match(drawerSource, /exercise\.steps && exercise\.steps\.length \? `/);
    assert.match(drawerSource, /: exercise\.instructionsZh \? `/);
});

test("详情抽屉根节点绑定反馈事件委托", () => {
    assert.match(drawerSource, /bindFeedbackControl\(root\)/);
    assert.match(feedbackSource, /const button = event\.target\.closest\("\[data-feedback\]"\)/);
});

test("picker 收藏状态以共享状态为准并使用乐观更新", () => {
    assert.match(plansSource, /const favorite = isFavorite\(type, item\.id\)/);
    assert.match(plansSource, /await toggleFavorite\(type, id, \(\) => currentlyFavorite/);
});

test("picker 详情覆盖餐食与动作完整字段", () => {
    for (const field of ["description", "ingredients", "nutrition", "allergens", "targetMuscles", "secondaryMuscles", "movementPattern", "riskTags", "qualificationVersion"]) {
        assert.match(plansSource, new RegExp(`resource\\.${field}`), `缺少 picker 字段：${field}`);
    }
});
