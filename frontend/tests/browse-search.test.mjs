/**
 * 浏览页名称搜索事件生命周期测试（#105）。
 *
 * 覆盖：回车 submit 只触发 API 搜索并 preventDefault、请求带 q、
 * 餐食↔动作跨路由切换后监听器不累积、旧页面监听器不处理当前表单。
 * 通过最小浏览器垫片驱动真实的 pages/browse.js + meals.js + exercises.js 模块；
 * 与真实浏览器一致，三个用例共享同一个 #app 桩（路由切换只替换内容，不替换容器）。
 */
import test from "node:test";
import assert from "node:assert/strict";

// —— 浏览器环境最小垫片：必须在动态导入前端模块之前安装 ——
const elementStub = () => ({
    textContent: "",
    className: "",
    style: {},
    dataset: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    addEventListener() {},
    removeEventListener() {},
    setAttribute() {},
    removeAttribute() {},
    querySelectorAll() { return []; },
    querySelector() { return null; },
    closest() { return null; },
    focus() {}
});
globalThis.window = globalThis;
globalThis.addEventListener = () => {};
globalThis.removeEventListener = () => {};
globalThis.document = {
    getElementById: () => elementStub(),
    querySelector: () => null,
    querySelectorAll: () => [],
    addEventListener() {},
    activeElement: null,
    body: elementStub()
};
globalThis.localStorage = (() => {
    const store = new Map();
    return {
        getItem: (key) => (store.has(key) ? store.get(key) : null),
        setItem: (key, value) => store.set(key, String(value)),
        removeItem: (key) => store.delete(key),
        clear: () => store.clear()
    };
})();

const fetchCalls = [];
globalThis.fetch = async (url) => {
    fetchCalls.push(String(url));
    return {
        ok: true,
        status: 200,
        headers: { get: () => "application/json" },
        text: async () => JSON.stringify({ items: [], total: 0 })
    };
};

const locationState = { hash: "#/meals" };
globalThis.location = locationState;

const { render: renderMeals } = await import("../assets/js/pages/meals.js");
const { render: renderExercises } = await import("../assets/js/pages/exercises.js");

// 与真实 #app 一致：整个会话共享同一个容器，路由切换只改 innerHTML。
const app = (() => {
    const listeners = new Map();
    return {
        listeners,
        innerHTML: "",
        focus() {},
        addEventListener(type, handler) {
            if (!listeners.has(type)) listeners.set(type, []);
            listeners.get(type).push(handler);
        },
        removeEventListener() {}
    };
})();

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const flushTwice = async () => {
    await flush();
    await flush();
};

function submitEvent(value) {
    const form = {
        matches: (selector) => selector === "form[data-browse-filter='1']",
        elements: { search: { value } }
    };
    let prevented = false;
    return {
        target: form,
        preventDefault: () => {
            prevented = true;
        },
        get prevented() {
            return prevented;
        }
    };
}

function submitListenerCount() {
    return (app.listeners.get("submit") || []).length;
}

function searchedUrls(apiPath, qFragment) {
    return fetchCalls
        .filter((url) => url.includes(apiPath) && url.includes("q="))
        .filter((url) => !qFragment || decodeURIComponent(url).includes(qFragment));
}

test("餐食库名称回车阻止文档导航并只向餐食 API 发送 q", async () => {
    locationState.hash = "#/meals";
    renderMeals(app);
    await flushTwice();

    assert.equal(submitListenerCount(), 1, "共享路由委托只绑定一次 submit 监听器");
    const initial = fetchCalls.filter((url) => url.includes("/api/v1/health/meals"));
    assert.ok(initial.length >= 1, "首次加载应请求餐食 API");
    assert.ok(initial.every((url) => !url.includes("q=")), "未输入搜索词时不得携带 q");

    const event = submitEvent("鸡");
    app.listeners.get("submit")[0](event);
    await flushTwice();

    assert.equal(event.prevented, true, "回车必须 preventDefault 阻止原生表单导航");
    const searched = searchedUrls("/api/v1/health/meals", "q=鸡");
    assert.ok(searched.length >= 1, "搜索必须重新请求餐食 API 并携带 q");
    assert.equal(searchedUrls("/api/v1/health/exercises").length, 0, "餐食搜索不得请求动作 API");
});

test("搜索文本框的 change 事件不得触发重载或清空搜索词", async () => {
    // 回归：回车提交时浏览器先对文本框派发 change；旧实现用 closest("[data-browse-filter]")
    // 误匹配到表单容器（data-browse-filter="1"），导致重渲染清空输入、搜索词丢失。
    const callsBefore = fetchCalls.length;
    const changeEvent = { target: { value: "鸡", matches: (s) => s === "[data-browse-favorite]" ? false : false, closest: () => null } };
    app.listeners.get("change")[0](changeEvent);
    await flushTwice();
    assert.equal(fetchCalls.length, callsBefore, "文本框 change 不得触发重新加载");

    const event = submitEvent("鸡");
    app.listeners.get("submit")[0](event);
    await flushTwice();
    assert.equal(event.prevented, true);
    assert.ok(searchedUrls("/api/v1/health/meals", "q=鸡").length >= 1, "change 之后的 submit 仍必须带上 q");
});

test("餐食到动作切换后监听器不累积且动作搜索发往动作 API", async () => {
    const countBefore = submitListenerCount();
    locationState.hash = "#/exercises";
    renderExercises(app);
    await flushTwice();

    assert.equal(submitListenerCount(), countBefore, "切换动作库后不得累积旧 submit 监听器");

    const event = submitEvent("push");
    app.listeners.get("submit")[0](event);
    await flushTwice();

    assert.equal(event.prevented, true, "动作库回车同样必须 preventDefault");
    const searched = searchedUrls("/api/v1/health/exercises", "q=push");
    assert.ok(searched.length >= 1, "动作库搜索必须发往动作 API 并带 q");
    assert.equal(searchedUrls("/api/v1/health/meals", "q=push").length, 0, "动作搜索不得发往餐食 API");
});

test("动作切回餐食后旧搜索状态保留且仍能重新搜索", async () => {
    locationState.hash = "#/meals";
    renderMeals(app);
    await flushTwice();

    assert.equal(submitListenerCount(), 1, "回到餐食库仍只有唯一 submit 监听器");
    assert.ok(searchedUrls("/api/v1/health/meals", "q=鸡").length >= 1, "切回餐食库应按保留的搜索词恢复结果");

    const event = submitEvent("沙拉");
    app.listeners.get("submit")[0](event);
    await flushTwice();

    assert.equal(event.prevented, true);
    const latest = searchedUrls("/api/v1/health/meals").at(-1);
    assert.ok(latest.includes(encodeURIComponent("沙拉")), "切回后仍能发起新的名称搜索");
    assert.equal(searchedUrls("/api/v1/health/exercises", "q=沙拉").length, 0, "餐食搜索不得请求动作 API");
});
