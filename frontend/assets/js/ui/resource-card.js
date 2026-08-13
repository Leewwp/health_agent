/**
 * 统一资源卡片（18 号决策：餐食、动作、对话推荐、计划项目复用）。
 *
 * 卡片只展示后端返回的字段（名称、标签、营养/难度摘要、来源、媒体状态），
 * 不在前端重新推导营养、训练剂量或风险结论。
 * 点击卡片主体打开统一详情抽屉；卡片持有的完整资源对象经 store 注册表传递。
 */
import { escapeHtml } from "../util/dom.js";
import { registerResource } from "../store.js";
import { renderMedia } from "./media-state.js";
import { renderFeedbackControl } from "./feedback-control.js";

let cardSequence = 0;

/**
 * 渲染一张资源卡片。
 * @param {object} resource 资源对象（浏览条目 / 对话块 / 计划项目，按字段识别）
 * @param {{sessionId?: string, traceId?: string, planId?: string|number, planItemId?: string|number, matchScore?: number, editable?: boolean}} options
 */
export function renderResourceCard(resource, options) {
    const opts = options || {};
    let resourceType = String(resource.resourceType || "").toUpperCase();
    if (!resourceType) {
        // 浏览条目不带 resourceType，按字段推断（对话块与计划项目自带）
        if (resource.nutrition || resource.ingredients || resource.tags) {
            resourceType = "MEAL";
        } else if (resource.bodyPart || resource.steps || resource.equipment || resource.instructionsZh) {
            resourceType = "EXERCISE";
        }
    }
    const resourceId = String(resource.resourceId ?? resource.id ?? "");
    if (!resourceType || !resourceId) {
        return "";
    }
    const key = `card-${cardSequence++}`;
    registerResource(key, resource);

    const name = resource.name || "未命名";
    const meta = buildCardMeta(resource, resourceType);
    const media = resourceType === "MEAL" || resourceType === "EXERCISE" ? renderCardMedia(resource) : "";
    const score = opts.matchScore ? `<span class="score">匹配 ${Math.round(opts.matchScore * 100)}%</span>` : "";

    return `
        <article class="meal-card">
            <header>
                <div>
                    <h3>${escapeHtml(name)}</h3>
                    ${meta.badge ? `<p class="muted" style="margin-top:4px;">${meta.badge}</p>` : ""}
                </div>
                ${score}
            </header>
            ${media}
            ${meta.chips ? `<div class="chips">${meta.chips}</div>` : ""}
            <p class="muted" style="margin:0;font-size:13px;line-height:1.55;">${escapeHtml(meta.desc)}</p>
            ${meta.source ? `<p class="media-credit" style="margin:0;">来源：${meta.source}</p>` : ""}
            <div class="button-row">
                <button class="btn soft" data-action="open-resource" data-key="${key}">详情</button>
                ${renderFeedbackControl(resourceType, resourceId, {
                    sessionId: opts.sessionId,
                    traceId: opts.traceId,
                    planId: opts.planId,
                    planItemId: opts.planItemId
                })}
            </div>
        </article>
    `;
}

function renderCardMedia(resource) {
    return renderMedia(resource.mediaUrl || null, resource.name, { credit: resource.mediaCredit });
}

function buildCardMeta(resource, resourceType) {
    if (resourceType === "MEAL") {
        const tags = resource.tags ? Object.entries(resource.tags).flatMap(([key, values]) => values.map((v) => `${key}：${v}`)) : [];
        const kcal = resource.nutrition && resource.nutrition.caloriesKcal != null
            ? `约 ${resource.nutrition.caloriesKcal} kcal${resource.nutrition.estimated ? "（估算）" : ""}`
            : "营养信息未提供";
        return {
            badge: kcal,
            chips: tags.slice(0, 6).map((tag) => `<span class="chip selected">${escapeHtml(tag)}</span>`).join(""),
            desc: (resource.description || "").slice(0, 80) || (resource.sourceName ? "" : ""),
            source: resource.sourceName || (resource.sourceType ? resource.sourceType : "")
        };
    }
    if (resourceType === "EXERCISE") {
        const chips = [];
        if (resource.difficulty) {
            chips.push(`<span class="chip ${escapeHtml(resource.difficulty)}">${escapeHtml(resource.difficulty)}</span>`);
        }
        if (resource.bodyPart) {
            chips.push(`<span class="chip selected">${escapeHtml(resource.bodyPart)}</span>`);
        }
        if (resource.equipment) {
            chips.push(`<span class="chip">${escapeHtml(resource.equipment)}</span>`);
        }
        const planReady = resource.planReady
            ? `<span class="badge">可入周计划</span>`
            : `<span class="badge warn">仅供浏览</span>`;
        return {
            badge: planReady,
            chips: chips.join(""),
            desc: resource.instructionsZh ? `${resource.instructionsZh.slice(0, 72)}…` : (resource.category || ""),
            source: resource.sourceName || "动作数据集"
        };
    }
    return {
        badge: resource.sourceType || "",
        chips: "",
        desc: resource.reason || "",
        source: resource.sourceName || resource.sourceType || ""
    };
}
