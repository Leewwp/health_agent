/**
 * 我的计划页。
 *
 * - 计划列表：DRAFT / ACTIVE / ARCHIVED 状态与版本，历史（归档）计划同样列出；
 * - 详情：以 weekStart 为周一渲染七天网格，按 localDate 落位项目；
 * - DRAFT：点击项目在统一详情抽屉内修改日期/时间/备注（PATCH）；
 * - ACTIVE：编辑先复制为新 DRAFT（POST /edit），激活需确认（POST /activate）；
 * - 生成草稿需要健康档案；校验结果（validationHits）与档案过期标记展示给用户。
 */
import { escapeHtml, formatTime, formatDateLabel, localDateOf, newRequestId } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { listPlans, getPlan, generateTrainingPlan, activatePlan, editPlan, patchPlanItem } from "../api.js";
import { registerResource, getOrCreateClientSessionId, getChatSessionId } from "../store.js";
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
    detailFilter: "ALL"
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
        const active = state.summaries.find((plan) => plan.status === "ACTIVE");
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
    return { ACTIVE: "已激活", DRAFT: "草稿", ARCHIVED: "历史" }[status] || status || "";
}

function scopeLabel(scope) {
    return { EXERCISE: "训练", MEAL: "餐食", COMPOSITE: "综合" }[scope] || "计划";
}

function statusBadge(status) {
    const map = {
        ACTIVE: `<span class="badge">已激活</span>`,
        DRAFT: `<span class="badge warn">草稿</span>`,
        ARCHIVED: `<span class="badge">已归档（历史版本）</span>`
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
                <h2>${escapeHtml(plan.weekStart)} 当周安排 ${statusBadge(plan.status)}${sourceBadge(plan.generationSource)}</h2>
                <p>时区 ${escapeHtml(plan.timezone)} · 档案 v${escapeHtml(plan.profileVersionNo)} · 规则 ${escapeHtml(plan.rulesVersion)} · 版本 v${escapeHtml(plan.currentVersion)}${plan.profileStale ? " · 档案已更新，当前计划仍按生成快照计算" : ""}</p>
            </div>
            <div class="button-row plans-detail-actions">
                ${plan.status === "DRAFT" ? `<button class="btn primary" data-action="activate-plan" data-plan-id="${escapeHtml(plan.id)}">激活计划</button>` : ""}
                ${plan.status === "ACTIVE" ? `<button class="btn ghost" data-action="edit-plan" data-plan-id="${escapeHtml(plan.id)}">编辑副本</button>` : ""}
                ${devConfig.enableDevTraceLink && state.generationTraceId && String(state.generationPlanId) === String(plan.id)
                    ? `<button class="btn ghost" data-action="open-generation-trace" data-trace-id="${escapeHtml(state.generationTraceId)}">查看本次 Trace</button>` : ""}
            </div>
        </div>
        ${renderPlanStats(plan, scope)}
        ${scope === "COMPOSITE" ? renderScopeFilter() : ""}
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
    const editable = plan.status === "DRAFT";
    return `
        <div class="week-day">
            <h4><span>${escapeHtml(formatDateLabel(day))}</span>${WEEKDAY_LABELS[index]}</h4>
            ${items.length ? items.map((item) => renderPlanItem(item, editable)).join("") : `<span class="muted" style="font-size:12px;">无安排</span>`}
        </div>
    `;
}

function renderPlanItem(item, editable) {
    const key = `plan-item-${item.id}`;
    const type = RESOURCE_TYPE_LABELS[item.resourceType] || "·";
    const params = item.params || {};
    const kcal = params.caloriesKcal != null ? ` · ${escapeHtml(params.caloriesKcal)} kcal` : "";
    return `
        <button class="plan-item ${String(item.resourceType || "").toLowerCase()}" aria-label="查看${escapeHtml(item.name)}详情" data-action="open-plan-item" data-item-key="${key}">
            <span>
                <span class="plan-item-type">${escapeHtml(type)}</span>
                <strong>${escapeHtml(item.name)}</strong>
            </span>
            <span class="plan-item-time">${escapeHtml(formatTime(item.startTime))} - ${escapeHtml(formatTime(item.endTime))}${kcal}</span>
            ${item.note ? `<span class="muted" style="font-size:12px;">备注：${escapeHtml(item.note)}</span>` : ""}
            ${editable ? `<span class="muted" style="font-size:12px;">点击编辑</span>` : ""}
        </button>
    `;
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
    } else if (action === "activate-plan") {
        activate(target.dataset.planId);
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
    }
}

async function selectPlan(planId) {
    await loadDetail(planId);
    render(document.getElementById("app"));
}

function openPlanItem(itemKey) {
    const item = findPlanItem(itemKey);
    if (!item) {
        return;
    }
    const key = `plan-drawer-${item.id}`;
    registerResource(key, { ...item, resourceType: item.resourceType, resourceId: item.resourceId, name: item.name });
    const context = {
        planId: state.detail.id,
        planItemId: item.id,
        sessionId: getOrCreateClientSessionId(),
        editForm: state.detail.status === "DRAFT" ? renderEditForm(item) : ""
    };
    if (context.editForm) {
        context.onSave = (values) => savePlanItem(item, values);
    }
    openDrawer(key, context);
}

function findPlanItem(itemKey) {
    const items = (state.detail && state.detail.items) || [];
    const id = itemKey.replace("plan-item-", "");
    return items.find((item) => String(item.id) === String(id));
}

function renderEditForm(item) {
    return `
        <div class="card" data-edit-form="1">
            <h3 style="margin:0 0 10px;">编辑计划项目</h3>
            <p class="field-hint" style="margin-bottom:10px;">只允许修改日期、时间和备注；不修改营养、训练剂量与作息规则。</p>
            <div class="form-grid">
                <div class="field">
                    <label>日期</label>
                    <input type="date" data-edit-field="localDate" value="${escapeHtml(item.localDate || "")}" required>
                </div>
                <div class="field">
                    <label>开始时间</label>
                    <input type="time" data-edit-field="startTime" value="${escapeHtml((item.startTime || "").slice(0, 5))}" required>
                </div>
                <div class="field">
                    <label>结束时间</label>
                    <input type="time" data-edit-field="endTime" value="${escapeHtml((item.endTime || "").slice(0, 5))}" required>
                </div>
                <div class="field full">
                    <label>备注（留空可清除）</label>
                    <textarea data-edit-field="note" placeholder="例如：减半主食">${escapeHtml(item.note || "")}</textarea>
                </div>
            </div>
        </div>
    `;
}

async function savePlanItem(item, values) {
    if (!values.localDate || !values.startTime || !values.endTime) {
        showToast("日期和时间不能为空", "error");
        return;
    }
    if (values.startTime >= values.endTime) {
        showToast("结束时间必须晚于开始时间", "error");
        return;
    }
    if (state.saving) {
        return;
    }
    state.saving = true;
    try {
        await patchPlanItem(state.detail.id, item.id, {
            localDate: values.localDate,
            startTime: `${values.startTime}:00`,
            endTime: `${values.endTime}:00`,
            note: values.note || ""
        });
        showToast("计划项目已保存");
        closeDrawerFromSave();
        await loadDetail(state.detail.id);
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "保存失败", "error");
    } finally {
        state.saving = false;
    }
}

async function activate(planId) {
    const plan = state.detail && String(state.detail.id) === String(planId) ? state.detail : null;
    const label = plan ? `${plan.weekStart} 当周计划` : "该计划";
    const confirmed = await requestConfirmation({
        title: `激活${label}`,
        description: "激活后旧的已激活计划会自动归档，且本版本不可原地修改。",
        confirmLabel: "确认激活"
    });
    if (!confirmed) {
        return;
    }
    try {
        await activatePlan(planId);
        state.summaries = await listPlans();
        await loadDetail(planId);
        showToast("计划已激活");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "激活失败", "error");
    }
}

async function editAsDraft(planId) {
    const confirmed = await requestConfirmation({
        title: "创建编辑草稿",
        description: "系统会复制当前已激活计划，原版本继续作为历史记录保留。",
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
