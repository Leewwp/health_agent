/**
 * 类型化反馈控件（17/41 号契约）。
 *
 * - FAVORITE：乐观更新 + 失败回滚（状态跨入口由 store 广播保持一致）；
 * - LIKE / DISLIKE / ADOPT：提交后轻量反馈；DISLIKE 由后端在当前候选集中
 *   硬过滤，前端不做本地过滤；
 * - 按钮通过事件委托处理，使用 data-* 描述动作。
 */
import { escapeHtml } from "../util/dom.js";
import { showToast } from "./toast.js";
import { isFavorite, toggleFavorite } from "../store.js";
import { sendFeedback } from "../api.js";

const ACTIONS = [
    { action: "FAVORITE", label: "收藏" },
    { action: "LIKE", label: "喜欢" },
    { action: "ADOPT", label: "采纳" },
    { action: "DISLIKE", label: "不合适" }
];

/**
 * 渲染反馈按钮组。
 * @param {string} resourceType MEAL | EXERCISE | ROUTINE
 * @param {string|number} resourceId
 * @param {{sessionId?: string, traceId?: string, planId?: string|number, planItemId?: string|number, source?: string}} context
 */
export function renderFeedbackControl(resourceType, resourceId, context) {
    const ctx = context || {};
    const favoriteActive = resourceType !== "ROUTINE" && isFavorite(resourceType, resourceId);
    const contextAttrs = `
        ${ctx.sessionId ? ` data-session-id="${escapeHtml(ctx.sessionId)}"` : ""}
        ${ctx.traceId ? ` data-trace-id="${escapeHtml(ctx.traceId)}"` : ""}
        ${ctx.planId ? ` data-plan-id="${escapeHtml(ctx.planId)}"` : ""}
        ${ctx.planItemId ? ` data-plan-item-id="${escapeHtml(ctx.planItemId)}"` : ""}
        ${ctx.source ? ` data-source="${escapeHtml(ctx.source)}"` : ""}
    `;
    const favoriteButton = resourceType === "ROUTINE"
        ? ""
        : `<button class="btn ghost feedback-btn ${favoriteActive ? "active" : ""}"
                   data-feedback="FAVORITE" data-type="${escapeHtml(resourceType)}" data-id="${escapeHtml(resourceId)}"${contextAttrs}
                   aria-pressed="${favoriteActive}">收藏${favoriteActive ? "✓" : ""}</button>`;
    return `
        <div class="button-row" data-feedback-group="1">
            ${favoriteButton}
            ${ACTIONS.filter((item) => item.action !== "FAVORITE").map((item) => `
                <button class="btn ghost feedback-btn" data-feedback="${item.action}"
                        data-type="${escapeHtml(resourceType)}" data-id="${escapeHtml(resourceId)}"${contextAttrs}>${item.label}</button>
            `).join("")}
        </div>
    `;
}

/** 在容器内绑定反馈按钮点击（事件委托；页面重复调用只绑定一次）。 */
let feedbackBound = false;

export function bindFeedbackControl(container) {
    if (feedbackBound) {
        return;
    }
    feedbackBound = true;
    container.addEventListener("click", (event) => {
        const button = event.target.closest("[data-feedback]");
        if (!button) {
            return;
        }
        const type = button.dataset.type;
        const id = button.dataset.id;
        const action = button.dataset.feedback;
        // 事件委托必须消费异步错误，失败路径已在 handleAction 内提示并回滚。
        void handleAction(type, id, action, button).catch(() => {});
    });
}

/** 收藏状态变化时同步页面内所有收藏按钮（乐观更新 + 失败回滚后的状态刷新）。 */
window.addEventListener("favoriteschange", () => {
    document.querySelectorAll('button[data-feedback="FAVORITE"]').forEach((button) => {
        const active = isFavorite(button.dataset.type, button.dataset.id);
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
        button.textContent = `收藏${active ? "✓" : ""}`;
    });
});

function contextFromButton(button) {
    return {
        sessionId: button.dataset.sessionId || null,
        traceId: button.dataset.traceId || null,
        planId: button.dataset.planId || null,
        planItemId: button.dataset.planItemId || null,
        source: button.dataset.source || null
    };
}

async function handleAction(resourceType, resourceId, action, button) {
    if (action === "FAVORITE") {
        // #65：按目标收藏状态发送 FAVORITE/UNFAVORITE，取消收藏不再是重复 FAVORITE
        const currentlyFavorite = isFavorite(resourceType, resourceId);
        await toggleFavorite(resourceType, resourceId, () =>
            sendFeedback({
                resourceType,
                resourceId,
                action: currentlyFavorite ? "UNFAVORITE" : "FAVORITE",
                ...contextFromButton(button)
            })
        );
        showToast(isFavorite(resourceType, resourceId) ? "已收藏" : "已取消收藏");
        return;
    }
    try {
        await sendFeedback({
            resourceType,
            resourceId,
            action,
            ...contextFromButton(button)
        });
        showToast(action === "DISLIKE" ? "已记录：之后不再推荐" : action === "ADOPT" ? "已采纳" : "已记录喜欢");
    } catch (error) {
        showToast(error.message || "反馈提交失败", "error");
    }
}
