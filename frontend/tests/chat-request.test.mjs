import assert from "node:assert/strict";
import test from "node:test";

import {
    CHAT_FAILURE_MESSAGE,
    CHAT_TIMEOUT_MESSAGE,
    createChatRequestController
} from "../assets/js/pages/chat-request.js";

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
    });
    return { promise, resolve, reject };
}

test("提交后同步显示等待状态并阻止重复请求", async () => {
    const pendingRequest = deferred();
    const states = [];
    let requestCount = 0;
    const controller = createChatRequestController({
        request: () => {
            requestCount += 1;
            return pendingRequest.promise;
        },
        onPendingChange: (pending) => states.push(pending),
        timeoutMs: 1000
    });

    const first = controller.submit({ message: "推荐晚餐" });
    const duplicate = await controller.submit({ message: "推荐晚餐" });

    assert.deepEqual(states, [true]);
    assert.equal(controller.isPending(), true);
    assert.equal(duplicate, false);
    assert.equal(requestCount, 1);

    pendingRequest.resolve({ speechText: "推荐完成" });
    assert.equal(await first, true);
    assert.deepEqual(states, [true, false]);
});

test("前端超时会中止请求、恢复控件并显示中文提示", async () => {
    let aborted = false;
    const failures = [];
    const states = [];
    const controller = createChatRequestController({
        request: (_payload, signal) => new Promise((_resolve, reject) => {
            signal.addEventListener("abort", () => {
                aborted = true;
                reject(new DOMException("Aborted", "AbortError"));
            });
        }),
        onPendingChange: (pending) => states.push(pending),
        onFailure: (message) => failures.push(message),
        timeoutMs: 5
    });

    assert.equal(await controller.submit({ message: "推荐动作" }), false);
    assert.equal(aborted, true);
    assert.deepEqual(failures, [CHAT_TIMEOUT_MESSAGE]);
    assert.deepEqual(states, [true, false]);
    assert.equal(controller.isPending(), false);
});

test("请求异常不暴露技术信息并允许再次提交", async () => {
    const failures = [];
    let attempts = 0;
    const controller = createChatRequestController({
        request: async () => {
            attempts += 1;
            if (attempts === 1) {
                throw new Error("502 <html>upstream stack trace</html>");
            }
            return { speechText: "重试成功" };
        },
        onFailure: (message) => failures.push(message),
        timeoutMs: 1000
    });

    assert.equal(await controller.submit({ message: "推荐动作" }), false);
    assert.deepEqual(failures, [CHAT_FAILURE_MESSAGE]);
    assert.equal(await controller.submit({ message: "推荐动作" }), true);
    assert.equal(attempts, 2);
});
