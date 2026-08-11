/**
 * 统一详情抽屉（18 号决策：桌面右侧打开，移动端占满屏幕）。
 *
 * 内容按资源类型分派：
 * - MEAL：营养表、份量、描述、食材、过敏原、来源与媒体署名；
 * - EXERCISE：部位/器材/难度/动作模式、风险标签、步骤、来源与媒体署名；
 * - ROUTINE：结构化事实与来源；
 * - 计划项目：计划参数与备注（计划页传入编辑表单）。
 * 只展示后端返回字段，不重新推导任何数值。
 */
import { escapeHtml, formatTime } from "../util/dom.js";
import { getResource } from "../store.js";
import { renderFeedbackControl } from "./feedback-control.js";

const root = document.getElementById("drawer-root");
let drawerContext = null;

export function openDrawer(key, context) {
    drawerContext = context || {};
    const resource = getResource(key);
    if (!resource) {
        return;
    }
    let resourceType = String(resource.resourceType || "").toUpperCase();
    if (!resourceType) {
        // 浏览条目不带 resourceType，按字段推断（与 resource-card 一致）
        if (resource.nutrition || resource.ingredients || resource.tags) {
            resourceType = "MEAL";
        } else if (resource.bodyPart || resource.steps || resource.equipment || resource.instructionsZh) {
            resourceType = "EXERCISE";
        }
    }
    const resourceId = String(resource.resourceId ?? resource.id ?? "");

    const editForm = drawerContext.editForm || "";
    const body = renderBody(resource, resourceType);
    const footerParts = [];
    if (resourceType === "MEAL" || resourceType === "EXERCISE") {
        footerParts.push(renderFeedbackControl(resourceType, resourceId, {
            sessionId: drawerContext.sessionId,
            planId: drawerContext.planId,
            planItemId: drawerContext.planItemId
        }));
    }
    if (editForm) {
        footerParts.push(`<button class="btn primary" data-drawer-save="1">保存</button>`);
        footerParts.push(`<button class="btn ghost" data-drawer-close="1">取消</button>`);
    }
    const footer = footerParts.length ? `<footer class="drawer-footer" data-drawer-footer="1">${footerParts.join("")}</footer>` : "";

    root.innerHTML = `
        <div class="drawer-panel" role="dialog" aria-modal="true" aria-label="${escapeHtml(resource.name || "详情")}">
            <header class="drawer-header">
                <div>
                    <h2>${escapeHtml(resource.name || "详情")}</h2>
                    <p>${escapeHtml(resourceType)} · ${escapeHtml(resourceId)}</p>
                </div>
                <button class="btn drawer-close" data-drawer-close="1" aria-label="关闭详情">✕</button>
            </header>
            <div class="drawer-body">${body}${editForm}</div>
            ${footer}
        </div>
    `;
    root.classList.add("open");
    root.setAttribute("aria-hidden", "false");
}

export function closeDrawer() {
    root.classList.remove("open");
    root.setAttribute("aria-hidden", "true");
    root.innerHTML = "";
    drawerContext = null;
}

export function isDrawerOpen() {
    return root.classList.contains("open");
}

let drawerBound = false;
let openerBound = false;

/** 页面级绑定（drawer 根元素独立于页面容器；两类监听都只注册一次）。 */
export function bindDrawer(container) {
    if (!openerBound) {
        openerBound = true;
        container.addEventListener("click", (event) => {
            const opener = event.target.closest("[data-action='open-resource']");
            if (opener && getResource(opener.dataset.key)) {
                openDrawer(opener.dataset.key, {
                    sessionId: opener.dataset.sessionId || null,
                    planId: opener.dataset.planId || null,
                    planItemId: opener.dataset.planItemId || null
                });
            }
        });
    }
    if (drawerBound) {
        return;
    }
    drawerBound = true;
    root.addEventListener("click", (event) => {
        if (event.target.closest("[data-drawer-close]")) {
            closeDrawer();
            return;
        }
        const saveButton = event.target.closest("[data-drawer-save]");
        if (saveButton && drawerContext && typeof drawerContext.onSave === "function") {
            drawerContext.onSave(collectEditValues());
        }
    });
}

function collectEditValues() {
    const values = {};
    root.querySelectorAll("[data-edit-field]").forEach((input) => {
        values[input.dataset.editField] = input.value;
    });
    return values;
}

window.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && isDrawerOpen()) {
        closeDrawer();
    }
});

/* ---------------- 内容渲染 ---------------- */

function renderBody(resource, resourceType) {
    if (resource.localDate) {
        return renderPlanItemBody(resource);
    }
    if (resourceType === "MEAL") {
        return renderMealBody(resource);
    }
    if (resourceType === "EXERCISE") {
        return renderExerciseBody(resource);
    }
    return renderRoutineBody(resource);
}

function renderMealBody(meal) {
    const nutrition = meal.nutrition || {};
    const tags = meal.tags ? Object.entries(meal.tags).flatMap(([key, values]) => values.map((v) => `${key}：${v}`)) : [];
    const serving = meal.serving ? `${meal.serving.count || 1} 份${meal.serving.size != null ? ` · ${meal.serving.size} ${meal.serving.unit || "g"}` : ""}` : "";
    return `
        ${meal.mediaUrl || meal.mediaStatus !== undefined
            ? `<figure class="media-frame" style="max-height:220px;">${meal.mediaUrl
                ? `<img src="${escapeHtml(meal.mediaUrl)}" alt="${escapeHtml(meal.name)}"
                        onerror="this.remove(); this.closest('.media-frame').innerHTML = '<span class=\\"media-placeholder\\"><span class=\\"media-mark\\">图</span><span>无图</span></span>';">`
                : `<span class="media-placeholder"><span class="media-mark">图</span><span>${escapeHtml(meal.name)} · 无图</span></span>`}</figure>`
            : ""}
        <div class="kv-list">
            <div class="kv"><span>营养（估算）</span><span>
                ${nutrition.caloriesKcal != null ? `${nutrition.caloriesKcal} kcal` : "-"} ·
                蛋白 ${nutrition.proteinG ?? "-"} g ·
                脂肪 ${nutrition.fatG ?? "-"} g ·
                碳水 ${nutrition.carbohydrateG ?? "-"} g
            </span></div>
            ${serving ? `<div class="kv"><span>份量口径</span><span>${escapeHtml(serving)}</span></div>` : ""}
            ${nutrition.basis ? `<div class="kv"><span>营养依据</span><span>${escapeHtml(nutrition.basis)}</span></div>` : ""}
            ${meal.description ? `<div class="kv"><span>描述</span><span>${escapeHtml(meal.description)}</span></div>` : ""}
            ${meal.ingredients && meal.ingredients.length ? `<div class="kv"><span>食材</span><span>${escapeHtml(meal.ingredients.join("、"))}</span></div>` : ""}
            ${meal.allergens && meal.allergens.length ? `<div class="kv"><span>过敏原</span><span>${escapeHtml(meal.allergens.join("、"))}</span></div>` : ""}
            <div class="kv"><span>来源</span><span>${escapeHtml(meal.sourceName || meal.sourceType || "-")}${meal.sourceId ? ` · ${escapeHtml(meal.sourceId)}` : ""}${meal.sourceVersion ? ` · v${escapeHtml(meal.sourceVersion)}` : ""}</span></div>
            ${meal.mediaCredit ? `<div class="kv"><span>媒体署名</span><span>${escapeHtml(meal.mediaCredit)}</span></div>` : ""}
            ${meal.allergenStatus ? `<div class="kv"><span>过敏审核</span><span>${escapeHtml(meal.allergenStatus)}</span></div>` : ""}
        </div>
        ${tags.length ? `<div class="chips">${tags.map((tag) => `<span class="chip selected">${escapeHtml(tag)}</span>`).join("")}</div>` : ""}
        ${meal.reason ? `<p class="muted" style="line-height:1.65;">推荐理由：${escapeHtml(meal.reason)}</p>` : ""}
    `;
}

function renderExerciseBody(exercise) {
    const chips = [
        exercise.bodyPart ? `部位：${exercise.bodyPart}` : "",
        exercise.equipment ? `器材：${exercise.equipment}` : "",
        exercise.difficulty ? `难度：${exercise.difficulty}` : "",
        exercise.category ? `类别：${exercise.category}` : "",
        exercise.movementPattern ? `动作模式：${exercise.movementPattern}` : ""
    ].filter(Boolean);
    return `
        ${exercise.planReady ? `<span class="badge">已通过周计划资格审核（plan_ready）</span>` : `<span class="badge warn">仅供浏览与单次推荐</span>`}
        <div class="kv-list">
            <div class="kv"><span>目标肌群</span><span>${escapeHtml((exercise.targetMuscles || []).join("、") || "-")}</span></div>
            ${exercise.secondaryMuscles && exercise.secondaryMuscles.length ? `<div class="kv"><span>辅助肌群</span><span>${escapeHtml(exercise.secondaryMuscles.join("、"))}</span></div>` : ""}
            ${exercise.riskTags && exercise.riskTags.length ? `<div class="kv"><span>风险提示</span><span>${escapeHtml(exercise.riskTags.join("、"))}</span></div>` : ""}
            ${exercise.alternativeGroup ? `<div class="kv"><span>替代组</span><span>${escapeHtml(exercise.alternativeGroup)}</span></div>` : ""}
            <div class="kv"><span>来源</span><span>${escapeHtml(exercise.sourceName || "动作数据集")}${exercise.sourceId ? ` · ${escapeHtml(exercise.sourceId)}` : ""}${exercise.sourceVersion ? ` · v${escapeHtml(exercise.sourceVersion)}` : ""}</span></div>
            ${exercise.mediaCredit ? `<div class="kv"><span>媒体署名</span><span>${escapeHtml(exercise.mediaCredit)}</span></div>` : ""}
        </div>
        ${chips.length ? `<div class="chips">${chips.map((chip) => `<span class="chip selected">${escapeHtml(chip)}</span>`).join("")}</div>` : ""}
        ${exercise.instructionsZh ? `<p style="line-height:1.7;margin:0;">${escapeHtml(exercise.instructionsZh)}</p>` : ""}
        ${exercise.steps && exercise.steps.length ? `
            <div>
                <h3 style="margin:0 0 8px;">动作步骤</h3>
                <ol class="ordered-list">${exercise.steps.map((step) => `<li>${escapeHtml(step)}</li>`).join("")}</ol>
            </div>
        ` : ""}
        ${exercise.reason ? `<p class="muted" style="line-height:1.65;">推荐理由：${escapeHtml(exercise.reason)}</p>` : ""}
    `;
}

function renderRoutineBody(routine) {
    return `
        <div class="kv-list">
            <div class="kv"><span>事实来源</span><span>${escapeHtml(routine.sourceName || routine.sourceType || "-")}</span></div>
            ${routine.sourceVersion ? `<div class="kv"><span>来源版本</span><span>${escapeHtml(routine.sourceVersion)}</span></div>` : ""}
            ${routine.planReady ? `<div class="kv"><span>计划资格</span><span>已通过周计划资格审核</span></div>` : ""}
        </div>
        ${routine.reason ? `<p style="line-height:1.7;">${escapeHtml(routine.reason)}</p>` : ""}
    `;
}

function renderPlanItemBody(item) {
    const params = item.params || {};
    return `
        <div class="kv-list">
            <div class="kv"><span>类型</span><span>${escapeHtml(item.resourceType)}</span></div>
            <div class="kv"><span>日期</span><span>${escapeHtml(item.localDate)}</span></div>
            <div class="kv"><span>时间</span><span>${escapeHtml(formatTime(item.startTime))} - ${escapeHtml(formatTime(item.endTime))}</span></div>
            ${item.note ? `<div class="kv"><span>备注</span><span>${escapeHtml(item.note)}</span></div>` : ""}
            ${Object.keys(params).length ? `
                <div class="kv"><span>计划参数</span><span>${Object.entries(params).map(([key, value]) => `${escapeHtml(key)}：${escapeHtml(value)}`).join("；")}</span></div>
            ` : ""}
            ${item.reason ? `<div class="kv"><span>说明</span><span>${escapeHtml(item.reason)}</span></div>` : ""}
        </div>
    `;
}
