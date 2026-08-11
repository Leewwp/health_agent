/**
 * 旧饮食兼容入口（37 号：旧 /diet 路由提供兼容入口）。
 *
 * 原 static 单页应用的三个用户页面原样保留为独立模块：
 * - /diet：旧首页（个人/公共餐食统计）；
 * - /diet/chat：旧聊天（PERSONAL / PUBLIC 源模式，X-User-Id 身份）；
 * - /diet/meals/personal：个人餐食维护（CRUD + 七槽位字典）；
 * - /diet/meals/public：公共餐食只读。
 * 旧接口 /api/v1/diet/** 保持可用，本模块是其唯一 UI 载体。
 * 新用户导航不暴露这些入口；直接访问 hash 仍可进入。
 */
import { escapeHtml } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { currentRoute } from "../router.js";
import {
    dietCreateSession,
    dietChat as apiDietChat,
    dietListPersonalMeals,
    dietCreatePersonalMeal,
    dietUpdatePersonalMeal,
    dietDeletePersonalMeal,
    dietListPublicMeals,
    dietSlotOptions,
    dietSaveFeedback,
    getLegacyUserId,
    setLegacyUserId
} from "../api.js";

const SLOT_LABELS = {
    mealTime: "用餐时间",
    mood: "心情状态",
    scene: "用餐场景",
    healthGoal: "健康目标",
    cuisine: "菜系偏好",
    taste: "口味偏好",
    convenience: "便利程度"
};

const state = {
    home: { loaded: false, personalCount: 0, publicCount: 0 },
    slotOptions: null,
    personalMeals: [],
    publicMeals: [],
    editingMeal: null,
    chat: {
        sourceMode: "PERSONAL",
        sessionId: null,
        sending: false,
        messages: [
            {
                role: "assistant",
                text: "你好，我可以根据你的个人餐食库或公共餐食库推荐今天吃什么。可以试试问我：今晚想吃清淡一点，有什么推荐？"
            }
        ]
    }
};

/* ---------------- 旧首页 ---------------- */

export function dietHome() {
    return {
        async render(app) {
            app.innerHTML = `
                <section class="hero">
                    <div class="hero-panel">
                        <span class="badge">旧饮食助手（兼容入口）</span>
                        <h1>用更轻松的方式决定今天吃什么</h1>
                        <p>维护你的个人餐食库，也可以从公共餐食库开始。助手会根据时间、心情、场景、健康目标、口味和便利程度给出推荐，并在信息不足时主动追问。</p>
                        <div class="hero-actions">
                            <a class="btn primary" href="#/diet/chat">开始聊天推荐</a>
                            <a class="btn soft" href="#/diet/meals/personal">管理个人餐食</a>
                            <a class="btn ghost" href="#/diet/meals/public">看公共餐食</a>
                        </div>
                    </div>
                    <aside class="grid stats">
                        ${statCard("个人餐食", state.home.loaded ? state.home.personalCount : "加载中", "你的私有餐食库，用于个性化推荐")}
                        ${statCard("公共餐食", state.home.loaded ? state.home.publicCount : "加载中", "系统预置餐食，适合快速体验")}
                        ${statCard("当前用户", getLegacyUserId(), "旧接口使用 X-User-Id 模拟身份")}
                    </aside>
                </section>
            `;
            loadHomeStats();
        }
    };
}

function statCard(label, value, desc) {
    return `
        <div class="stat-card">
            <span class="muted">${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
            <p class="muted">${escapeHtml(desc)}</p>
        </div>
    `;
}

async function loadHomeStats() {
    if (state.home.loaded) {
        return;
    }
    try {
        const [personal, publicMeals] = await Promise.all([
            dietListPersonalMeals(),
            dietListPublicMeals()
        ]);
        state.home = {
            loaded: true,
            personalCount: personal.length,
            publicCount: publicMeals.length
        };
        if (currentRoute() === "/diet") {
            dietHome().render(document.getElementById("app"));
        }
    } catch (error) {
        showToast(error.message || "首页数据加载失败", "error");
    }
}

/* ---------------- 旧聊天 ---------------- */

export function dietChatPage() {
    return {
        async render(app) {
            app.innerHTML = `
                <section class="section chat-layout">
                    <div class="chat-window">
                        <div class="card-title">
                            <div>
                                <h2>旧饮食聊天推荐</h2>
                                <p>当前会话：${state.chat.sessionId ? escapeHtml(state.chat.sessionId) : "尚未创建，发送消息时自动创建"}</p>
                            </div>
                            <div class="inline-actions">
                                ${sourceButton("PERSONAL", "个人库")}
                                ${sourceButton("PUBLIC", "公共库")}
                                <button class="btn ghost" data-action="legacy-new-session">新会话</button>
                            </div>
                        </div>
                        <div id="messages" class="messages">${state.chat.messages.map(renderLegacyMessage).join("")}</div>
                        <form id="legacyChatForm" class="composer">
                            <textarea name="message" placeholder="例如：今晚想吃清淡一点，最好快手一点" required></textarea>
                            <button class="btn primary" type="submit">${state.chat.sending ? "发送中..." : "发送"}</button>
                        </form>
                    </div>
                    <aside class="grid">
                        <div class="card">
                            <div class="card-title">
                                <div>
                                    <h3>快捷问题</h3>
                                    <p>点击后可直接填入输入框。</p>
                                </div>
                            </div>
                            <div class="chips">
                                ${["早餐想吃方便一点", "晚饭推荐清淡低脂的", "今天心情一般，想吃点热乎的", "换一批，不想吃刚才那些", "我胃不舒服，应该吃什么"].map((text) => `<button class="chip" data-action="legacy-quick" data-message="${escapeHtml(text)}">${escapeHtml(text)}</button>`).join("")}
                            </div>
                        </div>
                        <div class="card">
                            <h3>身份与提示</h3>
                            <label class="field" style="margin-bottom:10px;">
                                <span>旧接口用户 ID（X-User-Id）</span>
                                <input id="legacyUserId" type="number" min="1" value="${escapeHtml(getLegacyUserId())}">
                            </label>
                            <p class="muted">PERSONAL 模式依赖个人餐食库；没有数据时可先去维护餐食，或切换到 PUBLIC 模式。</p>
                            <div class="button-row">
                                <a class="btn soft" href="#/diet/meals/personal">维护餐食</a>
                                <a class="btn ghost" href="#/diet/meals/public">看公共库</a>
                            </div>
                        </div>
                    </aside>
                </section>
            `;
            const messages = document.getElementById("messages");
            messages.scrollTop = messages.scrollHeight;
            bindLegacyChat(app);
        }
    };
}

function sourceButton(mode, label) {
    const active = state.chat.sourceMode === mode;
    return `<button class="btn ${active ? "soft" : "ghost"}" data-action="legacy-source" data-source="${mode}">${label}</button>`;
}

let chatBound = false;
let mealsBound = false;

function bindLegacyChat(app) {
    if (chatBound) {
        return;
    }
    chatBound = true;
    app.addEventListener("click", handleLegacyChatClick);
    app.addEventListener("change", (event) => {
        if (event.target.id === "legacyUserId") {
            setLegacyUserId(event.target.value);
            state.home.loaded = false;
            showToast("旧接口用户 ID 已切换");
        }
    });
    app.addEventListener("submit", (event) => {
        if (event.target.id === "legacyChatForm") {
            event.preventDefault();
            submitLegacyChat(event.target);
        }
    });
}

function renderLegacyMessage(message) {
    const mealCards = (message.meals || []).map((meal) => renderLegacyMealCard(meal)).join("");
    return `
        <article class="message ${message.role}">
            <div class="bubble">${escapeHtml(message.text)}</div>
            ${mealCards ? `<div class="grid two">${mealCards}</div>` : ""}
            ${message.traceId ? `<div class="message-meta">traceId：${escapeHtml(message.traceId)}</div>` : ""}
        </article>
    `;
}

function renderLegacyMealCard(meal) {
    return `
        <article class="meal-card">
            <header>
                <div>
                    <h3>${escapeHtml(meal.name)}</h3>
                    <p class="muted">${escapeHtml(meal.sourceType || "")}</p>
                </div>
                ${meal.matchScore ? `<span class="score">匹配 ${Math.round(meal.matchScore * 100)}%</span>` : ""}
            </header>
            <div class="chips">${legacyMealTags(meal).map((tag) => `<span class="chip selected">${escapeHtml(tag)}</span>`).join("")}</div>
            <div class="button-row">
                <button class="btn soft" data-action="legacy-feedback" data-value="LIKE" data-item-id="${escapeHtml(meal.id)}">喜欢</button>
                <button class="btn ghost" data-action="legacy-feedback" data-value="ADOPT" data-item-id="${escapeHtml(meal.id)}">采纳</button>
                <button class="btn ghost" data-action="legacy-feedback" data-value="DISLIKE" data-item-id="${escapeHtml(meal.id)}">不合适</button>
            </div>
        </article>
    `;
}

function legacyMealTags(meal) {
    return Object.keys(SLOT_LABELS).flatMap((key) => (meal[key] || []).map((value) => `${SLOT_LABELS[key]}：${value}`));
}

async function submitLegacyChat(form) {
    const messageInput = form.elements.message;
    const message = messageInput.value.trim();
    if (!message || state.chat.sending) {
        return;
    }
    state.chat.messages.push({ role: "user", text: message });
    messageInput.value = "";
    state.chat.sending = true;
    dietChatPage().render(document.getElementById("app"));
    try {
        if (!state.chat.sessionId) {
            const session = await dietCreateSession();
            state.chat.sessionId = session.sessionId;
        }
        const response = await apiDietChat({
            sessionId: state.chat.sessionId,
            message,
            sourceMode: state.chat.sourceMode,
            context: {}
        });
        state.chat.sessionId = response.sessionId || state.chat.sessionId;
        state.chat.messages.push({
            role: "assistant",
            text: response.clarifyQuestion || response.speechText || "我已经处理完这轮请求。",
            meals: response.displayBlocks || [],
            traceId: response.traceId
        });
    } catch (error) {
        showToast(error.message || "聊天请求失败", "error");
        state.chat.messages.push({ role: "assistant", text: "这轮请求失败了，请稍后重试。" });
    } finally {
        state.chat.sending = false;
        dietChatPage().render(document.getElementById("app"));
    }
}

function resetLegacyChat() {
    state.chat.sessionId = null;
    state.chat.messages = [
        { role: "assistant", text: "已开启新会话。告诉我你的用餐时间、口味、场景或健康目标，我来推荐。" }
    ];
    dietChatPage().render(document.getElementById("app"));
}

async function handleLegacyChatClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
        return;
    }
    const action = target.dataset.action;
    if (action === "legacy-new-session") {
        resetLegacyChat();
    } else if (action === "legacy-source") {
        state.chat.sourceMode = target.dataset.source;
        resetLegacyChat();
    } else if (action === "legacy-quick") {
        const input = document.querySelector("#legacyChatForm textarea[name=message]");
        if (input) {
            input.value = target.dataset.message;
            input.focus();
        }
    } else if (action === "legacy-feedback") {
        saveLegacyFeedback(target);
    }
}

async function saveLegacyFeedback(button) {
    try {
        await dietSaveFeedback({
            sessionId: state.chat.sessionId,
            itemId: Number(button.dataset.itemId),
            action: button.dataset.value,
            rating: button.dataset.value === "DISLIKE" ? 2 : 5,
            reason: ""
        });
        showToast("反馈已记录");
    } catch (error) {
        showToast(error.message || "反馈提交失败", "error");
    }
}

/* ---------------- 个人餐食维护 ---------------- */

export function dietPersonalMeals() {
    return {
        async render(app) {
            if (!state.slotOptions) {
                app.innerHTML = `<section class="section"><div class="empty">标签字典加载中...</div></section>`;
                try {
                    state.slotOptions = await dietSlotOptions();
                } catch (error) {
                    showToast(error.message || "槽位字典加载失败", "error");
                }
                if (currentRoute() !== "/diet/meals/personal") {
                    return;
                }
            }
            await ensureLegacyPersonalMeals();
            if (currentRoute() !== "/diet/meals/personal") {
                return;
            }
            app.innerHTML = `
                <section class="split">
                    <div class="section">
                        <div class="card-title">
                            <div>
                                <h2>个人餐食</h2>
                                <p>维护常吃餐食，旧聊天可切换到个人库使用。</p>
                            </div>
                            <button class="btn primary" data-action="legacy-new-meal">新增餐食</button>
                        </div>
                        <div id="personalMealList">${renderLegacyMealList(state.personalMeals)}</div>
                    </div>
                    <aside class="section">
                        ${renderLegacyMealForm()}
                    </aside>
                </section>
            `;
            bindLegacyMeals(app);
        }
    };
}

function bindLegacyMeals(app) {
    if (mealsBound) {
        return;
    }
    mealsBound = true;
    app.addEventListener("click", handleLegacyMealClick);
    app.addEventListener("submit", (event) => {
        if (event.target.id === "legacyMealForm") {
            event.preventDefault();
            if (!event.target.checkValidity()) {
                event.target.reportValidity();
                return;
            }
            saveLegacyMeal(event.target);
        }
    });
}

async function ensureLegacyPersonalMeals(force) {
    if (!force && state.personalMeals.length) {
        return;
    }
    try {
        state.personalMeals = await dietListPersonalMeals();
        state.home.loaded = false;
        if (currentRoute() === "/diet/meals/personal") {
            const list = document.getElementById("personalMealList");
            if (list) {
                list.innerHTML = renderLegacyMealList(state.personalMeals);
            }
        }
    } catch (error) {
        showToast(error.message || "个人餐食加载失败", "error");
    }
}

function renderLegacyMealList(meals) {
    if (!meals.length) {
        return `<div class="empty">暂无餐食。可以先新增几道常吃的菜。</div>`;
    }
    return `<div class="grid two">${meals.map((meal) => `
        <article class="meal-card">
            <header>
                <div>
                    <h3>${escapeHtml(meal.name)}</h3>
                    <p class="muted">${escapeHtml(meal.sourceType || "")}</p>
                </div>
            </header>
            <div class="chips">${legacyMealTags(meal).map((tag) => `<span class="chip selected">${escapeHtml(tag)}</span>`).join("")}</div>
            <div class="button-row">
                <button class="btn soft" data-action="legacy-edit-meal" data-id="${escapeHtml(meal.id)}">编辑</button>
                <button class="btn ghost" data-action="legacy-delete-meal" data-id="${escapeHtml(meal.id)}">删除</button>
            </div>
        </article>
    `).join("")}</div>`;
}

function renderLegacyMealForm() {
    const meal = state.editingMeal || emptyLegacyMeal();
    const title = meal.id ? "编辑餐食" : "新增餐食";
    return `
        <div class="card-title">
            <div>
                <h3>${title}</h3>
                <p>从下拉框选择标签，用餐时间为必选项，其余可留空。</p>
            </div>
        </div>
        <form id="legacyMealForm" class="form-grid">
            <input type="hidden" name="mealId" value="${escapeHtml(meal.id || "")}">
            <div class="field full">
                <label for="mealName">餐食名称</label>
                <input id="mealName" name="name" value="${escapeHtml(meal.name || "")}" placeholder="例如：番茄鸡蛋面" required>
            </div>
            <p class="field-hint full">标签下拉框支持多选：Windows 按住 Ctrl，Mac 按住 Command 点击可多项选择。</p>
            ${Object.entries(SLOT_LABELS).map(([key, label]) => renderLegacySlotPicker(key, label, meal[key] || [])).join("")}
            <div class="field full">
                <div class="button-row">
                    <button class="btn primary" type="submit">${meal.id ? "保存修改" : "创建餐食"}</button>
                    <button class="btn ghost" type="button" data-action="legacy-cancel-edit">清空</button>
                </div>
            </div>
        </form>
    `;
}

function renderLegacySlotPicker(key, label, selected) {
    const options = state.slotOptions && state.slotOptions[key] ? state.slotOptions[key] : [];
    const selectedSet = new Set(selected || []);
    const required = key === "mealTime";
    return `
        <div class="field">
            <label for="slot-${escapeHtml(key)}">${escapeHtml(label)}${required ? "（必选）" : ""}</label>
            <select id="slot-${escapeHtml(key)}" class="slot-select" name="${escapeHtml(key)}" multiple size="5" ${required ? "required" : ""}>
                ${options.map((option) => `<option value="${escapeHtml(option)}" ${selectedSet.has(option) ? "selected" : ""}>${escapeHtml(option)}</option>`).join("")}
            </select>
        </div>
    `;
}

function emptyLegacyMeal() {
    return {
        name: "",
        mealTime: [],
        mood: [],
        scene: [],
        healthGoal: [],
        cuisine: [],
        taste: [],
        convenience: []
    };
}

function handleLegacyMealClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
        return;
    }
    const action = target.dataset.action;
    if (action === "legacy-new-meal") {
        state.editingMeal = emptyLegacyMeal();
        dietPersonalMeals().render(document.getElementById("app"));
    } else if (action === "legacy-edit-meal") {
        const meal = state.personalMeals.find((item) => String(item.id) === String(target.dataset.id));
        if (meal) {
            state.editingMeal = JSON.parse(JSON.stringify(meal));
            dietPersonalMeals().render(document.getElementById("app"));
        }
    } else if (action === "legacy-delete-meal") {
        deleteLegacyMeal(target.dataset.id);
    } else if (action === "legacy-cancel-edit") {
        state.editingMeal = null;
        dietPersonalMeals().render(document.getElementById("app"));
    }
}

async function deleteLegacyMeal(id) {
    const meal = state.personalMeals.find((item) => String(item.id) === String(id));
    if (!meal || !window.confirm(`确定删除“${meal.name}”？`)) {
        return;
    }
    try {
        await dietDeletePersonalMeal(id);
        await ensureLegacyPersonalMeals(true);
        state.editingMeal = null;
        dietPersonalMeals().render(document.getElementById("app"));
        showToast("餐食已删除");
    } catch (error) {
        showToast(error.message || "删除失败", "error");
    }
}

async function saveLegacyMeal(form) {
    const formData = new FormData(form);
    const mealId = String(formData.get("mealId") || "").trim();
    const payload = { name: String(formData.get("name") || "").trim() };
    Object.keys(SLOT_LABELS).forEach((key) => {
        payload[key] = formData.getAll(key).filter(Boolean);
    });
    if (!payload.name) {
        showToast("请填写餐食名称", "error");
        return;
    }
    if (!payload.mealTime.length) {
        showToast("请至少选择一个用餐时间标签", "error");
        return;
    }
    try {
        if (mealId) {
            await dietUpdatePersonalMeal(mealId, payload);
        } else {
            await dietCreatePersonalMeal(payload);
        }
        showToast(mealId ? "餐食已更新" : "餐食已创建");
        state.editingMeal = null;
        await ensureLegacyPersonalMeals(true);
        dietPersonalMeals().render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "保存失败", "error");
    }
}

/* ---------------- 公共餐食 ---------------- */

export function dietPublicMeals() {
    return {
        async render(app) {
            app.innerHTML = `
                <section class="section">
                    <div class="card-title">
                        <div>
                            <h2>公共餐食</h2>
                            <p>系统预置餐食库，只读展示，可在旧聊天页切换到 PUBLIC 模式体验。</p>
                        </div>
                        <a class="btn primary" href="#/diet/chat">去聊天推荐</a>
                    </div>
                    <div id="publicMealList">${renderLegacyMealList(state.publicMeals)}</div>
                </section>
            `;
            await ensureLegacyPublicMeals();
            if (currentRoute() === "/diet/meals/public") {
                const list = document.getElementById("publicMealList");
                if (list) {
                    list.innerHTML = renderLegacyMealList(state.publicMeals);
                }
            }
        }
    };
}

async function ensureLegacyPublicMeals(force) {
    if (!force && state.publicMeals.length) {
        return;
    }
    try {
        state.publicMeals = await dietListPublicMeals();
        state.home.loaded = false;
    } catch (error) {
        showToast(error.message || "公共餐食加载失败", "error");
    }
}
