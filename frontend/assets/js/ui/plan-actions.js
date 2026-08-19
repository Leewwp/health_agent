import { escapeHtml } from "../util/dom.js";

/** 计划动作完全以后端契约为准，前端不根据 PLAN 任务猜测档案状态。 */
export function renderPlanActions(message) {
    if (message.task !== "PLAN") return "";
    const actions = message.actions || [];
    if (!actions.length) return "";
    return `<div class="plan-brief" aria-label="训练计划需求简报">
        <strong>训练计划需求简报</strong>
        ${message.planBriefSummary ? `<p>${escapeHtml(message.planBriefSummary)}</p>` : ""}
        <div class="button-row">
            ${actions.map((action) => action.type === "COMPLETE_PROFILE"
                ? `<a class="btn primary" href="#/profile">${escapeHtml(action.label)}</a>`
                : `<button class="btn ${action.type === "GENERATE_PLAN" ? "primary" : "soft"}" data-action="plan-action" data-plan-action="${escapeHtml(action.type)}" data-request-id="${escapeHtml(action.requestId || "")}">${escapeHtml(action.label)}</button>`).join("")}
        </div>
    </div>`;
}
