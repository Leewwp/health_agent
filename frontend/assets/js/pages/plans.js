/**
 * 我的计划页。
 *
 * - 计划列表：DRAFT / UNENABLED / ENABLED / HISTORY 状态与版本；
 * - 详情：以 weekStart 为周一渲染七天网格，按 localDate 落位项目；
 * - DRAFT：点击项目在统一详情抽屉内修改日期/时间/备注（PATCH）；
 * - ENABLED：停用后回到 UNENABLED；HISTORY 只能复制为新 DRAFT；
 * - 生成草稿需要健康档案；校验结果（validationHits）与档案过期标记展示给用户。
 */
import { escapeHtml, formatTime, formatDateLabel, localDateOf, newRequestId } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import {
    listPlans, getPlan, generateTrainingPlan, confirmPlan, enablePlan, disablePlan, archivePlan, copyPlan,
    editPlan, updatePlanItems, getMealDetail, getExerciseDetail, listMeals, listExercises
} from "../api.js";
import { registerResource, getOrCreateClientSessionId, getChatSessionId, isFavorite } from "../store.js";
import { openDrawer, closeDrawer, bindDrawer } from "../ui/detail-drawer.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { requestConfirmation } from "../ui/modal.js";
import { currentRoute, navigate } from "../router.js";
import { devConfig } from "../config.js";
import { planGenerationRequestKey, runPlanGenerationRequest } from "./plan-generation-request.js";

const WEEKDAY_LABELS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const RESOURCE_TYPE_LABELS = { MEAL: "餐", EXERCISE: "练", ROUTINE: "息" };

const state = {
    summaries: [],
    detail: null,
    selectedId: null,
    loading: false,
    loaded: false,
    error: null,
    saving: false,
    generating: false,
    generationError: null,
    generationAttemptedKey: null,
    generationTraceId: null,
    generationPlanId: null,
    detailFilter: "ALL",
    favoriteOnly: false,
    candidates: null,
    movingItemId: null
};

export async function render(app) {
    const generationRequestKey = planGenerationRequestKey(location.hash);
    if (generationRequestKey && state.generationAttemptedKey !== generationRequestKey) {
        state.generationAttemptedKey = generationRequestKey;
        startTrainingPlanGeneration(app);
        return;
    }
    if (!generationRequestKey) {
        state.generationAttemptedKey = null;
        state.generationError = null;
    }
    if (state.generating) {
        app.innerHTML = renderGenerationWaiting();
        bind(app);
        return;
    }
    if (state.generationError) {
        app.innerHTML = renderGenerationError();
        bind(app);
        return;
    }
    if (!state.loaded && !state.loading && !state.error) {
        state.loading = true;
        loadSummaries().then(() => {
            if (currentRoute() === "/plans") {
                render(app);
            }
        });
        app.innerHTML = `<section class="section"><div class="empty">计划加载中...</div></section>`;
        return;
    }
    if (state.error && !state.summaries.length) {
        app.innerHTML = `
            <section class="section">
                <div class="empty">
                    <span>加载失败：${escapeHtml(state.error)}</span>
                    <div class="button-row"><button class="btn soft" data-action="retry-plans">重试</button></div>
                </div>
            </section>
        `;
        bind(app);
        return;
    }

    app.innerHTML = `
        <section class="plans-page" aria-labelledby="plans-title">
            <header class="plans-header">
                <div>
                    <p class="eyebrow">每周节奏</p>
                    <h1 id="plans-title">我的计划</h1>
                    <p class="plans-lede">把已确认的训练与餐食安排放在同一张周表里，按天查看和调整。</p>
                </div>
                <div class="button-row plans-header-actions">
                    <a class="btn primary" href="#/chat">从聊天生成</a>
                    <a class="btn ghost" href="#/profile">健康档案</a>
                </div>
            </header>
            ${state.summaries.length ? renderPlanSelector() : ""}
            ${state.summaries.length
                ? `<section class="plans-detail-section">${state.detail ? renderDetail(state.detail) : `<div class="empty">选择一个计划查看七天视图。</div>`}</section>`
                : `<section class="plans-empty"><div class="empty"><strong>还没有周计划</strong><p>先在聊天中完成训练或餐食简报，确认后再生成第一份草稿。</p><a class="btn primary" href="#/chat">开始简报</a></div></section>`}
        </section>
    `;
    bind(app);
}

function renderGenerationWaiting() {
    return `
        <section class="section plan-generation-state" aria-live="polite" aria-busy="true">
            <div class="empty">
                <span class="loading-spinner" aria-hidden="true"></span>
                <strong>正在生成计划草稿</strong>
                <p class="muted">正在读取已确认简报、筛选审核资源并执行范围校验，请稍候。</p>
            </div>
        </section>
    `;
}

function renderGenerationError() {
    return `
        <section class="section plan-generation-state">
            <div class="empty">
                <strong>计划生成失败</strong>
                <p class="muted">${escapeHtml(state.generationError)}</p>
                <div class="button-row">
                    <button class="btn primary" data-action="retry-generation">重新生成</button>
                    <a class="btn ghost" href="#/chat">返回聊天</a>
                    <a class="btn soft" href="#/profile">完善健康档案</a>
                </div>
            </div>
        </section>
    `;
}

async function startTrainingPlanGeneration(app) {
    state.generating = true;
    state.generationError = null;
    render(app);
    try {
        const query = new URLSearchParams((location.hash || "").split("?")[1] || "");
        const planScope = query.get("scope") || "EXERCISE";
        const result = await runPlanGenerationRequest((payload, signal) => generateTrainingPlan(payload, { signal }), {
            sessionId: getChatSessionId(),
            requestId: query.get("requestId") || newRequestId(),
            planScope
        });
        state.generating = false;
        state.detail = result.plan;
        state.selectedId = result.plan?.id || null;
        state.generationTraceId = result.traceId || null;
        state.generationPlanId = result.plan?.id || result.planId || null;
        state.summaries = await listPlans();
        state.loaded = true;
        state.error = null;
        showToast(result.generationSource === "FALLBACK" ? "计划已生成（规则降级）" : "计划已生成（Agent）");
        history.replaceState(null, "", "#/plans");
        render(app);
    } catch (error) {
        state.generating = false;
        state.generationError = error.message || "训练计划生成失败";
        render(app);
    }
}

let listenersBound = false;

function bind(app) {
    if (listenersBound) {
        return;
    }
    listenersBound = true;
    app.addEventListener("click", handleClick);
    app.addEventListener("change", handleChange);
    bindDrawer(app);
    bindFeedbackControl(app);
}

/* ---------------- 数据 ---------------- */

async function loadSummaries() {
    try {
        state.summaries = await listPlans();
        const active = state.summaries.find((plan) => plan.status === "ENABLED");
        const preferred = active || state.summaries[0] || null;
        if (preferred && !state.selectedId) {
            state.selectedId = preferred.id;
        }
        if (state.selectedId) {
            await loadDetail(state.selectedId);
        }
    } catch (error) {
        state.error = error.message || "计划加载失败";
    } finally {
        state.loading = false;
        state.loaded = true;
    }
}

async function loadDetail(planId) {
    try {
        state.detail = await getPlan(planId);
        state.selectedId = planId;
        if (state.detail.planScope !== "COMPOSITE") {
            state.detailFilter = "ALL";
        }
    } catch (error) {
        showToast(error.message || "计划详情加载失败", "error");
    }
}

/* ---------------- 渲染 ---------------- */

function renderPlanSelector() {
    return `
        <div class="plan-selector" aria-label="计划选择">
            <label for="plan-select">当前计划</label>
            <select id="plan-select" data-action="select-plan-select">
                ${state.summaries.map((plan) => `<option value="${escapeHtml(plan.id)}" ${String(plan.id) === String(state.selectedId) ? "selected" : ""}>${escapeHtml(plan.weekStart)} · ${scopeLabel(plan.planScope)} · ${statusLabel(plan.status)}</option>`).join("")}
            </select>
            <span class="plan-selector-count">${state.summaries.length} 份计划，按更新时间排序</span>
        </div>
    `;
}

function statusLabel(status) {
    return { ENABLED: "已启用", UNENABLED: "未启用", DRAFT: "草稿", HISTORY: "历史" }[status] || status || "";
}

function scopeLabel(scope) {
    return { EXERCISE: "训练", MEAL: "餐食", COMPOSITE: "综合" }[scope] || "计划";
}

function statusBadge(status) {
    const map = {
        ENABLED: `<span class="badge">已启用</span>`,
        UNENABLED: `<span class="badge">未启用</span>`,
        DRAFT: `<span class="badge warn">草稿</span>`,
        HISTORY: `<span class="badge">历史</span>`
    };
    return map[status] || escapeHtml(status || "");
}

function renderDetail(plan) {
    const days = buildDays(plan.weekStart);
    const scope = plan.planScope || "EXERCISE";
    return `
        <div class="plans-detail-head">
            <div>
                <div class="plan-kicker"><span>${scopeLabel(scope)}计划</span><span>${escapeHtml(plan.weekStart)} 至 ${escapeHtml(days[6])}</span></div>
                <div class="plan-title-row">
                    ${editablePlan(plan)
                        ? `<input class="plan-name-input" data-plan-name value="${escapeHtml(plan.name || "")}" maxlength="128" aria-label="计划名称">`
                        : `<h2>${escapeHtml(plan.name || `${plan.weekStart} 当周安排`)}</h2>`}
                    ${statusBadge(plan.status)}${sourceBadge(plan.generationSource)}
                </div>
                <p>时区 ${escapeHtml(plan.timezone)} · 档案 v${escapeHtml(plan.profileVersionNo)} · 规则 ${escapeHtml(plan.rulesVersion)} · 版本 v${escapeHtml(plan.currentVersion)}${plan.profileStale ? " · 档案已更新，当前计划仍按生成快照计算" : ""}</p>
            </div>
            <div class="button-row plans-detail-actions">
                ${plan.status === "DRAFT" ? `<button class="btn primary" data-action="confirm-plan" data-plan-id="${escapeHtml(plan.id)}">确认草稿</button>` : ""}
                ${plan.status === "UNENABLED" ? `<button class="btn primary" data-action="enable-plan" data-plan-id="${escapeHtml(plan.id)}">启用计划</button>` : ""}
                ${plan.status === "ENABLED" ? `<button class="btn soft" data-action="disable-plan" data-plan-id="${escapeHtml(plan.id)}">停用计划</button>` : ""}
                ${plan.status === "DRAFT" || plan.status === "UNENABLED" ? `<button class="btn ghost danger-outline" data-action="archive-plan" data-plan-id="${escapeHtml(plan.id)}">转为历史</button>` : ""}
                ${plan.status === "HISTORY" ? `<button class="btn ghost" data-action="copy-plan" data-plan-id="${escapeHtml(plan.id)}">复制为草稿</button>` : ""}
                ${plan.status === "ENABLED" ? `<button class="btn ghost" data-action="edit-plan" data-plan-id="${escapeHtml(plan.id)}">创建编辑草稿</button>` : ""}
                ${devConfig.enableDevTraceLink && state.generationTraceId && String(state.generationPlanId) === String(plan.id)
                    ? `<button class="btn ghost" data-action="open-generation-trace" data-trace-id="${escapeHtml(state.generationTraceId)}">查看本次 Trace</button>` : ""}
            </div>
        </div>
        ${renderPlanStats(plan, scope)}
        ${renderScopeFilter()}
        ${editablePlan(plan) ? renderEditorToolbar() : ""}
        ${editablePlan(plan) && state.movingItemId ? renderMoveGrid(plan) : ""}
        ${plan.validationHits && plan.validationHits.length ? `
            <div class="plan-validation">
                <h3>计划校验结果</h3>
                <div class="chips">
                    ${plan.validationHits.map((hit) => `<span class="chip ${hit.severity === "BLOCK_PLAN" ? "warn" : "selected"}">${escapeHtml(hit.ruleCode)} · ${escapeHtml(hit.decision)}</span>`).join("")}
                </div>
                ${plan.validationHits.map((hit) => `
                    <p class="muted">${escapeHtml(hit.copy)}${hit.detail ? `（${escapeHtml(hit.detail)}）` : ""}</p>
                `).join("")}
            </div>
        ` : ""}
        <div class="week-grid">
            ${days.map((day, index) => renderDay(day, index, plan)).join("")}
        </div>
        ${plan.note ? `<p class="muted" style="margin-top:14px;">计划备注：${escapeHtml(plan.note)}</p>` : ""}
        ${plan.explanation ? `<p class="muted" style="margin-top:14px;line-height:1.7;">${escapeHtml(plan.explanation)}</p>` : ""}
    `;
}

function editablePlan(plan) {
    return plan.status === "DRAFT" || plan.status === "UNENABLED";
}

function renderEditorToolbar() {
    const movingItem = state.detail?.items?.find((item) => String(item.id) === String(state.movingItemId));
    return `<div class="plan-editor-toolbar">
        <div class="button-row">
            <button class="btn soft" data-action="add-plan-item">添加项目</button>
            <button class="btn primary" data-action="save-plan-name">保存计划</button>
        </div>
        <label class="check-field"><input type="checkbox" data-action="favorites-filter" ${state.favoriteOnly ? "checked" : ""}> 仅看收藏资源</label>
        ${movingItem ? `<span class="move-mode-label">正在移动：${escapeHtml(movingItem.name)}</span><button class="btn ghost" data-action="cancel-move">取消移动</button>` : `<span class="muted">编辑会在一次保存中校验整周时间轴。</span>`}
    </div>`;
}

function renderMoveGrid(plan) {
    const item = plan.items?.find((entry) => String(entry.id) === String(state.movingItemId));
    if (!item) {
        return "";
    }
    const duration = timeToMinutes(item.endTime) - timeToMinutes(item.startTime);
    if (duration <= 0 || duration % 30 !== 0) {
        return `<section class="plan-move-panel" aria-label="选择移动目标">
            <div class="plan-move-panel-head">
                <strong>暂时无法移动</strong>
                <span class="muted">当前项目时长不是半小时的整数倍，请先打开项目编辑并调整结束时间。</span>
            </div>
        </section>`;
    }
    const slots = Array.from({ length: 48 }, (_, index) => index * 30)
        .filter((start) => start + duration <= 24 * 60);
    const days = buildDays(plan.weekStart);
    return `<section class="plan-move-panel" aria-label="选择移动目标">
        <div class="plan-move-panel-head">
            <strong>选择目标时间</strong>
            <span class="muted">保留 ${duration} 分钟，保存时重新校验整周冲突。</span>
        </div>
        <div class="plan-move-grid">
            ${days.map((day, index) => `<div class="plan-move-day">
                <strong>${escapeHtml(formatDateLabel(day))} ${WEEKDAY_LABELS[index]}</strong>
                <div class="plan-move-slots">
                    ${slots.map((start) => `<button class="plan-move-slot ${day === item.localDate && start === timeToMinutes(item.startTime) ? "selected" : ""}" data-action="move-target" data-local-date="${escapeHtml(day)}" data-start-time="${minutesToTime(start)}">${minutesToTime(start)}</button>`).join("")}
                </div>
            </div>`).join("")}
        </div>
    </section>`;
}

function renderPlanStats(plan, scope) {
    const items = plan.items || [];
    const exerciseCount = items.filter((item) => item.resourceType === "EXERCISE").length;
    const mealCount = items.filter((item) => item.resourceType === "MEAL").length;
    const cards = scope === "EXERCISE"
        ? [`<strong>${exerciseCount}</strong><span>训练项目</span>`, `<strong>${new Set(items.filter((item) => item.resourceType === "EXERCISE").map((item) => item.localDate)).size}</strong><span>训练日</span>`]
        : scope === "MEAL"
            ? [`<strong>${mealCount}</strong><span>餐食项目</span>`, `<strong>${new Set(items.filter((item) => item.resourceType === "MEAL").map((item) => item.localDate)).size}</strong><span>覆盖天数</span>`]
            : [`<strong>${items.length}</strong><span>全部项目</span>`, `<strong>${exerciseCount}</strong><span>训练项目</span>`, `<strong>${mealCount}</strong><span>餐食项目</span>`];
    return `<div class="plan-stats">${cards.map((card) => `<div class="plan-stat">${card}</div>`).join("")}</div>`;
}

function renderScopeFilter() {
    return `<div class="scope-filter" role="tablist" aria-label="综合计划筛选">
        ${[["ALL", "全部"], ["EXERCISE", "训练"], ["MEAL", "餐食"]].map(([value, label]) => `<button class="scope-filter-btn ${state.detailFilter === value ? "active" : ""}" role="tab" aria-selected="${state.detailFilter === value}" data-action="filter-items" data-filter="${value}">${label}</button>`).join("")}
    </div>`;
}

function sourceBadge(source) {
    const labels = {
        AGENT: "Agent 生成",
        FALLBACK: "规则降级",
        RULE_COMPOSER: "规则组合",
        RULE_MEAL_COMPOSER: "餐食规则组合",
        COMPOSITE_RULE_MERGE: "综合规则合并"
    };
    if (!source || !labels[source]) return "";
    return ` <span class="badge ${source === "FALLBACK" ? "warn" : ""}">${labels[source]}</span>`;
}

function buildDays(weekStart) {
    const start = new Date(`${weekStart}T00:00:00`);
    return Array.from({ length: 7 }, (_, index) => {
        const date = new Date(start.getTime() + index * 24 * 60 * 60 * 1000);
        return localDateOf(date);
    });
}

function renderDay(day, index, plan) {
    const items = (plan.items || []).filter((item) => item.localDate === day)
        .filter((item) => state.detailFilter === "ALL" || item.resourceType === state.detailFilter)
        .sort((left, right) => Number(right.resourceType === "EXERCISE") - Number(left.resourceType === "EXERCISE"));
    const editable = plan.status === "DRAFT" || plan.status === "UNENABLED";
    return `
        <div class="week-day">
            <h4><span>${escapeHtml(formatDateLabel(day))}</span>${WEEKDAY_LABELS[index]}</h4>
            ${items.length ? items.map((item) => renderPlanItem(item, editable)).join("") : `<span class="muted" style="font-size:12px;">无安排</span>`}
            ${editable ? `<button class="week-add-slot" data-action="add-plan-item" data-local-date="${escapeHtml(day)}" data-start-time="12:00">添加到这一天</button>` : ""}
        </div>
    `;
}

function renderPlanItem(item, editable) {
    const key = `plan-item-${item.id}`;
    const type = RESOURCE_TYPE_LABELS[item.resourceType] || "·";
    const params = item.params || {};
    const parameterSummary = renderParameterSummary(params);
    const moving = String(state.movingItemId) === String(item.id);
    return `<div class="plan-item-row ${moving ? "selected" : ""}">
        <button class="plan-item ${String(item.resourceType || "").toLowerCase()}" aria-label="查看${escapeHtml(item.name)}详情" data-action="open-plan-item" data-item-key="${key}">
            <span>
                <span class="plan-item-type">${escapeHtml(type)}</span>
                <strong>${escapeHtml(item.name)}</strong>
            </span>
            <span class="plan-item-time">${escapeHtml(formatTime(item.startTime))} - ${escapeHtml(formatTime(item.endTime))}</span>
            ${parameterSummary ? `<span class="plan-item-params">${parameterSummary}</span>` : ""}
            ${item.note ? `<span class="muted" style="font-size:12px;">备注：${escapeHtml(item.note)}</span>` : ""}
            ${editable ? `<span class="muted" style="font-size:12px;">点击编辑</span>` : ""}
        </button>
        ${editable ? `<button class="plan-move-trigger" type="button" data-action="select-move-item" data-item-id="${escapeHtml(item.id)}" aria-label="选择${escapeHtml(item.name)}的移动目标" title="移动项目">↕</button>` : ""}
    </div>`;
}

function timeToMinutes(value) {
    const [hours, minutes] = formatTime(value).split(":").map(Number);
    return (hours || 0) * 60 + (minutes || 0);
}

function minutesToTime(minutes) {
    return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function renderParameterSummary(params) {
    const labels = [
        ["caloriesKcal", "热量", " kcal"],
        ["bodyPart", "部位", ""],
        ["durationMinutes", "时长", " 分钟"],
        ["sets", "组数", " 组"],
        ["reps", "次数", " 次"]
    ];
    return labels.filter(([key]) => params[key] != null && params[key] !== "")
        .map(([key, label, suffix]) => `${escapeHtml(label)} ${escapeHtml(params[key])}${suffix}`).join(" · ");
}

/* ---------------- 操作 ---------------- */

function handleClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
        return;
    }
    const action = target.dataset.action;
    if (action === "retry-plans") {
        state.error = null;
        state.summaries = [];
        state.detail = null;
        state.selectedId = null;
        state.loaded = false;
        render(document.getElementById("app"));
    } else if (action === "retry-generation") {
        startTrainingPlanGeneration(document.getElementById("app"));
    } else if (action === "select-plan") {
        selectPlan(target.dataset.planId);
    } else if (action === "filter-items") {
        state.detailFilter = target.dataset.filter || "ALL";
        render(document.getElementById("app"));
    } else if (action === "open-plan-item") {
        openPlanItem(target.dataset.itemKey);
    } else if (action === "select-move-item") {
        state.movingItemId = target.dataset.itemId;
        render(document.getElementById("app"));
    } else if (action === "cancel-move") {
        state.movingItemId = null;
        render(document.getElementById("app"));
    } else if (action === "move-target") {
        movePlanItem(target.dataset.localDate, target.dataset.startTime);
    } else if (action === "add-plan-item") {
        openNewPlanItem(target.dataset.localDate || state.detail?.weekStart, target.dataset.startTime || "12:00");
    } else if (action === "save-plan-name") {
        savePlanName();
    } else if (action === "confirm-plan") {
        confirm(target.dataset.planId);
    } else if (action === "enable-plan") {
        enable(target.dataset.planId);
    } else if (action === "disable-plan") {
        disable(target.dataset.planId);
    } else if (action === "archive-plan") {
        archive(target.dataset.planId);
    } else if (action === "copy-plan") {
        copy(target.dataset.planId);
    } else if (action === "edit-plan") {
        editAsDraft(target.dataset.planId);
    } else if (action === "open-generation-trace") {
        window.healthPendingTraceId = target.dataset.traceId;
        navigate("/admin/traces");
    }
}

function handleChange(event) {
    if (event.target.matches("[data-action='select-plan-select']")) {
        selectPlan(event.target.value);
    } else if (event.target.matches("[data-action='favorites-filter']")) {
        state.favoriteOnly = event.target.checked;
        state.candidates = null;
        render(document.getElementById("app"));
    }
}

async function selectPlan(planId) {
    await loadDetail(planId);
    render(document.getElementById("app"));
}

async function openPlanItem(itemKey) {
    const item = findPlanItem(itemKey);
    if (!item) {
        return;
    }
    const candidates = await loadCandidates();
    const key = `plan-drawer-${item.id}`;
    registerResource(key, { ...item, resourceType: item.resourceType, resourceId: item.resourceId, name: item.name });
    const context = {
        planId: state.detail.id,
        planItemId: item.id,
        sessionId: getOrCreateClientSessionId(),
        editForm: editablePlan(state.detail) ? renderEditForm(item, candidates) : "",
        detailLoader: item.resourceType === "MEAL"
            ? () => getMealDetail(item.resourceId)
            : item.resourceType === "EXERCISE" ? () => getExerciseDetail(item.resourceId) : null
    };
    if (context.editForm) {
        context.onSave = (values) => savePlanItem(item, values);
        context.onDelete = () => deletePlanItem(item);
    }
    openDrawer(key, context);
}

async function openNewPlanItem(localDate, startTime) {
    const candidates = await loadCandidates();
    const key = "plan-drawer-new";
    registerResource(key, {
        resourceType: "MEAL", resourceId: "new", name: "新增计划项目", localDate,
        startTime: `${startTime}:00`, endTime: `${nextHalfHour(startTime)}:00`, params: {}
    });
    openDrawer(key, {
        planId: state.detail.id,
        sessionId: getOrCreateClientSessionId(),
        editForm: renderEditForm(null, candidates, { localDate, startTime }),
        onSave: (values) => savePlanItem(null, values)
    });
}

function nextHalfHour(time) {
    const [hours, minutes] = time.split(":").map(Number);
    const total = hours * 60 + minutes + 30;
    return `${String(Math.floor(total / 60) % 24).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}

async function loadCandidates() {
    if (state.candidates) return state.candidates;
    try {
        const [meals, exercises] = await Promise.all([
            listMeals({ page: 1, size: 50 }), listExercises({ page: 1, size: 50 })
        ]);
        const mealItems = (meals.items || []).map((resource) => ({
            resourceType: "MEAL", resourceId: String(resource.id), name: resource.name,
            caloriesKcal: resource.nutrition?.caloriesKcal == null ? null : Number(resource.nutrition.caloriesKcal),
            mealTime: resource.tags?.mealTime?.[0] || resource.tags?.mealTimes?.[0] || ""
        }));
        const exerciseItems = (exercises.items || []).filter((resource) => resource.planReady).map((resource) => ({
            resourceType: "EXERCISE", resourceId: String(resource.id), name: resource.name,
            bodyPart: resource.bodyPart || "全身"
        }));
        state.candidates = [...mealItems, ...exerciseItems]
            .filter((resource) => !state.favoriteOnly || isFavorite(resource.resourceType, resource.resourceId));
    } catch (error) {
        state.candidates = [];
        showToast(error.message || "审核资源加载失败", "error");
    }
    return state.candidates;
}

function findPlanItem(itemKey) {
    const items = (state.detail && state.detail.items) || [];
    const id = itemKey.replace("plan-item-", "");
    return items.find((item) => String(item.id) === String(id));
}

function renderEditForm(item, candidates, defaults = {}) {
    const current = item || { resourceType: "MEAL", resourceId: "", params: {}, note: "" };
    const currentKey = `${current.resourceType}:${current.resourceId}`;
    const options = [...(candidates || [])];
    if (current.resourceId && !options.some((entry) => `${entry.resourceType}:${entry.resourceId}` === currentKey)) {
        options.unshift({ resourceType: current.resourceType, resourceId: current.resourceId, name: current.name,
            caloriesKcal: current.params?.caloriesKcal, bodyPart: current.params?.bodyPart });
    }
    const localDate = defaults.localDate || current.localDate || state.detail.weekStart;
    const startTime = defaults.startTime || (current.startTime || "12:00:00").slice(0, 5);
    const endTime = defaults.endTime || (current.endTime || nextHalfHour(startTime) + ":00").slice(0, 5);
    const params = current.params || {};
    return `
        <div class="card" data-edit-form="1">
            <h3 style="margin:0 0 10px;">编辑计划项目</h3>
            <p class="field-hint" style="margin-bottom:10px;">候选来自审核资源；保存时会同时校验餐食与训练的整周时间轴。</p>
            <div class="form-grid">
                <div class="field full">
                    <label>资源</label>
                    <select data-edit-field="resourceKey" required>
                        <option value="">请选择审核资源</option>
                        ${options.map((entry) => `<option value="${escapeHtml(`${entry.resourceType}:${entry.resourceId}`)}" ${`${entry.resourceType}:${entry.resourceId}` === currentKey ? "selected" : ""}>${escapeHtml(entry.resourceType === "MEAL" ? "餐食" : "训练")} · ${escapeHtml(entry.name || entry.resourceId)}</option>`).join("")}
                    </select>
                </div>
                <div class="field">
                    <label>日期</label>
                    <input type="date" data-edit-field="localDate" value="${escapeHtml(localDate)}" required>
                </div>
                <div class="field">
                    <label>开始时间</label>
                    <input type="time" step="1800" data-edit-field="startTime" value="${escapeHtml(startTime)}" required>
                </div>
                <div class="field">
                    <label>结束时间</label>
                    <input type="time" step="1800" data-edit-field="endTime" value="${escapeHtml(endTime)}" required>
                </div>
                <div class="field">
                    <label>餐食热量（kcal）</label>
                    <input type="number" min="1" step="1" data-edit-field="caloriesKcal" value="${escapeHtml(params.caloriesKcal || "")}">
                </div>
                <div class="field">
                    <label>训练时长（分钟）</label>
                    <input type="number" min="1" step="1" data-edit-field="durationMinutes" value="${escapeHtml(params.durationMinutes || "")}">
                </div>
                <div class="field">
                    <label>组数</label>
                    <input type="number" min="1" step="1" data-edit-field="sets" value="${escapeHtml(params.sets || "")}">
                </div>
                <div class="field">
                    <label>次数</label>
                    <input type="number" min="1" step="1" data-edit-field="reps" value="${escapeHtml(params.reps || "")}">
                </div>
                <div class="field full">
                    <label>备注（留空可清除）</label>
                    <textarea data-edit-field="note" placeholder="例如：减半主食">${escapeHtml(current.note || "")}</textarea>
                </div>
            </div>
            ${item ? `<button class="btn ghost danger-outline" data-drawer-delete="1">删除这个项目</button>` : ""}
        </div>
    `;
}

async function savePlanItem(item, values) {
    if (!values.resourceKey || !values.localDate || !values.startTime || !values.endTime) {
        showToast("日期和时间不能为空", "error");
        return;
    }
    if (values.startTime >= values.endTime || ![values.startTime, values.endTime].every((value) => /^\d{2}:(00|30)$/.test(value))) {
        showToast("结束时间必须晚于开始时间", "error");
        return;
    }
    const [resourceType, resourceId] = values.resourceKey.split(":");
    const candidate = (state.candidates || []).find((entry) => entry.resourceType === resourceType && entry.resourceId === resourceId);
    const previousParams = item?.params || {};
    const params = resourceType === "MEAL"
        ? { caloriesKcal: positiveInteger(values.caloriesKcal || candidate?.caloriesKcal || previousParams.caloriesKcal),
            ...(candidate?.mealTime || previousParams.mealTime ? { mealTime: candidate?.mealTime || previousParams.mealTime } : {}) }
        : { bodyPart: candidate?.bodyPart || previousParams.bodyPart || "全身",
            durationMinutes: positiveInteger(values.durationMinutes || previousParams.durationMinutes || 30),
            sets: positiveInteger(values.sets || previousParams.sets || 1),
            reps: positiveInteger(values.reps || previousParams.reps || 1) };
    if (Object.values(params).some((value) => value == null)) {
        showToast("请补全资源的计划参数", "error");
        return;
    }
    const nextItem = {
        id: item?.id || null, resourceType, resourceId,
        name: candidate?.name || item?.name || resourceId,
        localDate: values.localDate, startTime: `${values.startTime}:00`, endTime: `${values.endTime}:00`,
        note: values.note || "", planParams: params
    };
    const items = (state.detail.items || []).map((entry) => item && String(entry.id) === String(item.id)
        ? nextItem : { ...entry, planParams: entry.params || {} });
    if (!item) items.push(nextItem);
    await persistPlanItems(items, readPlanName(), true);
}

function positiveInteger(value) {
    const number = Number(value);
    return Number.isInteger(number) && number > 0 ? number : null;
}

function readPlanName() {
    return document.querySelector("[data-plan-name]")?.value?.trim() || state.detail.name;
}

async function savePlanName() {
    await persistPlanItems((state.detail.items || []).map((item) => ({ ...item, planParams: item.params || {} })), readPlanName(), false);
}

async function deletePlanItem(item) {
    if ((state.detail.items || []).length <= 1) {
        showToast("计划至少需要保留一个项目", "error");
        return;
    }
    const confirmed = await requestConfirmation({
        title: "删除计划项目", description: `确定删除“${item.name}”吗？`, confirmLabel: "删除"
    });
    if (!confirmed) return;
    const items = state.detail.items.filter((entry) => String(entry.id) !== String(item.id))
        .map((entry) => ({ ...entry, planParams: entry.params || {} }));
    await persistPlanItems(items, readPlanName(), true);
}

async function persistPlanItems(items, name, closeAfterSave) {
    if (state.saving) return;
    state.saving = true;
    try {
        await updatePlanItems(state.detail.id, {
            requestId: newRequestId(), expectedVersion: state.detail.currentVersion, name, items
        });
        showToast("计划已保存");
        state.movingItemId = null;
        if (closeAfterSave) closeDrawerFromSave();
        state.candidates = null;
        await loadDetail(state.detail.id);
        state.summaries = await listPlans();
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "保存失败", "error");
    } finally {
        state.saving = false;
    }
}

async function movePlanItem(localDate, startTime) {
    const item = state.detail?.items?.find((entry) => String(entry.id) === String(state.movingItemId));
    if (!item) {
        return;
    }
    const duration = timeToMinutes(item.endTime) - timeToMinutes(item.startTime);
    const start = timeToMinutes(startTime);
    if (duration <= 0 || duration % 30 !== 0) {
        showToast("项目时长必须是半小时的整数倍，请先调整结束时间", "error");
        return;
    }
    if (start + duration > 24 * 60) {
        showToast("项目不能跨午夜，请选择更早的时间", "error");
        return;
    }
    const endTime = minutesToTime(start + duration);
    const items = state.detail.items.map((entry) => String(entry.id) === String(item.id)
        ? { ...entry, localDate, startTime: `${startTime}:00`, endTime: `${endTime}:00`, planParams: entry.params || {} }
        : { ...entry, planParams: entry.params || {} });
    await persistPlanItems(items, readPlanName(), false);
}

function mutationPayload(planId) {
    const plan = state.detail && String(state.detail.id) === String(planId) ? state.detail
        : state.summaries.find((entry) => String(entry.id) === String(planId));
    return { requestId: newRequestId(), expectedVersion: plan?.currentVersion || 1 };
}

async function confirm(planId) {
    const plan = state.detail && String(state.detail.id) === String(planId) ? state.detail : null;
    const label = plan ? `${plan.weekStart} 当周计划` : "该计划";
    const confirmed = await requestConfirmation({
        title: `确认${label}`,
        description: "确认后计划会保存为未启用模板，之后可单独启用。",
        confirmLabel: "确认草稿"
    });
    if (!confirmed) return;
    try {
        await confirmPlan(planId, mutationPayload(planId));
        state.summaries = await listPlans();
        await loadDetail(planId);
        showToast("草稿已确认，计划现在未启用");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "确认失败", "error");
    }
}

async function enable(planId) {
    const confirmed = await requestConfirmation({
        title: "启用这份计划",
        description: "当前已启用计划会回到未启用，原计划不会自动进入历史。",
        confirmLabel: "确认启用"
    });
    if (!confirmed) return;
    try {
        await enablePlan(planId, mutationPayload(planId));
        state.summaries = await listPlans();
        await loadDetail(planId);
        showToast("计划已启用");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "启用失败", "error");
    }
}

async function disable(planId) {
    try {
        await disablePlan(planId, mutationPayload(planId));
        state.summaries = await listPlans();
        await loadDetail(planId);
        showToast("计划已停用，回到未启用");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "停用失败", "error");
    }
}

async function archive(planId) {
    const confirmed = await requestConfirmation({
        title: "转为历史计划",
        description: "历史计划只读，但仍会保留在计划列表中。",
        confirmLabel: "确认归档"
    });
    if (!confirmed) return;
    try {
        await archivePlan(planId, mutationPayload(planId));
        state.summaries = await listPlans();
        state.detail = null;
        state.selectedId = null;
        if (state.summaries.length) await loadDetail(state.summaries[0].id);
        showToast("计划已转为历史");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "归档失败", "error");
    }
}

async function copy(planId) {
    try {
        const source = state.summaries.find((entry) => String(entry.id) === String(planId));
        const draft = await copyPlan(planId, {
            requestId: newRequestId(), expectedVersion: source?.currentVersion || 1
        });
        state.summaries = await listPlans();
        state.detail = draft;
        state.selectedId = draft.id;
        showToast("历史计划已复制为草稿");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "复制失败", "error");
    }
}

async function editAsDraft(planId) {
    const confirmed = await requestConfirmation({
        title: "创建编辑草稿",
        description: "系统会复制当前已启用计划，原计划继续保持启用。",
        confirmLabel: "创建草稿"
    });
    if (!confirmed) {
        return;
    }
    try {
        const draft = await editPlan(planId);
        state.summaries = await listPlans();
        state.detail = draft;
        state.selectedId = draft.id;
        showToast("已创建编辑草稿");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "创建编辑草稿失败", "error");
    }
}

function closeDrawerFromSave() {
    closeDrawer();
}
