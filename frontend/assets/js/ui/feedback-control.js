/**
 * 类型化反馈控件（17/41 号契约）。
 *
 * - FAVORITE：乐观更新 + 失败回滚（状态跨入口由 store 广播保持一致）；
 * - REDUCE_RECOMMENDATION：资源级减少推荐，按钮可再次点击撤销；
 * - 按钮通过事件委托处理，使用 data-* 描述动作。
 */
import { escapeHtml } from "../util/dom.js";
import { showToast } from "./toast.js";
import { isFavorite, toggleFavorite, isReduced, toggleReduced } from "../store.js";
import { addFavorite, removeFavorite, sendFeedback } from "../api.js";

const ACTIONS = [
    { action: "FAVORITE", label: "收藏" },
    { action: "REDUCE_RECOMMENDATION", label: "减少推荐" }
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
    const reducedActive = resourceType !== "ROUTINE" && isReduced(resourceType, resourceId);
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
                        data-type="${escapeHtml(resourceType)}" data-id="${escapeHtml(resourceId)}"${contextAttrs}
                        aria-pressed="${reducedActive}">${reducedActive ? "撤销减少推荐" : item.label}</button>
            `).join("")}
        </div>
    `;
}

/** 在容器内绑定反馈按钮点击（事件委托；页面重复调用只绑定一次）。 */
const feedbackContainers = new WeakSet();

export function bindFeedbackControl(container) {
    if (feedbackContainers.has(container)) {
        return;
    }
    feedbackContainers.add(container);
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

window.addEventListener("reducedchange", () => {
    document.querySelectorAll('button[data-feedback="REDUCE_RECOMMENDATION"]').forEach((button) => {
        const active = isReduced(button.dataset.type, button.dataset.id);
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
        button.textContent = active ? "撤销减少推荐" : "减少推荐";
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
        await toggleFavorite(resourceType, resourceId, () => currentlyFavorite
            ? removeFavorite(resourceType, resourceId)
            : addFavorite(resourceType, resourceId));
        showToast(isFavorite(resourceType, resourceId) ? "已收藏" : "已取消收藏");
        return;
    }
    if (action === "REDUCE_RECOMMENDATION") {
        const currentlyReduced = isReduced(resourceType, resourceId);
        await toggleReduced(resourceType, resourceId, () => sendFeedback({
            resourceType,
            resourceId,
            action: currentlyReduced ? "UNDO_REDUCE_RECOMMENDATION" : "REDUCE_RECOMMENDATION",
            ...contextFromButton(button)
        }));
        showToast(isReduced(resourceType, resourceId) ? "已减少推荐，可再次点击撤销" : "已撤销减少推荐");
        return;
    }
    try {
        await sendFeedback({
            resourceType,
            resourceId,
            action,
            ...contextFromButton(button)
        });
        showToast("已记录");
    } catch (error) {
        showToast(error.message || "反馈提交失败", "error");
    }
}
