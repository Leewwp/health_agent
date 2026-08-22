import { escapeHtml } from "../util/dom.js";

/** 计划动作完全以后端契约为准，前端不根据 PLAN 任务猜测档案状态。 */
export function renderPlanActions(message) {
    if (message.task !== "PLAN") return "";
    const actions = message.actions || [];
    if (!actions.length) return "";
    const scope = message.planScope || (message.domain === "MEAL" ? "MEAL" : message.domain === "COMPOSITE" ? "COMPOSITE" : "EXERCISE");
    const title = scope === "MEAL" ? "餐食计划需求简报" : scope === "COMPOSITE" ? "综合计划需求简报" : "训练计划需求简报";
    const summary = [message.planBriefSummary, message.mealPlanBriefSummary].filter(Boolean).join(" · ");
    return `<div class="plan-brief" aria-label="${escapeHtml(title)}">
        <strong>${escapeHtml(title)}</strong>
        ${summary ? `<p>${escapeHtml(summary)}</p>` : ""}
        <div class="button-row">
            ${actions.map((action) => action.type === "COMPLETE_PROFILE"
                ? `<a class="btn primary" href="#/profile">${escapeHtml(action.label)}</a>`
                : `<button class="btn ${["GENERATE_PLAN", "APPEND_TO_CURRENT_PLAN"].includes(action.type) ? "primary" : "soft"}" data-action="plan-action" data-plan-action="${escapeHtml(action.type)}" data-plan-scope="${escapeHtml(scope)}" data-request-id="${escapeHtml(action.requestId || "")}">${escapeHtml(action.label)}</button>`).join("")}
        </div>
    </div>`;
}
