import assert from "node:assert/strict";
import test from "node:test";

import { formatSlotSummary, slotLabel } from "../assets/js/ui/health-slot-labels.js";

test("所有健康聊天槽位使用面向用户的中文文案", () => {
    assert.equal(slotLabel("mood"), "今天的心情");
    assert.equal(slotLabel("cuisine"), "菜系或食材");
    assert.equal(slotLabel("convenience"), "能接受的耗时和购买方式");
    assert.equal(slotLabel("bodyParts"), "训练部位");
    assert.equal(slotLabel("unknownInternalSlot"), "其他偏好");
});

test("已确认摘要替换内部字段名并保留用户值", () => {
    assert.equal(formatSlotSummary("mood：没胃口"), "今天的心情：没胃口");
    assert.equal(formatSlotSummary("cuisine:素食"), "菜系或食材：素食");
});
