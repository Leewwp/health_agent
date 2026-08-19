/**
 * 聊天页（健康统一入口）。
 *
 * - 调用 POST /api/v1/health/chat，requestId 作为幂等键；
 * - 对话推荐的 displayBlocks 渲染为统一资源卡片，点击打开详情抽屉；
 * - 澄清时展示追问文本与缺失槽位；
 * - 开发/演示场景展示 traceId（可跳转 admin Trace 说明 Agent 路由、校验与降级），
 *   用户导航本身不暴露管理入口。
 */
import { escapeHtml, newRequestId } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { healthChat } from "../api.js";
import {
    getChatSessionId,
    setChatSessionId,
    clearChatSession,
    createChatSessionId,
    getOrCreateClientSessionId
} from "../store.js";
import { renderResourceCard } from "../ui/resource-card.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { bindDrawer } from "../ui/detail-drawer.js";
import { renderPlanActions } from "../ui/plan-actions.js";
import { navigate } from "../router.js";
import { devConfig } from "../config.js";
import { createChatRequestController, submitPresetChatMessage } from "./chat-request.js";

const QUICK_PROMPTS = [
    "今晚想吃得清淡一点，有什么推荐？",
    "帮我推荐一份适合新手的轻量训练",
    "晚上几点前应该停止喝咖啡？",
    "帮我安排一下这周的健身计划"
];

const SLOT_LABELS = {
    domain: "推荐类型",
    mealTime: "用餐时间",
    mood: "心情状态",
    scene: "用餐场景",
    healthGoal: "健康目标",
    cuisine: "菜系偏好",
    taste: "口味偏好",
    convenience: "便利程度",
    bodyPart: "训练部位",
    equipment: "器材",
    goal: "训练目标",
    bodyParts: "训练部位",
    trainingGoal: "训练目标",
    difficulty: "难度",
    weekStart: "目标周",
    trainingDays: "训练日期",
    timeWindow: "训练时段"
};

/** 缺失槽位的中文标签；未知槽位名兜底为通用中文标签，不暴露内部字段名。 */
function slotLabel(slot) {
    return SLOT_LABELS[slot] || "其他偏好";
}

const state = {
    sessionId: getChatSessionId(),
    sending: false,
    messages: [
        {
            role: "assistant",
            text: "你好，我是健康助手，可以帮你推荐餐食、健身动作和作息建议。\n试试问我：今晚想吃清淡一点、推荐轻量训练，或者安排一周计划。",
            blocks: [],
            traceId: null
        }
    ]
};

const requestController = createChatRequestController({
    request: ({ message }, signal) => requestHealthChat(message, signal),
    onPendingChange: (pending) => {
        state.sending = pending;
        render(document.getElementById("app"));
    },
    onSuccess: appendChatResponse,
    onFailure: (message) => {
        showToast(message, "error");
        state.messages.push({ role: "assistant", text: message });
    }
});

export async function render(app) {
    app.innerHTML = `
        <section class="chat-layout">
            <div class="section chat-window">
                <div class="card-title">
                    <div>
                        <h2>健康聊天</h2>
                        <p>当前会话：${escapeHtml(state.sessionId || "尚未创建，发送消息时自动创建")}</p>
                    </div>
                    <div class="inline-actions">
                        <button class="btn ghost" data-action="new-session">新会话</button>
                    </div>
                </div>
                <div id="messages" class="messages" aria-live="polite">${state.messages.map(renderMessage).join("")}${renderWaitingMessage()}</div>
                <form id="chatForm" class="composer" aria-busy="${state.sending}">
                    <textarea name="message" placeholder="例如：今晚想吃清淡一点，有什么推荐？" required ${state.sending ? "disabled" : ""}></textarea>
                    <button class="btn primary" type="submit" ${state.sending ? "disabled" : ""}>${state.sending ? '<span class="loading-spinner" aria-hidden="true"></span>等待中' : "发送"}</button>
                </form>
            </div>
            <aside class="grid">
                <div class="card">
                    <div class="card-title">
                        <div>
                            <h3>快捷问题</h3>
                            <p>点击后填入输入框。</p>
                        </div>
                    </div>
                    <div class="chips">
                        ${QUICK_PROMPTS.map((text) => `<button class="chip" data-action="quick-message" data-message="${escapeHtml(text)}">${escapeHtml(text)}</button>`).join("")}
                    </div>
                </div>
                <div class="card">
                    <h3>使用提示</h3>
                    <p class="muted">餐食推荐依赖餐食资源库与你的健康档案；生成周计划前请先在「健康档案」完善年龄、身高、体重、活动水平和目标。</p>
                    <div class="button-row">
                        <a class="btn soft" href="#/profile">完善健康档案</a>
                        <a class="btn ghost" href="#/meals">浏览餐食</a>
                    </div>
                </div>
            </aside>
        </section>
    `;

    const messages = document.getElementById("messages");
    messages.scrollTop = messages.scrollHeight;
    bind(app);
}

function renderWaitingMessage() {
    if (!state.sending) return "";
    return `<article class="message assistant chat-waiting" role="status"><div class="bubble"><span class="loading-spinner" aria-hidden="true"></span>正在等待推荐结果，请稍候…</div></article>`;
}

let listenersBound = false;

function bind(app) {
    if (listenersBound) {
        return;
    }
    listenersBound = true;
    app.addEventListener("click", handleClick);
    app.addEventListener("submit", handleSubmit);
    bindDrawer(app);
    bindFeedbackControl(app);
}

function renderMessage(message) {
    const blocks = (message.blocks || []).map((block) =>
        renderResourceCard(block, { sessionId: state.sessionId || getOrCreateClientSessionId(), traceId: message.traceId })
    ).join("");
    const missingSlots = message.missingSlots && message.missingSlots.length
        ? `<div class="chips">${message.missingSlots.map((slot) => `<span class="chip selected">${escapeHtml(slotLabel(slot))}</span>`).join("")}</div>`
        : "";
    const planEntry = renderPlanActions(message);
    const metaParts = [];
    if (message.traceId) {
        // 开发/演示配置下提供跳转 admin Trace 的入口；生产只展示 traceId 文本
        const traceLink = devConfig.enableDevTraceLink
            ? `<button class="btn ghost" style="min-height:0;padding:2px 8px;font-size:12px;" data-action="open-trace" data-trace-id="${escapeHtml(message.traceId)}">${escapeHtml(message.traceId)}</button>`
            : escapeHtml(message.traceId);
        metaParts.push(`traceId：${traceLink}`);
    }
    if (message.domain) {
        metaParts.push(`领域：${escapeHtml(message.domain)} · 任务：${escapeHtml(message.task)} · 阶段：${escapeHtml(message.phase)}`);
    }
    const meta = metaParts.length ? `<div class="message-meta">${metaParts.join(" · ")}</div>` : "";
    return `
        <article class="message ${message.role}">
            <div class="bubble">${escapeHtml(message.text)}</div>
            ${planEntry}
            ${missingSlots}
            ${blocks ? `<div class="grid two">${blocks}</div>` : ""}
            ${meta}
        </article>
    `;
}

async function submitChat(form) {
    const messageInput = form.elements.message;
    const message = messageInput.value.trim();
    if (!message || state.sending) {
        return;
    }
    state.messages.push({ role: "user", text: message });
    messageInput.value = "";
    await requestController.submit({ message });
}

/**
 * 发送消息；后端在会话不存在（如后端数据重置后本地残留旧 sessionId）
 * 时返回 404，此时清空本地会话并以新会话重试一次。
 */
async function requestHealthChat(message, signal, retried) {
    try {
        return await healthChat({
            sessionId: state.sessionId || undefined,
            requestId: newRequestId(),
            message,
            context: {}
        }, { signal });
    } catch (error) {
        // 会话相关失败（后端数据重置/匿名身份轮换后本地残留旧 sessionId）可能以 4xx 返回
        // （当前「会话不存在或无权访问」为 400）：统一清空本地会话后重试一次
        if (!retried && error.status >= 400 && error.status < 500) {
            clearChatSession();
            state.sessionId = null;
            return requestHealthChat(message, signal, true);
        }
        throw error;
    }
}

function appendChatResponse(response) {
    state.sessionId = response.sessionId || state.sessionId;
    setChatSessionId(state.sessionId);
    state.messages.push({
        role: "assistant",
        text: response.clarifyQuestion || response.speechText || "我已经处理完这轮请求。",
        blocks: response.displayBlocks || [],
        missingSlots: response.missingSlots || [],
        traceId: response.traceId,
        domain: response.domain,
        task: response.task,
        phase: response.phase
        ,actions: response.actions || []
        ,planBriefSummary: summarizePlanBrief(response.planBrief)
    });
}

function summarizePlanBrief(brief) {
    if (!brief || (!brief.trainingGoal && !brief.bodyParts?.length)) return "";
    const days = (brief.trainingDays || []).map((day) => String(day).replace("MONDAY", "周一").replace("TUESDAY", "周二")
        .replace("WEDNESDAY", "周三").replace("THURSDAY", "周四").replace("FRIDAY", "周五")
        .replace("SATURDAY", "周六").replace("SUNDAY", "周日")).join("、");
    const window = brief.timeWindow ? `${brief.timeWindow.start}-${brief.timeWindow.end}` : "未定";
    return `目标 ${brief.trainingGoal || "未定"} · 部位 ${(brief.bodyParts || []).join("、") || "未定"} · ${days || "未定"} · ${window}`;
}

function resetChat() {
    state.sessionId = createChatSessionId();
    state.messages = [
        {
            role: "assistant",
            text: "已开启新会话。告诉我你的餐食偏好、健身需求或作息问题，我来推荐。",
            blocks: [],
            traceId: null
        }
    ];
    render(document.getElementById("app"));
}

function handleClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
        return;
    }
    if (target.dataset.action === "new-session") {
        resetChat();
    } else if (target.dataset.action === "quick-message") {
        const input = document.querySelector("#chatForm textarea[name=message]");
        if (input) {
            input.value = target.dataset.message;
            input.focus();
        }
    } else if (target.dataset.action === "open-trace") {
        window.healthPendingTraceId = target.dataset.traceId;
        navigate("/admin/traces");
    } else if (target.dataset.action === "plan-action") {
        const action = target.dataset.planAction;
        if (action === "CONFIRM_PLAN_BRIEF") {
            submitPresetChatMessage(requestController, "确认训练偏好", (message) => {
                state.messages.push({ role: "user", text: message });
            });
        } else if (action === "GENERATE_PLAN") {
            const requestId = target.dataset.requestId || "";
            navigate(`/plans?generate=1${requestId ? `&requestId=${encodeURIComponent(requestId)}` : ""}`);
        }
    }
}

function handleSubmit(event) {
    if (event.target.id === "chatForm") {
        event.preventDefault();
        submitChat(event.target);
    }
}
