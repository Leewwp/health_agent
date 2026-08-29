import { escapeHtml } from "../util/dom.js";

/**
 * 计划动作完全以后端契约为准，前端不根据 PLAN 任务猜测档案状态。
 * 可补充项（supplementable，后端契约 {key, label, examples, filled}）渲染为可点 chip，
 * 点击把“属性名：”参考输入填入输入框并聚焦，不再要求用户自己想措辞。
 */
export function renderPlanActions(message) {
    if (message.task !== "PLAN") return "";
    const actions = message.actions || [];
    const supplementable = message.supplementable || [];
    if (!actions.length && !supplementable.length) return "";
    const scope = message.planScope || (message.domain === "MEAL" ? "MEAL" : message.domain === "COMPOSITE" ? "COMPOSITE" : "EXERCISE");
    const title = scope === "MEAL" ? "餐食计划需求简报" : scope === "COMPOSITE" ? "综合计划需求简报" : "训练计划需求简报";
    const summary = [message.planBriefSummary, message.mealPlanBriefSummary].filter(Boolean).join(" · ");
    const chips = supplementable.length
        ? `<div class="chips" aria-label="还可以补充的条件">${supplementable.map((item) =>
            `<button type="button" class="chip" data-action="supplement-chip" data-supplement-key="${escapeHtml(item.key)}" data-supplement-label="${escapeHtml(item.label)}">${escapeHtml(item.label)}</button>`).join("")}</div>`
        : "";
    return `<div class="plan-brief" aria-label="${escapeHtml(title)}">
        <strong>${escapeHtml(title)}</strong>
        ${summary ? `<p>${escapeHtml(summary)}</p>` : ""}
        ${chips}
        <div class="button-row">
            ${actions.map((action) => action.type === "COMPLETE_PROFILE"
                ? `<a class="btn primary" href="#/profile?return=chat">${escapeHtml(action.label)}</a>`
                : `<button class="btn ${["GENERATE_PLAN", "APPEND_TO_CURRENT_PLAN"].includes(action.type) ? "primary" : "soft"}" data-action="plan-action" data-plan-action="${escapeHtml(action.type)}" data-plan-scope="${escapeHtml(scope)}" data-request-id="${escapeHtml(action.requestId || "")}">${escapeHtml(action.label)}</button>`).join("")}
        </div>
    </div>`;
}
