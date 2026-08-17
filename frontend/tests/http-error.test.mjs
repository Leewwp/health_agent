import test from "node:test";
import assert from "node:assert/strict";

import { readResponseError } from "../assets/js/http-error.js";

test("Nginx 502 HTML 被转换为可执行的中文提示", () => {
    const html = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>";

    assert.equal(
        readResponseError(502, "text/html", html),
        "后端服务暂时不可用，请确认应用已启动后重试。"
    );
});

test("后端 JSON 业务错误保留 message", () => {
    assert.equal(
        readResponseError(400, "application/json", JSON.stringify({ message: "请求参数不完整" })),
        "请求参数不完整"
    );
});

test("普通文本客户端错误保持原文", () => {
    assert.equal(readResponseError(400, "text/plain", "请求格式错误"), "请求格式错误");
});
