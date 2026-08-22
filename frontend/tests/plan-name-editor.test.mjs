import assert from "node:assert/strict";
import test from "node:test";

import { createPlanNameEditor } from "../assets/js/pages/plan-name-editor.js";

test("计划名称先显示标题，输入后可保存或取消", () => {
    const editor = createPlanNameEditor("本周训练");
    assert.deepEqual(editor.state(), { originalName: "本周训练", draftName: "本周训练", editing: false, dirty: false });
    assert.equal(editor.begin().editing, true);
    assert.equal(editor.input("我的减脂计划").dirty, true);
    assert.equal(editor.cancel().draftName, "本周训练");
    assert.equal(editor.state().editing, false);
});

test("计划名称不能为空并限制为128字符", () => {
    const editor = createPlanNameEditor("旧名称");
    editor.begin();
    editor.input(" ");
    assert.equal(editor.validate(), "计划名称不能为空");
    editor.input("a".repeat(129));
    assert.equal(editor.state().draftName.length, 128);
    assert.equal(editor.validate(), null);
});

test("名称保存前的编辑状态可被外层取消动作安全丢弃", () => {
    const editor = createPlanNameEditor("原计划");
    editor.begin();
    editor.input("未保存的新名称");
    assert.equal(editor.state().dirty, true);
    assert.equal(editor.cancel().draftName, "原计划");
    assert.deepEqual(editor.state(), { originalName: "原计划", draftName: "原计划", editing: false, dirty: false });
});
