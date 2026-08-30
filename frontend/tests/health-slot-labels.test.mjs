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

test("训练槽位标签与澄清/可补充 chip 统一词语（器械/训练日/训练时段）", () => {
    assert.equal(slotLabel("equipment"), "器械", "澄清问句、可补充 chip 与槽位标签统一为器械");
    assert.equal(slotLabel("trainingDays"), "训练日", "修正与“训练日期”的漂移");
    assert.equal(slotLabel("timeWindow"), "训练时段");
});

test("已确认摘要替换内部字段名并保留用户值", () => {
    assert.equal(formatSlotSummary("mood：没胃口"), "今天的心情：没胃口");
    assert.equal(formatSlotSummary("cuisine:素食"), "菜系或食材：素食");
});
