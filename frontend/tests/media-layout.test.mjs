import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css = readFileSync(new URL("../assets/css/app.css", import.meta.url), "utf8");

function declarationBlock(selector) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const match = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
    assert.ok(match, `缺少样式规则：${selector}`);
    return match[1];
}

test("媒体容器清除 figure 默认外边距并裁切内容", () => {
    const block = declarationBlock(".media-frame");
    assert.match(block, /margin:\s*0\s*;/);
    assert.match(block, /overflow:\s*hidden\s*;/);
});

test("媒体图片脱离网格尺寸计算并完整贴合容器", () => {
    const block = declarationBlock(".media-frame img");
    assert.match(block, /position:\s*absolute\s*;/);
    assert.match(block, /inset:\s*0\s*;/);
    assert.match(block, /width:\s*100%\s*;/);
    assert.match(block, /height:\s*100%\s*;/);
    assert.match(block, /object-fit:\s*cover\s*;/);
});

test("详情抽屉按内容高度分配网格行并在超长时滚动", () => {
    const block = declarationBlock(".drawer-body");
    assert.match(block, /grid-auto-rows:\s*max-content\s*;/);
    assert.match(block, /overflow-y:\s*auto\s*;/);
});
